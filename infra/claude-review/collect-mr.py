#!/usr/bin/env python3
"""GitLab API에서 실행하지 않을 MR 메타데이터와 diff만 제한적으로 수집한다."""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import stat
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen


MAX_RESPONSE_BYTES = 2 * 1024 * 1024
MAX_FILES = 100
MAX_FILE_DIFF_BYTES = 128 * 1024
MAX_TOTAL_DIFF_BYTES = 512 * 1024
MAX_TITLE_BYTES = 1024
MAX_DESCRIPTION_BYTES = 16 * 1024
OUTPUT_PATH = Path("/tmp/feelm-claude-review/input.json")
SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")


class CollectionError(RuntimeError):
    """안전하게 분류해 출력할 수 있는 수집 실패."""

    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


class GitLabClient:
    def __init__(self, api_url: str, project_id: int, token: str, opener=urlopen):
        if not api_url.startswith("https://"):
            raise CollectionError("INVALID_API_URL")
        self.api_url = api_url.rstrip("/")
        self.project_id = project_id
        self.token = token
        self.opener = opener

    def get(self, path: str, query: dict[str, object] | None = None):
        url = self.api_url + path
        if query:
            url += "?" + urlencode(query)
        request = Request(url, headers={"PRIVATE-TOKEN": self.token, "Accept": "application/json"})
        try:
            with self.opener(request, timeout=20) as response:
                length = response.headers.get("Content-Length")
                if length and int(length) > MAX_RESPONSE_BYTES:
                    raise CollectionError("GITLAB_RESPONSE_TOO_LARGE")
                body = response.read(MAX_RESPONSE_BYTES + 1)
                if len(body) > MAX_RESPONSE_BYTES:
                    raise CollectionError("GITLAB_RESPONSE_TOO_LARGE")
                return json.loads(body), response.headers
        except CollectionError:
            raise
        except HTTPError as error:
            if error.code in (401, 403):
                raise CollectionError("GITLAB_AUTHENTICATION_FAILED") from None
            if error.code == 404:
                raise CollectionError("MR_NOT_FOUND") from None
            if error.code == 429:
                raise CollectionError("GITLAB_RATE_LIMITED") from None
            raise CollectionError("GITLAB_API_FAILED") from None
        except (URLError, TimeoutError):
            raise CollectionError("GITLAB_CONNECTION_FAILED") from None
        except (ValueError, TypeError, json.JSONDecodeError):
            raise CollectionError("INVALID_GITLAB_RESPONSE") from None

    def collect(self, iid: int, expected_sha: str) -> dict[str, object]:
        project = quote(str(self.project_id), safe="")
        mr_path = f"/projects/{project}/merge_requests/{iid}"
        metadata, _ = self.get(mr_path)
        validate_metadata(metadata, self.project_id, iid, expected_sha)

        changes: list[dict[str, object]] = []
        page = 1
        while True:
            payload, headers = self.get(mr_path + "/diffs", {"per_page": 100, "page": page})
            if not isinstance(payload, list):
                raise CollectionError("INVALID_GITLAB_RESPONSE")
            changes.extend(payload)
            if len(changes) > MAX_FILES:
                raise CollectionError("DIFF_FILE_LIMIT_EXCEEDED")
            next_page = headers.get("X-Next-Page", "")
            if not next_page:
                break
            try:
                following_page = int(next_page)
            except (TypeError, ValueError):
                raise CollectionError("INVALID_GITLAB_RESPONSE") from None
            if following_page <= page or following_page > MAX_FILES:
                raise CollectionError("INVALID_GITLAB_RESPONSE")
            page = following_page

        return build_review_input(metadata, changes)


def positive_int(value: str, code: str) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        raise CollectionError(code) from None
    if number <= 0:
        raise CollectionError(code)
    return number


def validate_metadata(metadata: object, project_id: int, iid: int, expected_sha: str) -> None:
    if not isinstance(metadata, dict):
        raise CollectionError("INVALID_GITLAB_RESPONSE")
    if metadata.get("iid") != iid or metadata.get("state") != "opened":
        raise CollectionError("MR_NOT_OPEN")
    if metadata.get("target_branch") != "develop":
        raise CollectionError("TARGET_BRANCH_NOT_ALLOWED")
    if metadata.get("source_project_id") != project_id or metadata.get("target_project_id") != project_id:
        raise CollectionError("FORK_OR_DIFFERENT_PROJECT_REJECTED")
    actual_sha = metadata.get("sha")
    if not isinstance(actual_sha, str) or not SHA_PATTERN.fullmatch(actual_sha):
        raise CollectionError("INVALID_GITLAB_RESPONSE")
    if not SHA_PATTERN.fullmatch(expected_sha) or actual_sha != expected_sha:
        raise CollectionError("MR_SHA_MISMATCH")


