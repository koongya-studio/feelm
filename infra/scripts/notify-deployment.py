#!/usr/bin/env python3
"""Send non-blocking GitLab deployment and develop failure notifications."""

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
MERGE_TITLE = re.compile(r"^Merge branch '([^']+)' into '([^']+)'$")
DEPLOYMENT_SERVICES = (
    "backend",
    "frontend",
    "consumer",
    "redis",
    "postgresql",
    "monitoring",
    "hdfs-service-config",
    "hdfs-distributed-config",
    "kafka-config",
    "spark-service",
    "spark-distributed",
    "spark-code",
)


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
    service: str = "Mattermost",
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
        raise DeploymentNotificationError(f"{service} HTTP error: {error.code}") from error
    except URLError as error:
        raise DeploymentNotificationError(f"{service} connection error") from error


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


def safe_person(value: str) -> str:
    name = value.split("<", 1)[0].strip()
    normalized = re.sub(r"[^\w .:/-]+", "-", name, flags=re.UNICODE).strip("- ")
    return normalized or "unknown"


def pipeline_context(environ: dict[str, str]) -> list[str]:
    branch = safe_value(environ.get("CI_COMMIT_BRANCH", ""))
    source = safe_value(environ.get("CI_PIPELINE_SOURCE", ""))
    title = environ.get("CI_COMMIT_TITLE", "").strip()
    merge = MERGE_TITLE.fullmatch(title)
    source_branch = safe_value(merge.group(1)) if merge else None
    target_branch = safe_value(merge.group(2)) if merge else branch

    if source == "push" and merge:
        trigger = f"{target_branch} 병합"
    elif source == "push":
        trigger = f"{branch} 반영"
    else:
        trigger = {
            "web": "수동 실행",
            "schedule": "스케줄 실행",
            "api": "API 실행",
            "merge_request_event": "MR 파이프라인",
        }.get(source, source)

    lines = [f"- 실행: `{trigger}` (`{source}`)"]
    author = environ.get("CI_COMMIT_AUTHOR", "").strip()
    if author:
        lines.append(f"- 작업자: `{safe_person(author)}`")
    if source_branch:
        lines.append(f"- 원본 브랜치: `{source_branch}`")
    lines.append(f"- 대상 브랜치: `{target_branch}`")
    return lines


