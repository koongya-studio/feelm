from __future__ import annotations

from datetime import UTC, datetime
from uuid import uuid4

from feelm_consumer.discovery import DiscoveryReason
from feelm_consumer.ranking import RankedMovie
from feelm_consumer.xai import CandidateReason

CANDIDATE_VERSION = 6


def _reason_payloads(reason: DiscoveryReason | CandidateReason) -> list[dict[str, object]]:
    if (
        isinstance(reason, CandidateReason)
        and not reason.source_movie_ids
        and not reason.matched_genre_codes
    ):
        return []
    return [reason.payload()]


def utc_now() -> str:
    return datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def build_results(
    *,
    model_version: str,
    vector: list[float],
    rating_count: int,
    factor_backed_input_count: int,
    candidates: list[RankedMovie],
    discovery_candidates: list[RankedMovie] | None = None,
    reasons: dict[int, CandidateReason],
    discovery_reasons: dict[int, DiscoveryReason | CandidateReason] | None = None,
    generated_at: str | None = None,
    generation_id: str | None = None,
) -> tuple[dict[str, object], dict[str, object]]:
    timestamp = generated_at or utc_now()
    generation = generation_id or str(uuid4())
    vector_result = {
        "modelVersion": model_version,
        "candidateVersion": CANDIDATE_VERSION,
        "generationId": generation,
        "vector": vector,
        "ratingCount": rating_count,
        "factorBackedInputCount": factor_backed_input_count,
        "updatedAt": timestamp,
    }
    candidates_result = {
        "modelVersion": model_version,
        "candidateVersion": CANDIDATE_VERSION,
        "generationId": generation,
        "taste": [
            {
                "movieId": item.movie_id,
                "score": item.score,
                "reasons": _reason_payloads(reasons[item.movie_id]),
            }
            for item in candidates
        ],
        "discovery": [
            {
                "movieId": item.movie_id,
                "score": item.score,
                "reasons": _reason_payloads((discovery_reasons or {})[item.movie_id]),
            }
            for item in discovery_candidates or []
        ],
        "updatedAt": timestamp,
    }
    return vector_result, candidates_result
