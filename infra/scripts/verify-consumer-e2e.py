#!/usr/bin/env python3
"""Verify one Consumer publication without printing Redis credentials or payloads."""

from __future__ import annotations

import argparse
import json
import math
import socket
import ssl
import sys
import time
from pathlib import Path
from typing import BinaryIO
from urllib.parse import unquote, urlparse
from uuid import UUID


class VerificationError(RuntimeError):
    pass


class NotReady(VerificationError):
    pass


def env_value(path: Path, name: str) -> str:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise VerificationError("Consumer environment file is unavailable") from exc
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip() == name:
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            if not value:
                break
            return value
    raise VerificationError(f"{name} is required in the Consumer environment file")


def redis_connection(url: str) -> tuple[str, int, str | None, str | None, int, bool]:
    parsed = urlparse(url)
    if parsed.scheme not in {"redis", "rediss"} or parsed.hostname is None:
        raise VerificationError("CONSUMER_REDIS_URL must be a redis:// or rediss:// URL")
    try:
        port = parsed.port or 6379
        database = int(parsed.path.removeprefix("/") or "0")
    except ValueError as exc:
        raise VerificationError("CONSUMER_REDIS_URL has an invalid port or database number") from exc
    if database < 0:
        raise VerificationError("CONSUMER_REDIS_URL has an invalid database number")
    return (
        parsed.hostname,
        port,
        unquote(parsed.username) if parsed.username else None,
        unquote(parsed.password) if parsed.password else None,
        database,
        parsed.scheme == "rediss",
    )


def encode_command(*parts: str) -> bytes:
    encoded = [part.encode("utf-8") for part in parts]
    chunks = [f"*{len(encoded)}\r\n".encode()]
    for part in encoded:
        chunks.extend((f"${len(part)}\r\n".encode(), part, b"\r\n"))
    return b"".join(chunks)


def read_response(stream: BinaryIO):
    marker = stream.read(1)
    if not marker:
        raise VerificationError("Redis closed the verification connection")
    line = stream.readline()
    if not line.endswith(b"\r\n"):
        raise VerificationError("Redis returned a malformed response")
    value = line[:-2]
    if marker == b"+":
        return value.decode("utf-8")
    if marker == b"-":
        raise VerificationError("Redis rejected the verification command")
    if marker == b":":
        return int(value)
    if marker == b"$":
        length = int(value)
        if length == -1:
            return None
        payload = stream.read(length)
        if len(payload) != length or stream.read(2) != b"\r\n":
            raise VerificationError("Redis returned a truncated response")
        return payload.decode("utf-8")
    if marker == b"*":
        length = int(value)
        if length == -1:
            return None
        return [read_response(stream) for _ in range(length)]
    raise VerificationError("Redis returned an unsupported response")


def redis_snapshot(url: str, user_id: int) -> list[str | None]:
    host, port, username, password, database, use_tls = redis_connection(url)
    keys = [
        f"feelm:user:{user_id}:candidate-version",
        f"feelm:user:{user_id}:vector",
        f"feelm:user:{user_id}:candidates",
    ]
    try:
        raw_socket = socket.create_connection((host, port), timeout=5)
        connection = (
            ssl.create_default_context().wrap_socket(raw_socket, server_hostname=host)
            if use_tls
            else raw_socket
        )
        with connection, connection.makefile("rwb", buffering=0) as stream:
            if password is not None:
                auth = ("AUTH", username, password) if username is not None else ("AUTH", password)
                stream.write(encode_command(*auth))
                if read_response(stream) != "OK":
                    raise VerificationError("Redis authentication failed")
            if database:
                stream.write(encode_command("SELECT", str(database)))
                if read_response(stream) != "OK":
                    raise VerificationError("Redis database selection failed")
            stream.write(encode_command("MGET", *keys))
            response = read_response(stream)
    except VerificationError:
        raise
    except (OSError, ssl.SSLError, UnicodeError, ValueError) as exc:
        raise VerificationError("Redis verification connection failed") from exc
    if not isinstance(response, list) or len(response) != 3:
        raise VerificationError("Redis returned an invalid Consumer snapshot")
    return response


