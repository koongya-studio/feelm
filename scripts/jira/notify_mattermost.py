#!/usr/bin/env python3
"""Post the active sprint's unfinished Jira work to a Mattermost channel."""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import Request, urlopen


DEFAULT_ENV_FILE = Path(".env")
MAX_MESSAGE_LENGTH = 15_000


class DigestError(RuntimeError):
    """A configuration or remote API error safe to show in CI logs."""


def load_dotenv(path: Path) -> None:
    if not path.exists():
        return
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def required_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise DigestError(f"필수 환경변수가 없습니다: {name}")
    return value


def https_base_url(name: str) -> str:
    value = required_env(name).rstrip("/")
    parsed = urlsplit(value)
    if parsed.scheme != "https" or not parsed.netloc or parsed.path not in ("", "/"):
        raise DigestError(f"{name}은 경로가 없는 HTTPS URL이어야 합니다.")
    return value


@dataclass(frozen=True)
class JiraConfig:
    base_url: str
    project_key: str
    email: str
    api_token: str


@dataclass(frozen=True)
class MattermostConfig:
    base_url: str
    login_id: str
    password: str
    channel_id: str | None
    team_name: str | None
    channel_name: str | None


def load_jira_config() -> JiraConfig:
    return JiraConfig(
        base_url=https_base_url("JIRA_BASE_URL"),
        project_key=required_env("JIRA_PROJECT_KEY"),
        email=required_env("JIRA_EMAIL"),
        api_token=required_env("JIRA_API_TOKEN"),
    )


def load_mattermost_config() -> MattermostConfig:
    channel_id = os.environ.get("MATTERMOST_CHANNEL_ID", "").strip() or None
    team_name = os.environ.get("MATTERMOST_TEAM_NAME", "").strip() or None
    channel_name = os.environ.get("MATTERMOST_CHANNEL_NAME", "").strip() or None
    if not channel_id and not (team_name and channel_name):
        raise DigestError(
            "MATTERMOST_CHANNEL_ID 또는 MATTERMOST_TEAM_NAME과 "
            "MATTERMOST_CHANNEL_NAME이 필요합니다."
        )
    return MattermostConfig(
        base_url=https_base_url("MATTERMOST_BASE_URL"),
        login_id=required_env("MATTERMOST_LOGIN_ID"),
        password=required_env("MATTERMOST_PASSWORD"),
        channel_id=channel_id,
        team_name=team_name,
        channel_name=channel_name,
    )


def api_request(
    url: str,
    *,
    method: str = "GET",
    payload: dict[str, Any] | None = None,
    headers: dict[str, str] | None = None,
) -> tuple[Any, Any]:
    request_headers = {"Accept": "application/json", **(headers or {})}
    data = None
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request_headers["Content-Type"] = "application/json"
    request = Request(url, data=data, headers=request_headers, method=method)
    try:
        with urlopen(request, timeout=30) as response:
            body = response.read()
            parsed = json.loads(body) if body else None
            return parsed, response.headers
    except HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise DigestError(f"외부 API 오류 ({error.code}): {detail[:500]}") from error
    except URLError as error:
        raise DigestError(f"외부 API 연결 오류: {error.reason}") from error


class JiraClient:
    def __init__(self, config: JiraConfig) -> None:
        self.config = config
        encoded = base64.b64encode(
            f"{config.email}:{config.api_token}".encode("utf-8")
        ).decode("ascii")
        self.headers = {
            "Authorization": f"Basic {encoded}",
            "Content-Type": "application/json",
        }

    def unfinished_active_sprint_issues(self) -> list[dict[str, Any]]:
        project = self.config.project_key.replace("\\", "\\\\").replace('"', '\\"')
        payload: dict[str, Any] = {
            "jql": (
                f'project = "{project}" AND sprint in openSprints() '
                "AND statusCategory != Done "
                "ORDER BY assignee ASC, status ASC, key ASC"
            ),
            "fields": ["summary", "assignee", "status"],
            "maxResults": 100,
        }
        issues: list[dict[str, Any]] = []
        seen_tokens: set[str] = set()
        while True:
            response, _ = api_request(
                f"{self.config.base_url}/rest/api/3/search/jql",
                method="POST",
                payload=payload,
                headers=self.headers,
            )
            if not isinstance(response, dict) or not isinstance(response.get("issues", []), list):
                raise DigestError("Jira 검색 응답 형식이 올바르지 않습니다.")
            issues.extend(response.get("issues", []))
            token = str(response.get("nextPageToken", "")).strip()
            if response.get("isLast") is True or not token:
                break
            if token in seen_tokens:
                raise DigestError("Jira 페이지 토큰이 반복되어 조회를 중단했습니다.")
            seen_tokens.add(token)
            payload["nextPageToken"] = token
        return issues


