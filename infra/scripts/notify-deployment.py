#!/usr/bin/env python3
"""Send a non-blocking GitLab deployment result with the shared Mattermost account."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from dataclasses import dataclass
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import Request, urlopen


SAFE_VALUE = re.compile(r"[^A-Za-z0-9._/:-]+")


class DeploymentNotificationError(RuntimeError):
    """A configuration or Mattermost API error safe to classify in CI logs."""


@dataclass(frozen=True)
class MattermostConfig:
    base_url: str
    login_id: str
    password: str
    channel_id: str | None
    team_name: str | None
    channel_name: str | None


def required_env(environ: dict[str, str], name: str) -> str:
    value = environ.get(name, "").strip()
    if not value:
        raise DeploymentNotificationError(f"missing required environment variable: {name}")
    return value


def load_mattermost_config(environ: dict[str, str]) -> MattermostConfig:
    base_url = required_env(environ, "MATTERMOST_BASE_URL").rstrip("/")
    parsed = urlsplit(base_url)
    if parsed.scheme != "https" or not parsed.netloc or parsed.path not in ("", "/"):
        raise DeploymentNotificationError("MATTERMOST_BASE_URL must be a pathless HTTPS URL")
    channel_id = environ.get("MATTERMOST_CHANNEL_ID", "").strip() or None
    team_name = environ.get("MATTERMOST_TEAM_NAME", "").strip() or None
    channel_name = environ.get("MATTERMOST_CHANNEL_NAME", "").strip() or None
    if not channel_id and not (team_name and channel_name):
        raise DeploymentNotificationError("Mattermost channel selector is missing")
    return MattermostConfig(
        base_url=base_url,
        login_id=required_env(environ, "MATTERMOST_LOGIN_ID"),
        password=required_env(environ, "MATTERMOST_PASSWORD"),
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
            return (json.loads(body) if body else None), response.headers
    except HTTPError as error:
        raise DeploymentNotificationError(f"Mattermost HTTP error: {error.code}") from error
    except URLError as error:
        raise DeploymentNotificationError("Mattermost connection error") from error


class MattermostClient:
    def __init__(self, config: MattermostConfig) -> None:
        self.config = config

    @staticmethod
    def auth_headers(token: str) -> dict[str, str]:
        return {"Authorization": f"Bearer {token}"}

    def login(self) -> str:
        _, headers = api_request(
            f"{self.config.base_url}/api/v4/users/login",
            method="POST",
            payload={"login_id": self.config.login_id, "password": self.config.password},
        )
        token = str(headers.get("Token", "")).strip()
        if not token:
            raise DeploymentNotificationError("Mattermost login returned no session token")
        return token

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
            raise DeploymentNotificationError("Mattermost team was not found")
        channel, _ = api_request(
            f"{self.config.base_url}/api/v4/teams/{quote(str(team_id), safe='')}/channels/name/"
            f"{quote(self.config.channel_name, safe='')}",
            headers=self.auth_headers(token),
        )
        channel_id = channel.get("id") if isinstance(channel, dict) else None
        if not channel_id:
            raise DeploymentNotificationError("Mattermost channel was not found")
        return str(channel_id)

    def post(self, token: str, channel_id: str, message: str) -> None:
        response, _ = api_request(
            f"{self.config.base_url}/api/v4/posts",
            method="POST",
            payload={"channel_id": channel_id, "message": message},
            headers=self.auth_headers(token),
        )
        if not isinstance(response, dict) or not response.get("id"):
            raise DeploymentNotificationError("Mattermost post returned no ID")

    def logout(self, token: str) -> None:
        api_request(
            f"{self.config.base_url}/api/v4/users/logout",
            method="POST",
            payload={},
            headers=self.auth_headers(token),
        )


def safe_value(value: str, fallback: str = "unknown") -> str:
    normalized = SAFE_VALUE.sub("-", value.strip()).strip("-")
    return normalized or fallback


def build_message(service: str, status: str, environ: dict[str, str]) -> str:
    labels = {
        "success": ("✅", "성공"),
        "failed": ("❌", "실패"),
        "canceled": ("⚠️", "취소"),
    }
    icon, status_label = labels.get(status, ("ℹ️", safe_value(status)))
    service_label = safe_value(service)
    environment = safe_value(environ.get("CI_ENVIRONMENT_NAME", ""))
    commit = safe_value(environ.get("CI_COMMIT_SHORT_SHA", ""))
    job_url = environ.get("CI_JOB_URL", "").strip()
    pipeline_url = environ.get("CI_PIPELINE_URL", "").strip()

    lines = [
        "## 🚀 [DEPLOY] 배포 결과",
        f"> {icon} {status_label} · 📦 {service_label} · 🌐 {environment}",
        "",
        "**🔎 배포 정보**",
        f"- 커밋: `{commit}`",
    ]
    if job_url.startswith("https://"):
        lines.append(f"- [배포 Job 확인]({job_url})")
    if pipeline_url.startswith("https://"):
        lines.append(f"- [Pipeline 확인]({pipeline_url})")
    return "\n".join(lines)


def notify(service: str, status: str, environ: dict[str, str]) -> bool:
    token: str | None = None
    client = None
    posted = False
    try:
        client = MattermostClient(load_mattermost_config(environ))
        token = client.login()
        channel_id = client.resolve_channel_id(token)
        client.post(token, channel_id, build_message(service, status, environ))
        posted = True
    except (DeploymentNotificationError, OSError, RuntimeError, ValueError) as error:
        print(
            f"경고: Mattermost 배포 알림 전송 실패 ({type(error).__name__})",
            file=sys.stderr,
        )
    finally:
        if token and client:
            try:
                client.logout(token)
            except (DeploymentNotificationError, OSError, RuntimeError, ValueError) as error:
                print(
                    f"경고: Mattermost 배포 알림 로그아웃 실패 ({type(error).__name__})",
                    file=sys.stderr,
                )
    if not posted:
        return False
    print(f"Mattermost 배포 알림 전송 완료: {safe_value(service)} {safe_value(status)}")
    return True


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("service", choices=("backend", "frontend", "consumer"))
    parser.add_argument("status")
    return parser.parse_args(argv)


def run(argv: list[str] | None = None, environ: dict[str, str] | None = None) -> int:
    args = parse_args(argv)
    notify(args.service, args.status, environ if environ is not None else os.environ)
    # A notification outage must never replace the deployment job's real status.
    return 0


if __name__ == "__main__":
    raise SystemExit(run())
