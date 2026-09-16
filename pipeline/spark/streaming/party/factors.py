"""Map immutable ALS item factors from internal to service movie IDs."""

from __future__ import annotations

from collections.abc import Mapping, Sequence


def service_item_factors(
    candidate_movie_ids: Sequence[int],
    service_to_model_movie_ids: Mapping[int, int],
    model_item_factors: Mapping[int, Sequence[float]],
) -> tuple[dict[int, Sequence[float]], tuple[int, ...]]:
    """Return only requested service-ID factors and observable missing IDs.

    Redis candidates use the service database's movie ID, while the ALS artifact
    uses its immutable model-side ID.  The caller must build
    ``service_to_model_movie_ids`` from the mapping artifact whose revision is
    already validated against the serving model; this function never guesses a
    same-numbered ID is a valid match.
    """
    factors: dict[int, Sequence[float]] = {}
    missing: list[int] = []
    for movie_id in candidate_movie_ids:
        model_movie_id = service_to_model_movie_ids.get(movie_id)
        factor = model_item_factors.get(model_movie_id) if model_movie_id is not None else None
        if factor is None:
            missing.append(movie_id)
        else:
            factors[movie_id] = factor
    return factors, tuple(missing)
