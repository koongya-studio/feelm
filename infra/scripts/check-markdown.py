#!/usr/bin/env python3
"""Check tracked Markdown files without network access or third-party packages."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path, PurePosixPath
import posixpath
import re
import subprocess
import sys
import urllib.parse


FENCE = re.compile(r"^[ \t]{0,3}(`{3,}|~{3,})")
CONFLICT = re.compile(r"^(?:<<<<<<<(?: .*)?|=======|>>>>>>>(?: .*)?)$")
REFERENCE = re.compile(r"^[ \t]{0,3}\[[^]\n]+\]:[ \t]*(?P<target><[^>]*>|\S+)")
HTML_TARGET = re.compile(r"\b(?:href|src)[ \t]*=[ \t]*(['\"])(?P<target>.*?)\1", re.I)
EXTERNAL_SCHEMES = {"data", "ftp", "http", "https", "irc", "mailto", "news", "tel"}


class CheckError(ValueError):
    """A deterministic Markdown validation error."""


def canonical_path(value: str) -> str:
    if not isinstance(value, str) or not value or "\\" in value:
        raise CheckError("invalid tracked path")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in ("", ".", "..") for part in path.parts):
        raise CheckError("non-canonical tracked path")
    if any(ord(character) < 32 or ord(character) == 127 for character in value):
        raise CheckError("control character in tracked path")
    return value


def tracked_paths(root: Path, manifest: Path | None) -> list[str]:
    from_git = manifest is None
    if manifest is not None:
        try:
            values = json.loads(manifest.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise CheckError("tracked-path manifest is not valid UTF-8 JSON") from exc
        if not isinstance(values, list) or any(not isinstance(value, str) for value in values):
            raise CheckError("tracked-path manifest must be a JSON string array")
    else:
        try:
            result = subprocess.run(
                ["git", "-C", str(root), "ls-files", "--cached", "--others",
                 "--exclude-standard", "-z"],
                check=True,
                capture_output=True,
                timeout=30,
            )
            values = result.stdout.decode("utf-8").rstrip("\0").split("\0")
        except (OSError, subprocess.SubprocessError, UnicodeDecodeError) as exc:
            raise CheckError("unable to read tracked paths from Git") from exc
    normalized = [canonical_path(value) for value in values if value]
    if from_git:
        return sorted(set(normalized))
    if normalized != sorted(set(normalized)):
        raise CheckError("tracked paths must be sorted and unique")
    return normalized


def markdown_destinations(line: str) -> tuple[list[tuple[int, str]], list[tuple[int, str]]]:
    """Return destinations and syntax errors from one non-fenced Markdown line."""
    destinations: list[tuple[int, str]] = []
    errors: list[tuple[int, str]] = []
    index = 0
    code_delimiter = 0
    while index < len(line):
        if line[index] == "`":
            end = index
            while end < len(line) and line[end] == "`":
                end += 1
            width = end - index
            if code_delimiter == 0:
                code_delimiter = width
            elif width == code_delimiter:
                code_delimiter = 0
            index = end
            continue
        if code_delimiter or not line.startswith("](", index):
            index += 1
            continue
        start = index + 2
        cursor = start
        depth = 0
        escaped = False
        while cursor < len(line):
            character = line[cursor]
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == "(":
                depth += 1
            elif character == ")":
                if depth == 0:
                    break
                depth -= 1
            cursor += 1
        if cursor == len(line):
            errors.append((index + 1, "unclosed inline link destination"))
            break
        raw = line[start:cursor].strip()
        if raw.startswith("<"):
            closing = raw.find(">")
            if closing < 0:
                errors.append((start + 1, "unclosed angle-bracket link destination"))
            else:
                destinations.append((start + 1, raw[1:closing]))
        else:
            match = re.match(r"(?:\\.|[^\s])+", raw)
            if match:
                destinations.append((start + 1, match.group(0)))
        index = cursor + 1
    reference = REFERENCE.match(line)
    if reference:
        target = reference.group("target")
        destinations.append((reference.start("target") + 1, target[1:-1] if target.startswith("<") else target))
    for match in HTML_TARGET.finditer(line):
        destinations.append((match.start("target") + 1, match.group("target")))
    return destinations, errors


def decode_target(target: str) -> str | None:
    target = target.replace("\\(", "(").replace("\\)", ")")
    if not target or target.startswith("#") or target.startswith("//"):
        return None
    parsed = urllib.parse.urlsplit(target)
    if parsed.scheme.casefold() in EXTERNAL_SCHEMES:
        return None
    if parsed.scheme or parsed.netloc:
        raise CheckError("unsupported link scheme")
    try:
        return urllib.parse.unquote_to_bytes(parsed.path).decode("utf-8") or None
    except UnicodeDecodeError as exc:
        raise CheckError("link path is not valid UTF-8 percent-encoding") from exc


def resolve_target(source: str, destination: str) -> str:
    if destination.startswith("/"):
        combined = destination.lstrip("/")
    else:
        combined = posixpath.join(posixpath.dirname(source), destination)
    normalized = posixpath.normpath(combined)
    if normalized in ("", "."):
        return posixpath.dirname(source)
    if normalized == ".." or normalized.startswith("../") or normalized.startswith("/"):
        raise CheckError("link escapes the repository root")
    return canonical_path(normalized)


def check(root: Path, paths: list[str]) -> list[str]:
    tracked = set(paths)
    directories = {""}
    for value in paths:
        parent = PurePosixPath(value).parent
        while str(parent) != ".":
            directories.add(parent.as_posix())
            parent = parent.parent
    problems: list[str] = []
    markdown = [value for value in paths if value.casefold().endswith(".md")]
    for relative in markdown:
        path = root.joinpath(*PurePosixPath(relative).parts)
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except (OSError, UnicodeDecodeError) as exc:
            problems.append(f"{relative}:1: UTF8 unable to read Markdown as UTF-8: {type(exc).__name__}")
            continue
        fence_character = None
        fence_width = 0
        for number, line in enumerate(lines, 1):
            fence = FENCE.match(line)
            if fence:
                marker = fence.group(1)
                if fence_character is None:
                    fence_character, fence_width = marker[0], len(marker)
                elif marker[0] == fence_character and len(marker) >= fence_width:
                    fence_character, fence_width = None, 0
                continue
            if fence_character is not None:
                continue
            if CONFLICT.fullmatch(line):
                problems.append(f"{relative}:{number}: CONFLICT unresolved merge conflict marker")
            destinations, syntax_errors = markdown_destinations(line)
            for column, message in syntax_errors:
                problems.append(f"{relative}:{number}:{column}: SYNTAX {message}")
            for column, raw_target in destinations:
                try:
                    decoded = decode_target(raw_target)
                    if decoded is None:
                        continue
                    target = resolve_target(relative, decoded)
                except CheckError as exc:
                    problems.append(f"{relative}:{number}:{column}: LINK {exc}: {raw_target}")
                    continue
                if target not in tracked and target not in directories:
                    problems.append(
                        f"{relative}:{number}:{column}: LINK missing tracked target: {raw_target}"
                    )
        if fence_character is not None:
            problems.append(f"{relative}:{len(lines) or 1}: SYNTAX unclosed fenced code block")
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--tracked-paths", type=Path)
    args = parser.parse_args(argv)
    try:
        root = args.root.resolve(strict=True)
        paths = tracked_paths(root, args.tracked_paths)
        problems = check(root, paths)
    except CheckError as exc:
        print("MARKDOWN_CHECK_ERROR: " + str(exc), file=sys.stderr)
        return 2
    for problem in problems:
        print(problem)
    if problems:
        print(f"MARKDOWN_CHECK_FAILED: {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print(f"MARKDOWN_CHECK_PASSED: {sum(path.casefold().endswith('.md') for path in paths)} file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
