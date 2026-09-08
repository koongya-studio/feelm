#!/usr/bin/env python3
"""MR의 /claude-review 댓글을 보호된 develop 리뷰 파이프라인으로 전달한다."""

from __future__ import annotations

import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen


MAX_REQUEST_BYTES = 64 * 1024
COMMAND = "/claude-review"
MIN_ACCESS_LEVEL = 30
COOLDOWN_SECONDS = 300
SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
TRIGGER_PATH = "/hooks/gitlab/claude-review"


class TriggerError(RuntimeError):
    def __init__(self, code: str, status: int = 400):
        super().__init__(code)
        self.code = code
        self.status = status


class GitLabClient:
    def __init__(self, api_url: str, project_id: int, token: str, opener=urlopen):
        if not api_url.startswith("https://"):
            raise TriggerError("INVALID_CONFIG", 500)
        self.api_url = api_url.rstrip("/")
        self.project_id = project_id
        self.token = token
        self.opener = opener

    def request(self, method: str, path: str, payload: object | None = None):
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        headers = {"PRIVATE-TOKEN": self.token, "Accept": "application/json"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        request = Request(self.api_url + path, data=body, headers=headers, method=method)
        try:
            with self.opener(request, timeout=20) as response:
                raw = response.read(MAX_REQUEST_BYTES + 1)
                if len(raw) > MAX_REQUEST_BYTES:
                    raise TriggerError("GITLAB_RESPONSE_TOO_LARGE", 502)
                return json.loads(raw)
        except TriggerError:
            raise
        except HTTPError as error:
            if error.code in (401, 403):
                raise TriggerError("GITLAB_AUTHENTICATION_FAILED", 502) from None
            if error.code == 404:
                raise TriggerError("GITLAB_RESOURCE_NOT_FOUND", 404) from None
            if error.code == 429:
                raise TriggerError("GITLAB_RATE_LIMITED", 503) from None
            raise TriggerError("GITLAB_API_FAILED", 502) from None
        except (URLError, TimeoutError, ValueError, TypeError, json.JSONDecodeError):
            raise TriggerError("GITLAB_API_FAILED", 502) from None

    def verify_and_trigger(self, iid: int, user_id: int) -> int:
        project = quote(str(self.project_id), safe="")
        member = self.request("GET", f"/projects/{project}/members/all/{user_id}")
        if (not isinstance(member, dict) or type(member.get("access_level")) is not int
                or member["access_level"] < MIN_ACCESS_LEVEL):
            raise TriggerError("INSUFFICIENT_ACCESS", 403)

        mr = self.request("GET", f"/projects/{project}/merge_requests/{iid}")
        if (not isinstance(mr, dict) or mr.get("iid") != iid or mr.get("state") != "opened"
                or mr.get("target_branch") != "develop"
                or mr.get("source_project_id") != self.project_id
                or mr.get("target_project_id") != self.project_id
                or not isinstance(mr.get("sha"), str) or not SHA_PATTERN.fullmatch(mr["sha"])):
            raise TriggerError("MR_NOT_REVIEWABLE", 409)

        pipeline = self.request("POST", f"/projects/{project}/pipeline", {
            "ref": "develop",
            "variables": [
                {"key": "CLAUDE_REVIEW_TRIGGER", "value": "comment"},
                {"key": "REVIEW_MR_IID", "value": str(iid)},
                {"key": "REVIEW_MR_SHA", "value": mr["sha"]},
            ],
        })
        if not isinstance(pipeline, dict) or type(pipeline.get("id")) is not int:
            raise TriggerError("INVALID_PIPELINE_RESPONSE", 502)
        return pipeline["id"]


def parse_note_event(payload: object, project_id: int) -> tuple[int, int]:
    if not isinstance(payload, dict) or payload.get("object_kind") != "note":
        raise TriggerError("IGNORED_EVENT", 202)
    project, attributes = payload.get("project"), payload.get("object_attributes")
    user, mr = payload.get("user"), payload.get("merge_request")
    if not all(isinstance(value, dict) for value in (project, attributes, user, mr)):
        raise TriggerError("INVALID_EVENT")
    if project.get("id") != project_id:
        raise TriggerError("PROJECT_MISMATCH", 403)
    note = attributes.get("note")
    if (attributes.get("noteable_type") != "MergeRequest"
            or attributes.get("action") not in (None, "create")
            or not isinstance(note, str) or note.strip() != COMMAND):
        raise TriggerError("IGNORED_EVENT", 202)
    iid, user_id = mr.get("iid"), user.get("id")
    if type(iid) is not int or iid <= 0 or type(user_id) is not int or user_id <= 0:
        raise TriggerError("INVALID_EVENT")
    return iid, user_id


class TriggerApplication:
    def __init__(self, project_id: int, webhook_token: str, client: GitLabClient,
                 clock=time.monotonic):
        self.project_id = project_id
        self.webhook_token = webhook_token
        self.client = client
        self.clock = clock
        self.recent: dict[int, float] = {}
        self.lock = threading.Lock()

    def handle(self, token: str, event: str, body: bytes) -> tuple[int, str]:
        if not hmac.compare_digest(token.encode("utf-8"), self.webhook_token.encode("utf-8")):
            raise TriggerError("INVALID_WEBHOOK_TOKEN", 403)
        if event != "Note Hook":
            raise TriggerError("IGNORED_EVENT", 202)
        try:
            payload = json.loads(body)
        except (UnicodeError, json.JSONDecodeError):
            raise TriggerError("INVALID_EVENT") from None
        iid, user_id = parse_note_event(payload, self.project_id)
        key = iid
        now = self.clock()
        with self.lock:
            if now - self.recent.get(key, -COOLDOWN_SECONDS) < COOLDOWN_SECONDS:
                raise TriggerError("REQUEST_COOLDOWN", 429)
            # 동시에 들어온 동일 요청도 막되 API 실패 시에는 재시도를 허용한다.
            self.recent[key] = now
        try:
            pipeline_id = self.client.verify_and_trigger(iid, user_id)
        except TriggerError:
            with self.lock:
                self.recent.pop(key, None)
            raise
        return 202, f"PIPELINE_TRIGGERED:{pipeline_id}"


class Handler(BaseHTTPRequestHandler):
    server_version = "ClaudeReviewTrigger/1"

    def setup(self):
        super().setup()
        self.connection.settimeout(10)

    def do_POST(self):  # noqa: N802
        try:
            if self.path != TRIGGER_PATH:
                raise TriggerError("NOT_FOUND", 404)
            if self.headers.get_content_type() != "application/json":
                raise TriggerError("INVALID_CONTENT_TYPE", 415)
            try:
                length = int(self.headers.get("Content-Length", ""))
            except ValueError:
                raise TriggerError("INVALID_LENGTH", 400) from None
            if length <= 0 or length > MAX_REQUEST_BYTES:
                raise TriggerError("INVALID_LENGTH", 413)
            status, message = self.server.application.handle(  # type: ignore[attr-defined]
                self.headers.get("X-Gitlab-Token", ""),
                self.headers.get("X-Gitlab-Event", ""), self.rfile.read(length))
        except TriggerError as error:
            status, message = error.status, error.code
        encoded = message.encode("ascii")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=us-ascii")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, _format, *_args):
        return


def credential(name: str) -> str:
    directory = os.environ.get("CREDENTIALS_DIRECTORY", "")
    try:
        value = (Path(directory) / name).read_text(encoding="utf-8").strip()
    except (OSError, UnicodeError):
        raise TriggerError("MISSING_CREDENTIAL", 500) from None
    if not value or any(character.isspace() for character in value):
        raise TriggerError("INVALID_CREDENTIAL", 500)
    return value


def main() -> None:
    try:
        project_id = int(os.environ.get("CLAUDE_REVIEW_PROJECT_ID", ""))
        port = int(os.environ.get("CLAUDE_REVIEW_WEBHOOK_PORT", "8765"))
    except ValueError:
        raise SystemExit("COMMENT_TRIGGER: INVALID_CONFIG") from None
    if project_id <= 0 or not 1024 <= port <= 65535:
        raise SystemExit("COMMENT_TRIGGER: INVALID_CONFIG")
    webhook_token = credential("webhook-token")
    api_token = credential("gitlab-api-token")
    client = GitLabClient(os.environ.get("CLAUDE_REVIEW_GITLAB_API_URL", ""), project_id, api_token)
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.application = TriggerApplication(project_id, webhook_token, client)  # type: ignore[attr-defined]
    server.serve_forever()


if __name__ == "__main__":
    main()
