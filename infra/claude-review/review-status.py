#!/usr/bin/env python3
"""보호된 Claude 리뷰 Job의 실행 상태를 하나의 MR 댓글로 표시한다."""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import stat
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen


STATUS_PATH = Path("/tmp/feelm-claude-review/status.json")
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
MAX_STATUS_BYTES = 16 * 1024
SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
ALLOWED_STATES = {"running", "success", "failed", "canceled"}
MARKER_NAME = "feelm-claude-review-status:v1"


class StatusError(RuntimeError):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


class GitLabClient:
    def __init__(self, api_url: str, project_id: int, token: str, opener=urlopen):
        if not api_url.startswith("https://"):
            raise StatusError("INVALID_API_URL")
        self.api_url = api_url.rstrip("/")
        self.project_id = project_id
        self.token = token
        self.opener = opener

    def request(self, method: str, path: str, payload: dict[str, object] | None = None):
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        headers = {"PRIVATE-TOKEN": self.token, "Accept": "application/json"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        request = Request(self.api_url + path, data=body, headers=headers, method=method)
        try:
            with self.opener(request, timeout=20) as response:
                raw = response.read(MAX_RESPONSE_BYTES + 1)
                if len(raw) > MAX_RESPONSE_BYTES:
                    raise StatusError("GITLAB_RESPONSE_TOO_LARGE")
                return json.loads(raw)
        except StatusError:
            raise
        except HTTPError as error:
            if error.code in (401, 403):
                raise StatusError("GITLAB_AUTHENTICATION_FAILED") from None
            if error.code == 404:
                raise StatusError("GITLAB_RESOURCE_NOT_FOUND") from None
            if error.code == 429:
                raise StatusError("GITLAB_RATE_LIMITED") from None
            raise StatusError("GITLAB_API_FAILED") from None
        except (URLError, TimeoutError, OSError, ValueError, TypeError,
                json.JSONDecodeError):
            raise StatusError("GITLAB_API_FAILED") from None

    def mr_path(self, iid: int) -> str:
        project = quote(str(self.project_id), safe="")
        return f"/projects/{project}/merge_requests/{iid}"

    def merge_request(self, iid: int) -> dict[str, object]:
        value = self.request("GET", self.mr_path(iid))
        if (not isinstance(value, dict) or value.get("iid") != iid
                or value.get("state") not in ("opened", "closed", "locked", "merged")
                or value.get("target_branch") != "develop"
                or value.get("source_project_id") != self.project_id
                or value.get("target_project_id") != self.project_id
                or not isinstance(value.get("sha"), str)
                or not SHA_PATTERN.fullmatch(value["sha"])):
            raise StatusError("MR_NO_LONGER_ELIGIBLE")
        return value

    def create_note(self, iid: int, body: str) -> int:
        value = self.request("POST", self.mr_path(iid) + "/notes", {"body": body})
        if (not isinstance(value, dict) or type(value.get("id")) is not int
                or value["id"] <= 0 or value.get("body") != body):
            raise StatusError("INVALID_NOTE_RESPONSE")
        return value["id"]

    def update_note(self, iid: int, note_id: int, body: str) -> None:
        value = self.request(
            "PUT", self.mr_path(iid) + f"/notes/{note_id}", {"body": body})
        if (not isinstance(value, dict) or value.get("id") != note_id
                or value.get("body") != body):
            raise StatusError("INVALID_NOTE_RESPONSE")


def positive_int(value: str, code: str) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        raise StatusError(code) from None
    if number <= 0:
        raise StatusError(code)
    return number


def trusted_context(env: dict[str, str]) -> bool:
    source = env.get("CI_PIPELINE_SOURCE")
    allowed_source = (source in ("push", "web")
                      or (source == "api" and env.get("CLAUDE_REVIEW_TRIGGER") == "comment"))
    return (env.get("CI_COMMIT_BRANCH") == "develop"
            and env.get("CI_COMMIT_REF_PROTECTED") == "true"
            and env.get("CI_ENVIRONMENT_NAME") == "claude-review"
            and allowed_source
            and env.get("CI_DEBUG_TRACE", "").lower() not in ("true", "1")
            and env.get("CI_DEBUG_SERVICES", "").lower() not in ("true", "1"))


def validate_pipeline_url(project_url: str, pipeline_url: str, pipeline_id: int) -> str:
    expected = project_url.rstrip("/") + f"/-/pipelines/{pipeline_id}"
    if (not project_url.startswith("https://") or pipeline_url != expected
            or len(pipeline_url) > 255 or any(character.isspace() for character in pipeline_url)):
        raise StatusError("INVALID_PIPELINE_URL")
    return pipeline_url


def marker(project_id: int, iid: int, sha: str, pipeline_id: int) -> str:
    return (f"<!-- {MARKER_NAME} project={project_id} mr={iid} "
            f"sha={sha} pipeline={pipeline_id} -->")


def format_comment(state: str, project_id: int, iid: int, sha: str, pipeline_id: int,
                   pipeline_url: str, stale: bool = False) -> str:
    labels = {
        "running": "🔄 리뷰 진행 중",
        "success": "✅ 리뷰 완료",
        "failed": "❌ 리뷰 실패",
        "canceled": "⏹️ 리뷰 취소",
    }
    details = {
        "running": "결과가 등록되기까지 약 2~3분 걸립니다.",
        "success": "리뷰 결과 댓글 등록을 완료했습니다.",
        "failed": "리뷰 생성 또는 댓글 등록에 실패했습니다. 파이프라인 로그를 확인해 주세요.",
        "canceled": "리뷰 Job이 취소되었습니다. 필요하면 다시 요청해 주세요.",
    }
    if stale:
        details["canceled"] = "새 Push 또는 MR 상태 변경으로 이 커밋의 리뷰를 중단했습니다."
    body = "\n\n".join([
        marker(project_id, iid, sha, pipeline_id),
        "## 🤖 Claude 자동 코드 리뷰",
        f"**상태:** {labels[state]}  \n**검토 커밋:** `{sha[:12]}`",
        details[state],
        f"[파이프라인 #{pipeline_id}]({pipeline_url})",
    ])
    if len(body.encode("utf-8")) > MAX_STATUS_BYTES:
        raise StatusError("STATUS_COMMENT_TOO_LARGE")
    return body


def write_private_status(path: Path, value: dict[str, object]) -> None:
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = path.with_name(path.name + f".tmp-{os.getpid()}")
    try:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(value, stream, separators=(",", ":"))
        os.replace(temporary, path)
        path.chmod(0o600)
    except (OSError, TypeError, ValueError):
        try:
            temporary.unlink(missing_ok=True)
        except OSError:
            pass
        raise StatusError("STATUS_FILE_WRITE_FAILED") from None


def load_private_status(path: Path, iid: int, sha: str, pipeline_id: int) -> int:
    try:
        details = path.lstat()
    except OSError:
        raise StatusError("STATUS_FILE_NOT_FOUND") from None
    if (not stat.S_ISREG(details.st_mode) or path.is_symlink()
            or details.st_size > MAX_STATUS_BYTES
            or (os.name != "nt" and (details.st_uid != os.getuid() or details.st_mode & 0o077))):
        raise StatusError("UNSAFE_STATUS_FILE")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        raise StatusError("INVALID_STATUS_FILE") from None
    if (not isinstance(value, dict)
            or set(value) != {"iid", "sha", "pipeline_id", "note_id"}
            or value.get("iid") != iid or value.get("sha") != sha
            or value.get("pipeline_id") != pipeline_id
            or type(value.get("note_id")) is not int
            or value["note_id"] <= 0):
        raise StatusError("STATUS_FILE_MISMATCH")
    return value["note_id"]


def remove_status_file(path: Path) -> None:
    try:
        path.unlink(missing_ok=True)
    except OSError:
        raise StatusError("STATUS_FILE_CLEANUP_FAILED") from None


def publish(client: GitLabClient, state: str, project_id: int, iid: int, sha: str,
            pipeline_id: int, pipeline_url: str, status_path: Path = STATUS_PATH) -> str:
    mr = client.merge_request(iid)
    stale = mr["sha"] != sha or mr["state"] != "opened"
    actual_state = "canceled" if stale else state
    body = format_comment(
        actual_state, project_id, iid, sha, pipeline_id, pipeline_url, stale)
    if state == "running":
        remove_status_file(status_path)
        note_id = client.create_note(iid, body)
        write_private_status(status_path, {
            "iid": iid, "sha": sha, "pipeline_id": pipeline_id, "note_id": note_id,
        })
    else:
        note_id = load_private_status(status_path, iid, sha, pipeline_id)
        client.update_note(iid, note_id, body)
        remove_status_file(status_path)
    return actual_state


def main(arguments: list[str] | None = None) -> int:
    arguments = sys.argv[1:] if arguments is None else arguments
    try:
        if len(arguments) != 1 or arguments[0] not in ALLOWED_STATES:
            raise StatusError("INVALID_STATUS")
        if not trusted_context(os.environ):
            raise StatusError("REJECTED_CI_CONTEXT")
        token = os.environ.get("GITLAB_REVIEW_TOKEN", "")
        if not token or any(character.isspace() for character in token):
            raise StatusError("MISSING_OR_INVALID_TOKEN")
        project_id = positive_int(os.environ.get("CI_PROJECT_ID", ""), "INVALID_PROJECT_ID")
        iid = positive_int(os.environ.get("REVIEW_MR_IID", ""), "INVALID_MR_IID")
        pipeline_id = positive_int(
            os.environ.get("CI_PIPELINE_ID", ""), "INVALID_PIPELINE_ID")
        sha = os.environ.get("REVIEW_MR_SHA", "")
        if not SHA_PATTERN.fullmatch(sha):
            raise StatusError("INVALID_MR_SHA")
        pipeline_url = validate_pipeline_url(
            os.environ.get("CI_PROJECT_URL", ""),
            os.environ.get("CI_PIPELINE_URL", ""), pipeline_id)
        client = GitLabClient(os.environ.get("CI_API_V4_URL", ""), project_id, token)
        result = publish(
            client, arguments[0], project_id, iid, sha, pipeline_id, pipeline_url)
        print("REVIEW_STATUS: " + result.upper())
        return 0
    except StatusError as error:
        print("REVIEW_STATUS: " + error.code)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
