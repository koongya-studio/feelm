#!/usr/bin/env python3
"""Validate required CI variable contracts without disclosing their values."""

from __future__ import annotations

import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import sys
from typing import Callable
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONTRACT = ROOT / "infra" / "config" / "required-ci-variables.json"
KEY_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]*$")
IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_-]*$")
ALLOWED_FORMATS = {"identifier", "nonempty", "private-ipv4"}
PRIVATE_IPV4_NETWORKS = tuple(
    ipaddress.ip_network(network) for network in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
)
ALLOWED_ENTRY_FIELDS = {
    "key",
    "environment_scope",
    "variable_type",
    "protected",
    "masked",
    "hidden",
    "raw",
    "format",
}
METADATA_FIELDS = (
    "environment_scope",
    "variable_type",
    "protected",
    "masked",
    "hidden",
    "raw",
)


class PreflightError(RuntimeError):
    pass


def load_contract(path: Path = DEFAULT_CONTRACT) -> list[dict[str, object]]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise PreflightError(f"계약 파일을 읽을 수 없습니다: {type(error).__name__}") from error

    if not isinstance(payload, dict) or payload.get("schema_version") != 1:
        raise PreflightError("지원하지 않는 CI 변수 계약 버전입니다.")
    if set(payload) != {"schema_version", "variables"}:
        raise PreflightError("CI 변수 계약의 최상위 필드가 올바르지 않습니다.")
    variables = payload.get("variables")
    if not isinstance(variables, list) or not variables:
        raise PreflightError("CI 변수 계약이 비어 있습니다.")

    seen: set[tuple[str, str]] = set()
    validated: list[dict[str, object]] = []
    for index, entry in enumerate(variables, start=1):
        if not isinstance(entry, dict) or set(entry) != ALLOWED_ENTRY_FIELDS:
            raise PreflightError(f"CI 변수 계약 {index}번 항목의 필드가 올바르지 않습니다.")
        key = entry.get("key")
        scope = entry.get("environment_scope")
        if not isinstance(key, str) or KEY_PATTERN.fullmatch(key) is None:
            raise PreflightError(f"CI 변수 계약 {index}번 항목의 key가 올바르지 않습니다.")
        if not isinstance(scope, str) or not scope or scope == "*":
            raise PreflightError(f"{key}의 environment_scope가 구체적이지 않습니다.")
        identity = (key, scope)
        if identity in seen:
            raise PreflightError(f"중복된 CI 변수 계약입니다: {key} ({scope})")
        seen.add(identity)
        if entry.get("variable_type") != "env_var":
            raise PreflightError(f"{key}는 env_var 형식이어야 합니다.")
        if any(not isinstance(entry.get(field), bool) for field in
               ("protected", "masked", "hidden", "raw")):
            raise PreflightError(f"{key}의 보안 메타데이터 형식이 올바르지 않습니다.")
        if entry.get("protected") is not True:
            raise PreflightError(f"{key}는 Protected 변수여야 합니다.")
        if entry.get("masked") is True and entry.get("hidden") is not True:
            raise PreflightError(f"Secret 변수 {key}는 Hidden이어야 합니다.")
        if entry.get("format") not in ALLOWED_FORMATS:
            raise PreflightError(f"{key}의 값 형식 계약이 올바르지 않습니다.")
        validated.append(entry)
    return validated


def require_protected_main(environment: dict[str, str]) -> None:
    if not (
        environment.get("CI_COMMIT_BRANCH") == "main"
        and environment.get("CI_COMMIT_REF_PROTECTED") == "true"
        and environment.get("CI_PIPELINE_SOURCE") == "push"
        and environment.get("CI_ENVIRONMENT_NAME") == "service"
    ):
        raise PreflightError("실제 변수 검사는 보호된 main push의 service 환경에서만 실행합니다.")
    if environment.get("CI_DEBUG_TRACE", "").lower() == "true":
        raise PreflightError("CI_DEBUG_TRACE가 활성화된 상태에서는 Secret 검사를 실행하지 않습니다.")


def scoped_variables(variables: list[dict[str, object]], scope: str) -> list[dict[str, object]]:
    selected = [entry for entry in variables if entry["environment_scope"] == scope]
    if not selected:
        raise PreflightError(f"환경 범위 {scope}에 필요한 CI 변수 계약이 없습니다.")
    return selected


