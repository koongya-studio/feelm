"""Redis payload construction for Spark-produced party candidate snapshots."""

from __future__ import annotations

import json
from collections.abc import Mapping, Sequence
from datetime import datetime, timezone
from typing import Protocol

from streaming.party.scoring import PartyCandidate, PartyMember, PartyScoringResult

ALGORITHM_VERSION = "party-mean-fit-v2"


class RedisStringWriter(Protocol):
    """The minimal Redis API used by the streaming adapter."""

    def set(self, name: str, value: str) -> object: ...


def party_candidates_key(party_id: int) -> str:
    if party_id <= 0:
        raise ValueError("party ID must be positive")
    return f"feelm:party:{party_id}:candidates"


def build_payload(
    *,
    party_id: int,
    model_version: str,
    input_id: str,
    members: Sequence[PartyMember],
    result: PartyScoringResult,
    generated_at: str | None = None,
) -> dict[str, object]:
    """Build a canonical Redis value without exposing vectors to Spring.

    Spring supplies an opaque input ID derived from its current party/member
    snapshot. It must compare the echoed ID with a newly-read snapshot before
    serving this result.
    """
    party_candidates_key(party_id)
    if not model_version:
        raise ValueError("model version must not be blank")
    if not input_id:
        raise ValueError("input ID must not be blank")
    return {
        "partyId": party_id,
        "modelVersion": model_version,
        "algorithmVersion": ALGORITHM_VERSION,
        "inputId": input_id,
        "generatedAt": generated_at or _utc_now(),
        "candidates": [_candidate_payload(candidate) for candidate in result.candidates],
        "fallback": {
            "missingItemFactorMovieIds": list(result.missing_factor_movie_ids),
            "emptyVectorUserIds": [member.user_id for member in members if not member.vector],
        },
    }


def publish(redis: RedisStringWriter, party_id: int, payload: Mapping[str, object]) -> None:
    """Atomically replace one complete party snapshot with Redis SET."""
    key = party_candidates_key(party_id)
    if payload.get("partyId") != party_id:
        raise ValueError("payload party ID does not match Redis key")
    encoded = json.dumps(payload, separators=(",", ":"), ensure_ascii=False, allow_nan=False)
    redis.set(key, encoded)


def _candidate_payload(candidate: PartyCandidate) -> dict[str, object]:
    return {
        "movieId": candidate.movie_id,
        "partyScore": candidate.party_score,
        "meanFit": candidate.mean_fit,
        "minFit": candidate.min_fit,
        "maxFit": candidate.max_fit,
        "stddevFit": candidate.stddev_fit,
        "memberFit": [
            {"userId": user_id, "score": score} for user_id, score in candidate.member_fit
        ],
    }


def _utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
