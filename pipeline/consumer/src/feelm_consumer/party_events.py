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


@dataclass(frozen=True)
class PartyRefresh:
    party_id: int
    input_id: str
    members: tuple[PartyMember, ...]


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
        raw_members = payload.get("members")
        if not isinstance(raw_members, list):
            raise ValueError("members must be a list")
        members = tuple(_parse_member(member) for member in raw_members)
        if not 1 <= len(members) <= MAX_PARTY_MEMBERS:
            raise ValueError(f"party must contain between 1 and {MAX_PARTY_MEMBERS} members")
        if [member.user_id for member in members] != sorted(member.user_id for member in members):
            raise ValueError("members must be sorted by userId")
        if len({member.user_id for member in members}) != len(members):
            raise ValueError("members must have unique userId values")
        dimensions = {len(member.vector) for member in members if member.vector}
        if len(dimensions) > 1:
            raise ValueError("non-empty member vectors must share a dimension")
        if _party_id_from_key(key) != party_id:
            raise ValueError("Kafka key does not match partyId")
        return PartyRefresh(party_id, input_id, members)
    except ValueError as exc:
        raise PartyEventValidationError(str(exc)) from exc


def _party_id_from_key(key: bytes | None) -> int:
    if key is None or len(key) != 8:
        raise ValueError("Kafka key must be an 8-byte signed long")
    party_id = int.from_bytes(key, byteorder="big", signed=True)
    if party_id <= 0:
        raise ValueError("Kafka key must contain a positive partyId")
    return party_id


def _parse_member(value: object) -> PartyMember:
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
