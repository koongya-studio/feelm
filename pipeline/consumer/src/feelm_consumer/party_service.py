from __future__ import annotations

from collections.abc import Sequence
from datetime import UTC, datetime

from feelm_consumer.artifacts import ArtifactBundle
from feelm_consumer.party_events import PartyRefresh
from feelm_consumer.party_scoring import PartyCandidate, PartyScoringResult, score_party_candidates

ALGORITHM_VERSION = "party-mean-fit-v2"


def calculate_party_result(
    bundle: ArtifactBundle,
    refresh: PartyRefresh,
    *,
    generated_at: str | None = None,
) -> dict[str, object]:
    if refresh.schema_version != 1:
        raise ValueError("party refresh schema v2 scoring is implemented by S15P21E106-747")
    movie_ids = sorted({movie_id for member in refresh.members for movie_id in member.candidate_movie_ids})
    factors: dict[int, Sequence[float]] = {}
    missing: list[int] = []
    for movie_id in movie_ids:
        model_movie_id = bundle.service_to_movielens.get(movie_id)
        factor = bundle.factors.get(model_movie_id) if model_movie_id is not None else None
        if factor is None:
            missing.append(movie_id)
        else:
            factors[movie_id] = factor
    scored = score_party_candidates(refresh.members, factors)
    result = PartyScoringResult(scored.candidates, tuple(missing))
    return {
        "partyId": refresh.party_id,
        "modelVersion": bundle.model_version,
        "algorithmVersion": ALGORITHM_VERSION,
        "inputId": refresh.input_id,
        "generatedAt": generated_at or _utc_now(),
        "candidates": [_candidate_payload(candidate) for candidate in result.candidates],
        "fallback": {
            "missingItemFactorMovieIds": list(result.missing_factor_movie_ids),
            "emptyVectorUserIds": [member.user_id for member in refresh.members if not member.vector],
        },
    }


def _candidate_payload(candidate: PartyCandidate) -> dict[str, object]:
    return {
        "movieId": candidate.movie_id,
        "partyScore": candidate.party_score,
        "meanFit": candidate.mean_fit,
        "minFit": candidate.min_fit,
        "maxFit": candidate.max_fit,
        "stddevFit": candidate.stddev_fit,
        "memberFit": [{"userId": user_id, "score": score} for user_id, score in candidate.member_fit],
    }


def _utc_now() -> str:
    return datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")
