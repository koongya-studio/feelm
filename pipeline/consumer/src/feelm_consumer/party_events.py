"""Validation for the dedicated party refresh Kafka contract."""

from __future__ import annotations

import json
import math
import re
from collections.abc import Mapping
from dataclasses import dataclass

from feelm_consumer.party_scoring import MAX_MEMBER_CANDIDATES, MAX_PARTY_MEMBERS, PartyMember


class PartyEventValidationError(ValueError):
    pass


INPUT_ID_PATTERN = re.compile(r"sha256:[0-9a-f]{64}\Z")
MODEL_VERSION_PATTERN = re.compile(r"[a-z0-9][a-z0-9._-]*\Z")


@dataclass(frozen=True)
class PartyRefresh:
    party_id: int
    input_id: str
    members: tuple[PartyMember | PartyInputReference, ...]
    schema_version: int = 1
    model_version: str | None = None
    serving_revision: str | None = None


@dataclass(frozen=True)
class PartyInputReference:
    user_id: int
    generation_id: str
    input_schema_version: str
    input_hash: str
    candidate_movie_ids: tuple[int, ...]


def parse_party_refresh(value: bytes | str, key: bytes | None) -> PartyRefresh:
    try:
        decoded = json.loads(value)
    except (json.JSONDecodeError, UnicodeDecodeError, TypeError) as exc:
        raise PartyEventValidationError("party refresh value must be JSON") from exc
    if not isinstance(decoded, Mapping):
        raise PartyEventValidationError("party refresh value must be an object")
    payload = decoded
    try:
        party_id = _positive_int(payload.get("partyId"), "partyId")
        input_id = _non_blank_string(payload.get("inputId"), "inputId")
        schema_version = payload.get("schemaVersion", 1)
        if isinstance(schema_version, bool) or not isinstance(schema_version, int):
            raise ValueError("schemaVersion must be an integer")
        raw_members = payload.get("members")
        if not isinstance(raw_members, list):
            raise ValueError("members must be a list")
        if schema_version == 1:
            members: tuple[PartyMember | PartyInputReference, ...] = tuple(
                _parse_legacy_member(member) for member in raw_members
            )
            model_version = None
            serving_revision = None
        elif schema_version == 2:
            members = tuple(_parse_input_reference(member) for member in raw_members)
            model_version = _bounded_string(payload.get("modelVersion"), "modelVersion", 50)
            if not MODEL_VERSION_PATTERN.fullmatch(model_version):
                raise ValueError("modelVersion has an invalid format")
            serving_revision = _bounded_string(
                payload.get("servingRevision"), "servingRevision", 100
            )
        else:
            raise ValueError("unsupported schemaVersion")
        if not 1 <= len(members) <= MAX_PARTY_MEMBERS:
            raise ValueError(f"party must contain between 1 and {MAX_PARTY_MEMBERS} members")
        if [member.user_id for member in members] != sorted(member.user_id for member in members):
            raise ValueError("members must be sorted by userId")
        if len({member.user_id for member in members}) != len(members):
            raise ValueError("members must have unique userId values")
        if schema_version == 1:
            dimensions = {
                len(member.vector)
                for member in members
                if isinstance(member, PartyMember) and member.vector
            }
            if len(dimensions) > 1:
                raise ValueError("non-empty member vectors must share a dimension")
        if _party_id_from_key(key) != party_id:
            raise ValueError("Kafka key does not match partyId")
        return PartyRefresh(
            party_id,
            input_id,
            members,
            schema_version,
            model_version,
            serving_revision,
        )
    except ValueError as exc:
        raise PartyEventValidationError(str(exc)) from exc


def _party_id_from_key(key: bytes | None) -> int:
    if key is None or len(key) != 8:
        raise ValueError("Kafka key must be an 8-byte signed long")
    party_id = int.from_bytes(key, byteorder="big", signed=True)
    if party_id <= 0:
        raise ValueError("Kafka key must contain a positive partyId")
    return party_id


def _parse_legacy_member(value: object) -> PartyMember:
    if not isinstance(value, Mapping):
        raise ValueError("member must be an object")
    user_id = _positive_int(value.get("userId"), "member.userId")
    vector = _number_tuple(value.get("vector"), "member.vector")
    movie_ids = _movie_ids(value.get("candidateMovieIds"))
    if any(not math.isfinite(item) for item in vector):
        raise ValueError("member.vector must contain only finite numbers")
    if len(movie_ids) > MAX_MEMBER_CANDIDATES:
        raise ValueError(f"member.candidateMovieIds exceeds {MAX_MEMBER_CANDIDATES} movies")
    if len(set(movie_ids)) != len(movie_ids):
        raise ValueError("member.candidateMovieIds contains duplicates")
    return PartyMember(user_id, vector, movie_ids)


def _parse_input_reference(value: object) -> PartyInputReference:
    if not isinstance(value, Mapping):
        raise ValueError("member must be an object")
    if "vector" in value:
        raise ValueError("schema v2 member must not contain vector")
    user_id = _positive_int(value.get("userId"), "member.userId")
    generation_id = _bounded_string(value.get("generationId"), "member.generationId", 100)
    input_schema_version = _bounded_string(
        value.get("inputSchemaVersion"), "member.inputSchemaVersion", 50
    )
    input_hash = _sha256(value.get("inputHash"), "member.inputHash")
    movie_ids = _movie_ids(value.get("candidateMovieIds"))
    if len(movie_ids) > MAX_MEMBER_CANDIDATES:
        raise ValueError(f"member.candidateMovieIds exceeds {MAX_MEMBER_CANDIDATES} movies")
    if len(set(movie_ids)) != len(movie_ids):
        raise ValueError("member.candidateMovieIds contains duplicates")
    return PartyInputReference(
        user_id, generation_id, input_schema_version, input_hash, movie_ids
    )


def _positive_int(value: object, field: str) -> int:
    if (
        isinstance(value, bool)
        or not isinstance(value, int)
        or value <= 0
        or value > 2**63 - 1
    ):
        raise ValueError(f"{field} must be a positive signed long")
    return value


def _non_blank_string(value: object, field: str) -> str:
    if not isinstance(value, str) or not INPUT_ID_PATTERN.fullmatch(value):
        raise ValueError(f"{field} must be a sha256 digest")
    return value


def _sha256(value: object, field: str) -> str:
    if not isinstance(value, str) or not INPUT_ID_PATTERN.fullmatch(value):
        raise ValueError(f"{field} must be a sha256 digest")
    return value


def _bounded_string(value: object, field: str, max_length: int) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > max_length:
        raise ValueError(f"{field} must be a non-empty string up to {max_length} characters")
    return value


def _number_tuple(value: object, field: str) -> tuple[float, ...]:
    if not isinstance(value, list):
        raise ValueError(f"{field} must be a list")
    if any(isinstance(item, bool) or not isinstance(item, (int, float)) for item in value):
        raise ValueError(f"{field} must contain numbers")
    try:
        return tuple(float(item) for item in value)
    except (OverflowError, ValueError) as exc:
        raise ValueError(f"{field} must contain finite numbers") from exc


def _movie_ids(value: object) -> tuple[int, ...]:
    if not isinstance(value, list) or not value:
        raise ValueError("member.candidateMovieIds must be a non-empty list")
    return tuple(_positive_int(item, "member.candidateMovieIds[]") for item in value)