def validate_snapshot(values: list[str | None], after_version: int | None) -> dict[str, int]:
    version_text, vector_text, candidates_text = values
    if version_text is None or vector_text is None or candidates_text is None:
        raise NotReady("Consumer result keys are not all published yet")
    try:
        version = int(version_text)
    except ValueError as exc:
        raise VerificationError("candidate-version is not an integer") from exc
    if version < 1:
        raise VerificationError("candidate-version must be positive")
    if after_version is not None and version <= after_version:
        raise NotReady("Consumer has not published a newer candidate-version yet")
    try:
        vector = json.loads(vector_text)
        candidates = json.loads(candidates_text)
    except json.JSONDecodeError as exc:
        raise VerificationError("Consumer result JSON is invalid") from exc
    if not isinstance(vector, dict) or not isinstance(candidates, dict):
        raise VerificationError("Consumer result JSON must be an object")
    generations = (vector.get("generationId"), candidates.get("generationId"))
    if not isinstance(generations[0], str) or not generations[0] or generations[0] != generations[1]:
        raise VerificationError("vector and candidates generationId values differ")
    try:
        UUID(generations[0])
    except ValueError as exc:
        raise VerificationError("generationId is not a UUID") from exc
    versions = (vector.get("candidateVersion"), candidates.get("candidateVersion"))
    if versions != (4, 4) or any(type(item) is not int for item in versions):
        raise VerificationError("vector and candidates must use candidateVersion 4")
    if (
        not isinstance(vector.get("modelVersion"), str)
        or not vector.get("modelVersion")
        or vector.get("modelVersion") != candidates.get("modelVersion")
        or not isinstance(vector.get("updatedAt"), str)
        or not vector.get("updatedAt")
        or vector.get("updatedAt") != candidates.get("updatedAt")
    ):
        raise VerificationError("vector and candidates metadata values differ")
    vector_values = vector.get("vector")
    if not isinstance(vector_values, list) or not vector_values or not all(
        _finite_number(item) for item in vector_values
    ):
        raise VerificationError("vector contains invalid values")
    for count_name in ("ratingCount", "factorBackedInputCount"):
        count = vector.get(count_name)
        if type(count) is not int or count < 0:
            raise VerificationError(f"vector {count_name} is invalid")
    taste = candidates.get("taste")
    discovery = candidates.get("discovery")
    if not isinstance(taste, list) or not isinstance(discovery, list):
        raise VerificationError("candidates must contain taste and discovery arrays")
    if len(taste) > 500 or len(discovery) > 100:
        raise VerificationError("candidate list exceeds its contract limit")
    _validate_candidate_list(taste, discovery=False)
    _validate_candidate_list(discovery, discovery=True)
    return {"version": version, "taste": len(taste), "discovery": len(discovery)}


def _finite_number(value: object) -> bool:
    return type(value) in {int, float} and math.isfinite(value)


def _validate_candidate_list(items: list[object], *, discovery: bool) -> None:
    movie_ids: set[int] = set()
    ordering: list[tuple[float, int]] = []
    for item in items:
        if not isinstance(item, dict):
            raise VerificationError("candidate item must be an object")
        movie_id = item.get("movieId")
        if type(movie_id) is not int or movie_id <= 0 or movie_id in movie_ids:
            raise VerificationError("candidate movieId is invalid or duplicated")
        movie_ids.add(movie_id)
        if not _finite_number(item.get("score")):
            raise VerificationError("candidate score is invalid")
        ordering.append((-float(item["score"]), movie_id))
        if discovery:
            flavor_code = item.get("flavorCode")
            if flavor_code is not None and (not isinstance(flavor_code, str) or not flavor_code):
                raise VerificationError("discovery flavorCode is invalid")
            continue
        reason = item.get("reason")
        if not isinstance(reason, dict) or reason.get("code") not in {
            "SHARED_GENRE",
            "TOP_RATED_SOURCE",
        }:
            raise VerificationError("taste reason is invalid")
        source_ids = reason.get("sourceMovieIds")
        genre_codes = reason.get("matchedGenreCodes")
        if (
            not isinstance(source_ids, list)
            or not source_ids
            or not all(type(source_id) is int and source_id > 0 for source_id in source_ids)
            or len(set(source_ids)) != len(source_ids)
            or not isinstance(genre_codes, list)
            or not all(isinstance(code, str) and code for code in genre_codes)
        ):
            raise VerificationError("taste reason fields are invalid")
        if reason["code"] == "SHARED_GENRE" and not genre_codes:
            raise VerificationError("SHARED_GENRE requires matched genre codes")
        if reason["code"] == "TOP_RATED_SOURCE" and genre_codes:
            raise VerificationError("TOP_RATED_SOURCE must not contain matched genre codes")
    if ordering != sorted(ordering):
        raise VerificationError("candidate list order violates score and movieId ordering")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=Path("infra/.env"))
    parser.add_argument("--user-id", type=int, required=True)
    parser.add_argument("--after-version", type=int)
    parser.add_argument("--timeout-seconds", type=float, default=60)
    parser.add_argument("--interval-seconds", type=float, default=2)
    args = parser.parse_args(argv)
    if args.user_id <= 0 or args.timeout_seconds < 0 or args.interval_seconds < 0:
        parser.error("user id must be positive and time values must be non-negative")
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    try:
        redis_url = env_value(args.env_file, "CONSUMER_REDIS_URL")
        deadline = time.monotonic() + args.timeout_seconds
        while True:
            try:
                result = validate_snapshot(
                    redis_snapshot(redis_url, args.user_id),
                    args.after_version,
                )
                break
            except NotReady:
                if time.monotonic() >= deadline:
                    raise VerificationError("Consumer publication was not observed before timeout")
                time.sleep(args.interval_seconds)
    except VerificationError as exc:
        print(f"Consumer Redis E2E verification failed: {exc}", file=sys.stderr)
        return 1
    print(
        "Consumer Redis E2E verification passed "
        f"version={result['version']} candidateVersion=4 "
        f"taste={result['taste']} discovery={result['discovery']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