def build_review_input(metadata: dict[str, object], changes: list[dict[str, object]]) -> dict[str, object]:
    files = []
    total_bytes = 0
    for change in changes:
        if not isinstance(change, dict):
            raise CollectionError("INVALID_GITLAB_RESPONSE")
        if change.get("too_large") or change.get("collapsed"):
            raise CollectionError("INCOMPLETE_DIFF_REJECTED")
        old_path, new_path, diff = change.get("old_path"), change.get("new_path"), change.get("diff")
        if not all(isinstance(value, str) for value in (old_path, new_path, diff)):
            raise CollectionError("INVALID_GITLAB_RESPONSE")
        size = len(diff.encode("utf-8"))
        if size > MAX_FILE_DIFF_BYTES:
            raise CollectionError("FILE_DIFF_LIMIT_EXCEEDED")
        total_bytes += size
        if total_bytes > MAX_TOTAL_DIFF_BYTES:
            raise CollectionError("TOTAL_DIFF_LIMIT_EXCEEDED")
        files.append({
            "old_path": old_path,
            "new_path": new_path,
            "new_file": change.get("new_file") is True,
            "renamed_file": change.get("renamed_file") is True,
            "deleted_file": change.get("deleted_file") is True,
            "generated_file": change.get("generated_file") is True,
            "diff": diff,
        })
    return {
        "untrusted_data": True,
        "merge_request": {
            "iid": metadata["iid"],
            "title": limited_text(metadata.get("title"), MAX_TITLE_BYTES),
            "description": limited_text(metadata.get("description") or "", MAX_DESCRIPTION_BYTES),
            "source_branch": limited_text(metadata.get("source_branch"), 1024),
            "target_branch": metadata["target_branch"],
            "sha": metadata["sha"],
        },
        "files": files,
        "limits": {"file_count": len(files), "total_diff_bytes": total_bytes},
    }


def limited_text(value: object, maximum_bytes: int) -> str:
    if not isinstance(value, str):
        raise CollectionError("INVALID_GITLAB_RESPONSE")
    if len(value.encode("utf-8")) > maximum_bytes:
        raise CollectionError("MR_METADATA_LIMIT_EXCEEDED")
    return value


def trusted_context(env: dict[str, str]) -> bool:
    return (env.get("CI_COMMIT_BRANCH") == "develop"
            and env.get("CI_COMMIT_REF_PROTECTED") == "true"
            and env.get("CI_ENVIRONMENT_NAME") == "claude-review"
            and env.get("CI_PIPELINE_SOURCE") in ("push", "web")
            and env.get("CI_DEBUG_TRACE", "").lower() not in ("true", "1")
            and env.get("CI_DEBUG_SERVICES", "").lower() not in ("true", "1"))


def write_private_json(path: Path, payload: dict[str, object]) -> None:
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    try:
        with temporary.open("x", encoding="utf-8", newline="\n") as output:
            json.dump(payload, output, ensure_ascii=False, separators=(",", ":"))
        temporary.chmod(stat.S_IRUSR | stat.S_IWUSR)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def main() -> int:
    try:
        if not trusted_context(os.environ):
            raise CollectionError("REJECTED_CI_CONTEXT")
        token = os.environ.get("GITLAB_REVIEW_TOKEN", "")
        if not token or any(character.isspace() for character in token):
            raise CollectionError("MISSING_OR_INVALID_TOKEN")
        project_id = positive_int(os.environ.get("CI_PROJECT_ID", ""), "INVALID_PROJECT_ID")
        iid = positive_int(os.environ.get("REVIEW_MR_IID", ""), "INVALID_MR_IID")
        expected_sha = os.environ.get("REVIEW_MR_SHA", "")
        client = GitLabClient(os.environ.get("CI_API_V4_URL", ""), project_id, token)
        # 이전 실행 결과가 실패한 새 실행의 입력으로 재사용되지 않게 먼저 제거한다.
        OUTPUT_PATH.unlink(missing_ok=True)
        write_private_json(OUTPUT_PATH, client.collect(iid, expected_sha))
        print("MR_COLLECTION: PASS")
        return 0
    except CollectionError as error:
        print("MR_COLLECTION: " + error.code)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