def validate_value(key: str, value: str, value_format: str) -> None:
    if not value or "\n" in value or "\r" in value:
        raise PreflightError(f"{key} 값이 없거나 허용되지 않는 개행을 포함합니다.")
    if value_format == "identifier" and IDENTIFIER_PATTERN.fullmatch(value) is None:
        raise PreflightError(f"{key} 값 형식이 올바르지 않습니다.")
    if value_format == "private-ipv4":
        try:
            address = ipaddress.ip_address(value)
        except ValueError as error:
            raise PreflightError(f"{key} 값 형식이 올바르지 않습니다.") from error
        if address.version != 4 or not any(address in network for network in PRIVATE_IPV4_NETWORKS):
            raise PreflightError(f"{key}는 사설 IPv4 주소여야 합니다.")


def validate_injected_values(
    variables: list[dict[str, object]], environment: dict[str, str]
) -> None:
    errors: list[str] = []
    for entry in variables:
        key = str(entry["key"])
        try:
            validate_value(key, environment.get(key, ""), str(entry["format"]))
        except PreflightError as error:
            errors.append(str(error))
    if errors:
        raise PreflightError("; ".join(errors))


def validate_api_url(value: str) -> str:
    parsed = urlsplit(value)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
        raise PreflightError("CI_API_V4_URL은 인증정보가 없는 HTTPS 주소여야 합니다.")
    if parsed.query or parsed.fragment:
        raise PreflightError("CI_API_V4_URL에 query 또는 fragment를 사용할 수 없습니다.")
    return value.rstrip("/")


def fetch_variable_metadata(
    api_url: str,
    project_id: str,
    token: str,
    key: str,
    scope: str,
    opener: Callable[..., object] = urlopen,
) -> dict[str, object]:
    if not project_id.isdigit():
        raise PreflightError("CI_PROJECT_ID 형식이 올바르지 않습니다.")
    if not token:
        raise PreflightError("CI_VARIABLE_AUDIT_TOKEN이 주입되지 않았습니다.")
    query = urlencode({"filter[environment_scope]": scope})
    url = f"{validate_api_url(api_url)}/projects/{project_id}/variables/{quote(key)}?{query}"
    request = Request(url, headers={"PRIVATE-TOKEN": token, "Accept": "application/json"})
    try:
        with opener(request, timeout=5) as response:
            payload = json.loads(response.read())
    except HTTPError as error:
        raise PreflightError(f"{key} 메타데이터 조회가 HTTP {error.code}로 실패했습니다.") from error
    except (URLError, OSError, json.JSONDecodeError) as error:
        raise PreflightError(f"{key} 메타데이터 조회에 실패했습니다: {type(error).__name__}") from error
    if not isinstance(payload, dict):
        raise PreflightError(f"{key} 메타데이터 응답 형식이 올바르지 않습니다.")
    payload.pop("value", None)
    return payload


def validate_live_metadata(
    variables: list[dict[str, object]],
    environment: dict[str, str],
    opener: Callable[..., object] = urlopen,
) -> None:
    api_url = environment.get("CI_API_V4_URL", "")
    project_id = environment.get("CI_PROJECT_ID", "")
    token = environment.get("CI_VARIABLE_AUDIT_TOKEN", "")
    errors: list[str] = []
    for expected in variables:
        key = str(expected["key"])
        try:
            actual = fetch_variable_metadata(
                api_url,
                project_id,
                token,
                key,
                str(expected["environment_scope"]),
                opener,
            )
            mismatched = [
                field for field in METADATA_FIELDS if actual.get(field) != expected.get(field)
            ]
            if actual.get("key") != key:
                mismatched.append("key")
            if mismatched:
                errors.append(f"{key} 메타데이터 불일치: {', '.join(mismatched)}")
        except PreflightError as error:
            errors.append(str(error))
    if errors:
        raise PreflightError("; ".join(errors))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--scope", default="service")
    parser.add_argument("--require-values", action="store_true")
    parser.add_argument("--check-metadata", action="store_true")
    args = parser.parse_args()
    try:
        variables = scoped_variables(load_contract(args.contract), args.scope)
        if args.require_values or args.check_metadata:
            environment = dict(os.environ)
            require_protected_main(environment)
            if args.require_values:
                validate_injected_values(variables, environment)
            if args.check_metadata:
                validate_live_metadata(variables, environment)
    except PreflightError as error:
        print(f"CI variable preflight failed: {error}", file=sys.stderr)
        return 1
    print(f"CI variable preflight passed for {args.scope}: {len(variables)} variables")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
