#!/usr/bin/env python3
"""Validate the backend deployment environment without disclosing variable values."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "backend" / "config" / "deployment-environment.properties"
COMPOSE = ROOT / "infra" / "compose" / "service.yml"
ENTRY = re.compile(
    r"^([A-Z][A-Z0-9_]*)=([A-Z][A-Z0-9_]*|@compose-default|@dev-default)$"
)
COMPOSE_ENTRY = re.compile(r"^      ([A-Z][A-Z0-9_]*):\s*(.*)$")
PLACEHOLDER = re.compile(r"\$\{([A-Z][A-Z0-9_]*)(?::([-?])[^}]*)?}")


class ContractError(ValueError):
    pass


def load_contract(path: Path = CONTRACT) -> dict[str, str]:
    entries: dict[str, str] = {}
    for line_number, raw_line in enumerate(
        path.read_text(encoding="utf-8").splitlines(), start=1
    ):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        match = ENTRY.fullmatch(line)
        if match is None:
            raise ContractError(f"Invalid deployment contract entry at line {line_number}")
        target, source = match.group(1), match.group(2)
        if target in entries:
            raise ContractError(f"Duplicate deployment contract key: {target}")
        entries[target] = source
    if not entries:
        raise ContractError("Backend deployment contract is empty")
    return entries


def backend_environment(path: Path = COMPOSE) -> dict[str, str]:
    entries: dict[str, str] = {}
    in_backend = False
    in_environment = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if re.fullmatch(r"  [a-zA-Z0-9_-]+:", line):
            in_backend = line == "  backend:"
            in_environment = False
            continue
        if in_backend and line == "    environment:":
            in_environment = True
            continue
        if in_environment:
            match = COMPOSE_ENTRY.fullmatch(line)
            if match:
                entries[match.group(1)] = match.group(2)
            elif line and not line.startswith("      "):
                break
    if not entries:
        raise ContractError("Compose backend environment is missing")
    return entries


def validate_compose(contract: dict[str, str], compose: dict[str, str]) -> None:
    errors: list[str] = []
    for target, source in contract.items():
        expression = compose.get(target)
        if expression is None:
            if source != "@dev-default":
                errors.append(f"Compose does not inject {target}")
            continue
        if source == "@dev-default":
            errors.append(f"Compose must not inject dev-defaulted {target}")
            continue
        placeholders = {
            match.group(1): match.group(2) or "" for match in PLACEHOLDER.finditer(expression)
        }
        if not source.startswith("@"):
            if source not in placeholders:
                errors.append(f"Compose mapping for {target} must use {source}")
        elif source == "@compose-default":
            reviewed_default = re.compile(
                r'''^\s*["']?\$\{''' + re.escape(target) + r''':-[^{}]+\}["']?\s*$'''
            )
            if reviewed_default.fullmatch(expression) is None:
                errors.append(f"Compose mapping for {target} needs a reviewed default")
    if errors:
        raise ContractError("; ".join(errors))


def require_main_context(environment: dict[str, str]) -> None:
    if not (
        environment.get("CI_COMMIT_BRANCH") == "main"
        and environment.get("CI_COMMIT_REF_PROTECTED") == "true"
        and environment.get("CI_PIPELINE_SOURCE") == "push"
    ):
        raise ContractError("Runtime environment check requires a protected main push pipeline")


def missing_runtime_variables(
    contract: dict[str, str], environment: dict[str, str]
) -> list[str]:
    required = {source for source in contract.values() if not source.startswith("@")}
    return sorted(name for name in required if not environment.get(name, "").strip())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--require-values", action="store_true")
    args = parser.parse_args()
    try:
        contract = load_contract()
        validate_compose(contract, backend_environment())
        if args.require_values:
            require_main_context(dict(os.environ))
            missing = missing_runtime_variables(contract, dict(os.environ))
            if missing:
                print("Missing required backend deployment variables: " + ", ".join(missing),
                      file=sys.stderr)
                return 1
    except (OSError, ContractError) as error:
        print(f"Backend environment contract check failed: {error}", file=sys.stderr)
        return 2
    print("Backend environment contract check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