def build_message(service: str, status: str, environ: dict[str, str]) -> str:
    labels = {
        "success": ("✅", "성공"),
        "failed": ("❌", "실패"),
        "canceled": ("⚠️", "취소"),
        "candidate_rejected": ("⚠️", "후보 거부 · 운영 상태 유지"),
        "auto_rollback": ("⚠️", "후보 실패 · 자동 복구 완료"),
        "manual_recovery": ("❌", "복구 실패 · 수동 복구 필요"),
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
    lines.extend(pipeline_context(environ))
    if job_url.startswith("https://"):
        lines.append(f"- [배포 Job 확인]({job_url})")
    if pipeline_url.startswith("https://"):
        lines.append(f"- [Pipeline 확인]({pipeline_url})")
    return "\n".join(lines)


def pipeline_jobs(environ: dict[str, str]) -> list[dict[str, Any]]:
    api_url = required_env(environ, "CI_API_V4_URL").rstrip("/")
    parsed = urlsplit(api_url)
    if parsed.scheme != "https" or not parsed.netloc or parsed.query or parsed.fragment:
        raise DeploymentNotificationError("CI_API_V4_URL must be an HTTPS URL")
    project_id = required_env(environ, "CI_PROJECT_ID")
    pipeline_id = required_env(environ, "CI_PIPELINE_ID")
    if not project_id.isdigit() or not pipeline_id.isdigit():
        raise DeploymentNotificationError("GitLab project or pipeline ID is invalid")
    token = required_env(environ, "CI_JOB_TOKEN")
    jobs: list[dict[str, Any]] = []
    page = "1"
    seen_pages: set[str] = set()
    while page:
        if page in seen_pages:
            raise DeploymentNotificationError("GitLab jobs pagination repeated a page")
        seen_pages.add(page)
        response, headers = api_request(
            f"{api_url}/projects/{project_id}/pipelines/{pipeline_id}/jobs"
            f"?include_retried=false&per_page=100&page={page}",
            headers={"JOB-TOKEN": token},
            service="GitLab",
        )
        if not isinstance(response, list) or any(not isinstance(job, dict) for job in response):
            raise DeploymentNotificationError("GitLab jobs response is invalid")
        jobs.extend(response)
        page = str(headers.get("X-Next-Page", "")).strip()
        if page and not page.isdigit():
            raise DeploymentNotificationError("GitLab jobs pagination is invalid")
    return jobs


def failed_predeploy_jobs(jobs: list[dict[str, Any]], current_job_id: str) -> list[dict[str, Any]]:
    failures = []
    for job in jobs:
        name = str(job.get("name", ""))
        if (
            str(job.get("id", "")) == current_job_id
            or name.startswith("deploy-")
            or job.get("allow_failure") is True
            or job.get("status") != "failed"
        ):
            continue
        failures.append(job)
    return sorted(failures, key=lambda job: (str(job.get("stage", "")), str(job.get("name", ""))))


def build_pipeline_failure_message(
    failures: list[dict[str, Any]], environ: dict[str, str]
) -> str:
    commit = safe_value(environ.get("CI_COMMIT_SHORT_SHA", ""))
    pipeline_url = environ.get("CI_PIPELINE_URL", "").strip()
    lines = [
        "## 🚨 [CI] develop Pipeline 실패",
        f"> ❌ 실패 · 🧪 배포 이전 단계 · {len(failures)}개 Job",
        "",
        "**실패 Job**",
    ]
    for job in failures[:10]:
        name = safe_value(str(job.get("name", "")))
        stage = safe_value(str(job.get("stage", "")))
        web_url = str(job.get("web_url", "")).strip()
        if web_url.startswith("https://"):
            lines.append(f"- [{name}]({web_url}) · `{stage}`")
        else:
            lines.append(f"- `{name}` · `{stage}`")
    if len(failures) > 10:
        lines.append(f"- 그 외 {len(failures) - 10}개")
    lines.extend(["", "**Pipeline 정보**", f"- 커밋: `{commit}`"])
    lines.extend(pipeline_context(environ))
    if pipeline_url.startswith("https://"):
        lines.append(f"- [Pipeline 확인]({pipeline_url})")
    return "\n".join(lines)


def notify_pipeline_failure(environ: dict[str, str]) -> bool:
    try:
        failures = failed_predeploy_jobs(
            pipeline_jobs(environ), required_env(environ, "CI_JOB_ID")
        )
    except (DeploymentNotificationError, OSError, RuntimeError, ValueError) as error:
        print(f"경고: develop Pipeline 결과 조회 실패 ({type(error).__name__})", file=sys.stderr)
        return False
    if not failures:
        print("develop Pipeline 배포 이전 실패 없음: Mattermost 알림 생략")
        return False
    posted = post_message(build_pipeline_failure_message(failures, environ), environ)
    if posted:
        print("Mattermost develop Pipeline 실패 알림 전송 완료")
    return posted


def post_message(message: str, environ: dict[str, str]) -> bool:
    token: str | None = None
    client = None
    posted = False
    try:
        client = MattermostClient(load_mattermost_config(environ))
        token = client.login()
        channel_id = client.resolve_channel_id(token)
        client.post(token, channel_id, message)
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
    return True


def notify(service: str, status: str, environ: dict[str, str]) -> bool:
    posted = post_message(build_message(service, status, environ), environ)
    if posted:
        print(f"Mattermost 배포 알림 전송 완료: {safe_value(service)} {safe_value(status)}")
    return posted


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("service", choices=(*DEPLOYMENT_SERVICES, "pipeline-failure"))
    parser.add_argument("status", nargs="?")
    return parser.parse_args(argv)


def run(argv: list[str] | None = None, environ: dict[str, str] | None = None) -> int:
    args = parse_args(argv)
    if args.service == "pipeline-failure":
        if args.status is not None:
            raise SystemExit("pipeline-failure does not accept a status")
        notify_pipeline_failure(environ if environ is not None else os.environ)
        return 0
    if args.status is None:
        raise SystemExit("deployment status is required")
    notify(args.service, args.status, environ if environ is not None else os.environ)
    # A notification outage must never replace the deployment job's real status.
    return 0


if __name__ == "__main__":
    raise SystemExit(run())
