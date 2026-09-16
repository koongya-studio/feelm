from __future__ import annotations

from datetime import UTC, datetime
from uuid import uuid4

from feelm_consumer.artifacts import MovieMetadata
from feelm_consumer.ranking import RankedMovie

CANDIDATE_VERSION = 3


def utc_now() -> str:
    return datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def build_results(
    *,
    model_version: str,
    vector: list[float],
    rating_count: int,
    factor_backed_input_count: int,
    candidates: list[RankedMovie],
    movie_metadata: dict[int, MovieMetadata],
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
            }
            for item in candidates
        ],
        # DISCOVERY classification belongs to the dedicated discovery work.
        # Keep the serving shape stable so its producer can populate this list.
        "discovery": [],
        "updatedAt": timestamp,
    }
    return vector_result, candidates_result
