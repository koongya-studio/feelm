from __future__ import annotations

from datetime import UTC, datetime

from feelm_consumer.artifacts import MovieMetadata
from feelm_consumer.ranking import RankedMovie


def utc_now() -> str:
    return datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def build_results(
    *,
    model_version: str,
    vector: list[float],
    rating_count: int,
    factor_backed_input_count: int,
    candidates: list[RankedMovie],
    candidate_version: int,
    movie_metadata: dict[int, MovieMetadata],
    generated_at: str | None = None,
) -> tuple[dict[str, object], dict[str, object]]:
    timestamp = generated_at or utc_now()
    vector_result = {
        "modelVersion": model_version,
        "candidateVersion": candidate_version,
        "vector": vector,
        "ratingCount": rating_count,
        "factorBackedInputCount": factor_backed_input_count,
        "updatedAt": timestamp,
    }
    candidates_result = {
        "modelVersion": model_version,
        "candidateVersion": candidate_version,
        "items": [
            {
                "movieId": item.movie_id,
                "score": item.score,
                "type": "TASTE",
                "flavorCode": movie_metadata[item.movie_id].flavor_code,
            }
            for item in candidates
        ],
        "updatedAt": timestamp,
    }
    return vector_result, candidates_result
