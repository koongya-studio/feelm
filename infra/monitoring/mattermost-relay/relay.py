#!/usr/bin/env python3
"""Relay authenticated Grafana alerts to Mattermost with a user session."""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import Request, urlopen


MAX_BODY_BYTES = 256 * 1024
MAX_CLOCK_SKEW_SECONDS = 300


class RelayError(RuntimeError):
    """An alert relay error whose message never contains credentials."""


def required_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RelayError(f"missing required environment variable: {name}")
    return value


def mattermost_base_url() -> str:
    value = required_env("MATTERMOST_BASE_URL").rstrip("/")
    parsed = urlsplit(value)
    if parsed.scheme != "https" or not parsed.netloc or parsed.path not in ("", "/"):
        raise RelayError("MATTERMOST_BASE_URL must be a pathless HTTPS URL")
    return value


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
        raise RelayError(f"Mattermost HTTP error: {error.code}") from error
    except URLError as error:
        raise RelayError("Mattermost connection error") from error


def resolve_channel_id(base_url: str, token: str) -> str:
    channel_id = os.environ.get("MATTERMOST_CHANNEL_ID", "").strip()
    if channel_id:
        return channel_id
    team_name = os.environ.get("MATTERMOST_TEAM_NAME", "").strip()
    channel_name = os.environ.get("MATTERMOST_CHANNEL_NAME", "").strip()
    if not team_name or not channel_name:
        raise RelayError("Mattermost channel selector is missing")
    headers = {"Authorization": f"Bearer {token}"}
    team, _ = api_request(
        f"{base_url}/api/v4/teams/name/{quote(team_name, safe='')}",
        headers=headers,
    )
    team_id = team.get("id") if isinstance(team, dict) else None
    if not team_id:
        raise RelayError("Mattermost team was not found")
    channel, _ = api_request(
        f"{base_url}/api/v4/teams/{quote(str(team_id), safe='')}/channels/name/"
        f"{quote(channel_name, safe='')}",
        headers=headers,
    )
    resolved = channel.get("id") if isinstance(channel, dict) else None
    if not resolved:
        raise RelayError("Mattermost channel was not found")
    return str(resolved)


def post_to_mattermost(message: str) -> None:
    base_url = mattermost_base_url()
    token: str | None = None
    try:
        _, headers = api_request(
            f"{base_url}/api/v4/users/login",
            method="POST",
            payload={
                "login_id": required_env("MATTERMOST_LOGIN_ID"),
                "password": required_env("MATTERMOST_PASSWORD"),
            },
        )
        token = str(headers.get("Token", "")).strip()
        if not token:
            raise RelayError("Mattermost login returned no session token")
        channel_id = resolve_channel_id(base_url, token)
        response, _ = api_request(
            f"{base_url}/api/v4/posts",
            method="POST",
            payload={"channel_id": channel_id, "message": message},
            headers={"Authorization": f"Bearer {token}"},
        )
        if not isinstance(response, dict) or not response.get("id"):
            raise RelayError("Mattermost post returned no ID")
    finally:
        if token:
            try:
                api_request(
                    f"{base_url}/api/v4/users/logout",
                    method="POST",
                    payload={},
                    headers={"Authorization": f"Bearer {token}"},
                )
            except RelayError:
                pass


def verify_signature(body: bytes, signature: str, timestamp: str, now: int | None = None) -> None:
    try:
        sent_at = int(timestamp)
    except ValueError as error:
        raise RelayError("invalid webhook timestamp") from error
    current = int(time.time()) if now is None else now
    if abs(current - sent_at) > MAX_CLOCK_SKEW_SECONDS:
        raise RelayError("expired webhook timestamp")
    signed = timestamp.encode("ascii") + b":" + body
    expected = hmac.new(
        required_env("GRAFANA_ALERT_HMAC_SECRET").encode("utf-8"),
        signed,
        hashlib.sha256,
    ).hexdigest()
    if not hmac.compare_digest(expected, signature.lower()):
        raise RelayError("invalid webhook signature")


def clean_text(value: Any, fallback: str) -> str:
    text = str(value).strip() if value is not None else ""
    return text[:15_000] or fallback


def build_message(payload: dict[str, Any]) -> str:
    status = str(payload.get("status", "")).lower()
    title = clean_text(
        payload.get("title"),
        "🚨 [ALERT] 서버 장애 감지" if status == "firing" else "✅ [RECOVERY] 서버 장애 복구",
    )
    detail = clean_text(payload.get("message"), "Grafana에서 상세 내용을 확인하세요.")
    return f"## {title}\n\n{detail}"


class AlertHandler(BaseHTTPRequestHandler):
    server_version = "FEELMAlertRelay/1"

    def send_status(self, status: int) -> None:
        self.send_response(status)
        if status == 204:
            self.end_headers()
            return
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps({"status": status}).encode("ascii"))

    def do_GET(self) -> None:  # noqa: N802
        self.send_status(200 if self.path == "/healthz" else 404)

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/alerts":
            self.send_status(404)
            return
        try:
            content_length = int(self.headers.get("Content-Length", ""))
            if content_length < 1 or content_length > MAX_BODY_BYTES:
                raise RelayError("invalid request size")
            body = self.rfile.read(content_length)
            verify_signature(
                body,
                self.headers.get("X-Grafana-Alerting-Signature", ""),
                self.headers.get("X-Grafana-Alerting-Timestamp", ""),
            )
            payload = json.loads(body)
            if not isinstance(payload, dict):
                raise RelayError("invalid webhook payload")
        except (RelayError, json.JSONDecodeError, ValueError) as error:
            print(f"alert relay rejected request: {type(error).__name__}", flush=True)
            self.send_status(401 if isinstance(error, RelayError) and "signature" in str(error) else 400)
            return
        try:
            post_to_mattermost(build_message(payload))
        except Exception as error:
            print(f"alert relay delivery failed: {type(error).__name__}", flush=True)
            self.send_status(502)
            return
        self.send_status(204)

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"alert relay: {self.address_string()} {fmt % args}", flush=True)


def main() -> int:
    mattermost_base_url()
    required_env("MATTERMOST_LOGIN_ID")
    required_env("MATTERMOST_PASSWORD")
    required_env("GRAFANA_ALERT_HMAC_SECRET")
    if not os.environ.get("MATTERMOST_CHANNEL_ID", "").strip() and not (
        os.environ.get("MATTERMOST_TEAM_NAME", "").strip()
        and os.environ.get("MATTERMOST_CHANNEL_NAME", "").strip()
    ):
        raise RelayError("Mattermost channel selector is missing")
    ThreadingHTTPServer(("0.0.0.0", 8080), AlertHandler).serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