class MattermostClient:
    def __init__(self, config: MattermostConfig) -> None:
        self.config = config

    def login(self) -> str:
        _, headers = api_request(
            f"{self.config.base_url}/api/v4/users/login",
            method="POST",
            payload={"login_id": self.config.login_id, "password": self.config.password},
        )
        token = str(headers.get("Token", "")).strip()
        if not token:
            raise DigestError("Mattermost 로그인 응답에 세션 토큰이 없습니다.")
        return token

    def auth_headers(self, token: str) -> dict[str, str]:
        return {"Authorization": f"Bearer {token}"}

    def resolve_channel_id(self, token: str) -> str:
        if self.config.channel_id:
            return self.config.channel_id
        assert self.config.team_name and self.config.channel_name
        team, _ = api_request(
            f"{self.config.base_url}/api/v4/teams/name/"
            f"{quote(self.config.team_name, safe='')}",
            headers=self.auth_headers(token),
        )
        team_id = team.get("id") if isinstance(team, dict) else None
        if not team_id:
            raise DigestError("Mattermost 팀 ID를 확인하지 못했습니다.")
        channel, _ = api_request(
            f"{self.config.base_url}/api/v4/teams/{quote(str(team_id), safe='')}/channels/name/"
            f"{quote(self.config.channel_name, safe='')}",
            headers=self.auth_headers(token),
        )
        channel_id = channel.get("id") if isinstance(channel, dict) else None
        if not channel_id:
            raise DigestError("Mattermost 채널 ID를 확인하지 못했습니다.")
        return str(channel_id)

    def post(self, token: str, channel_id: str, message: str) -> None:
        response, _ = api_request(
            f"{self.config.base_url}/api/v4/posts",
            method="POST",
            payload={"channel_id": channel_id, "message": message},
            headers=self.auth_headers(token),
        )
        if not isinstance(response, dict) or not response.get("id"):
            raise DigestError("Mattermost 게시 응답에 post ID가 없습니다.")

    def logout(self, token: str) -> None:
        api_request(
            f"{self.config.base_url}/api/v4/users/logout",
            method="POST",
            payload={},
            headers=self.auth_headers(token),
        )


def issue_category(issue: dict[str, Any]) -> str:
    status = issue.get("fields", {}).get("status") or {}
    category_key = str((status.get("statusCategory") or {}).get("key", ""))
    return "진행 중" if category_key == "indeterminate" else "해야 할 일"


def normalize_issue(issue: dict[str, Any], jira_base_url: str) -> dict[str, str]:
    fields = issue.get("fields") or {}
    assignee = fields.get("assignee") or {}
    key = str(issue.get("key", "")).strip()
    summary = " ".join(str(fields.get("summary", "제목 없음")).split())
    return {
        "assignee": str(assignee.get("displayName") or "미할당"),
        "category": issue_category(issue),
        "key": key,
        "summary": summary,
        "url": f"{jira_base_url}/browse/{quote(key, safe='-')}",
    }


def build_message(
    issues: list[dict[str, Any]], jira_base_url: str, today: date | None = None
) -> str:
    day = today or date.today()
    header = f"### Jira 아침 작업 현황 · {day.isoformat()}"
    if not issues:
        return f"{header}\n\n활성 스프린트에 완료되지 않은 작업이 없습니다."

    normalized = [normalize_issue(issue, jira_base_url) for issue in issues]
    assignees: dict[str, dict[str, list[dict[str, str]]]] = {}
    for issue in normalized:
        groups = assignees.setdefault(issue["assignee"], {"진행 중": [], "해야 할 일": []})
        groups[issue["category"]].append(issue)

    lines = [header, "", f"활성 스프린트 미완료 작업: **{len(normalized)}개**"]
    for assignee in sorted(assignees, key=lambda name: (name == "미할당", name.casefold())):
        lines.extend(["", f"#### {assignee}"])
        for category in ("진행 중", "해야 할 일"):
            items = assignees[assignee][category]
            if not items:
                continue
            lines.append(f"**{category}** ({len(items)})")
            for item in items:
                lines.append(f"- [{item['key']}]({item['url']}) {item['summary']}")

    message = "\n".join(lines)
    if len(message) <= MAX_MESSAGE_LENGTH:
        return message
    suffix = "\n\n주의: 메시지 길이 제한으로 일부 작업이 생략되었습니다. Jira에서 전체 목록을 확인해 주세요."
    return message[: MAX_MESSAGE_LENGTH - len(suffix)].rstrip() + suffix


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="Mattermost에 보내지 않고 출력")
    parser.add_argument("--env-file", type=Path, default=DEFAULT_ENV_FILE)
    return parser.parse_args(argv)


def configure_utf8_streams() -> None:
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure:
            reconfigure(encoding="utf-8")


def run(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        load_dotenv(args.env_file)
        jira_config = load_jira_config()
        issues = JiraClient(jira_config).unfinished_active_sprint_issues()
        message = build_message(issues, jira_config.base_url)
        if args.dry_run:
            print(message)
            return 0

        mattermost = MattermostClient(load_mattermost_config())
        token: str | None = None
        primary_error: DigestError | None = None
        try:
            token = mattermost.login()
            channel_id = mattermost.resolve_channel_id(token)
            mattermost.post(token, channel_id, message)
        except DigestError as error:
            primary_error = error
        finally:
            if token:
                try:
                    mattermost.logout(token)
                except DigestError as logout_error:
                    if primary_error:
                        print(f"경고: Mattermost 로그아웃 실패: {logout_error}", file=sys.stderr)
                    else:
                        primary_error = DigestError(f"Mattermost 로그아웃 실패: {logout_error}")
        if primary_error:
            raise primary_error
        print(f"Mattermost 일일 작업 요약 전송 완료: {len(issues)}개")
        return 0
    except DigestError as error:
        print(f"오류: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    configure_utf8_streams()
    raise SystemExit(run())
