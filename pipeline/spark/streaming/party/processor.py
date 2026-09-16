"""Process one self-contained party refresh event into a Redis snapshot."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass

from streaming.party.factors import service_item_factors
from streaming.party.redis_store import RedisStringWriter, build_payload, publish
from streaming.party.scoring import (
    MAX_PARTY_MEMBERS,
    PartyMember,
    PartyScoringResult,
    score_party_candidates,
)


@dataclass(frozen=True)
class PartyRefresh:
    """Immutable input emitted after a party/member candidate snapshot changes."""

    party_id: int
    input_id: str
    members: tuple[PartyMember, ...]


def parse_party_refresh(payload: Mapping[str, object]) -> PartyRefresh:
    """Validate the event body before a Spark task can act on it."""
    party_id = _positive_int(payload.get("partyId"), "partyId")
    input_id = _non_blank_string(payload.get("inputId"), "inputId")
    raw_members = payload.get("members")
    if not isinstance(raw_members, list):
        raise ValueError("members must be a list")
    members = tuple(_parse_member(raw_member) for raw_member in raw_members)
    if not 1 <= len(members) <= MAX_PARTY_MEMBERS:
        raise ValueError(f"party must contain between 1 and {MAX_PARTY_MEMBERS} members")
    return PartyRefresh(party_id=party_id, input_id=input_id, members=members)


def process_party_refresh(
    refresh: PartyRefresh,
    service_to_model_movie_ids: Mapping[int, int],
    model_item_factors: Mapping[int, Sequence[float]],
    redis: RedisStringWriter,
    *,
    model_version: str,
) -> dict[str, object]:
    """Recompute and atomically publish one party's raw top-500 candidates."""
    candidate_movie_ids = tuple(
        sorted({movie_id for member in refresh.members for movie_id in member.candidate_movie_ids})
    )
    factors, mapping_missing = service_item_factors(
        candidate_movie_ids, service_to_model_movie_ids, model_item_factors
    )
    scored = score_party_candidates(refresh.members, factors)
    result = PartyScoringResult(
        candidates=scored.candidates,
        missing_factor_movie_ids=tuple(sorted(set(mapping_missing))),
    )
    result_payload = build_payload(
        party_id=refresh.party_id,
        model_version=model_version,
        input_id=refresh.input_id,
        members=refresh.members,
        result=result,
    )
    publish(redis, refresh.party_id, result_payload)
    return result_payload


def _parse_member(value: object) -> PartyMember:
    if not isinstance(value, Mapping):
        raise ValueError("member must be an object")
    user_id = _positive_int(value.get("userId"), "member.userId")
    vector = _number_tuple(value.get("vector"), "member.vector")
    candidate_movie_ids = _movie_ids(value.get("candidateMovieIds"))
    return PartyMember(user_id, vector, candidate_movie_ids)


def _positive_int(value: object, field: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ValueError(f"{field} must be a positive integer")
    return value


def _non_blank_string(value: object, field: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{field} must be a non-blank string")
    return value


def _number_tuple(value: object, field: str) -> tuple[float, ...]:
    if not isinstance(value, list):
        raise ValueError(f"{field} must be a list")
    try:
        return tuple(float(item) for item in value)
    except (TypeError, ValueError) as error:
        raise ValueError(f"{field} must contain numbers") from error


def _movie_ids(value: object) -> tuple[int, ...]:
    if not isinstance(value, list) or not value:
        raise ValueError("member.candidateMovieIds must be a non-empty list")
    return tuple(_positive_int(item, "member.candidateMovieIds[]") for item in value)
