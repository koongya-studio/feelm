# ruff: noqa: E501

import numpy as np

from feelm_consumer.artifacts import ArtifactBundle, MovieMetadata
from feelm_consumer.profile import ProfileInput
from feelm_consumer.service import calculate_user_results


def test_candidate_top500_is_selected_after_service_publishability_filtering() -> None:
    # The first 600 Factor IDs are deliberately unmapped.  Limiting all
    # factors first would return no service candidates.
    factors = {movie_id: np.array([float(movie_id)]) for movie_id in range(1, 1102)}
    mapping = {10_000 + movie_id: movie_id for movie_id in range(601, 1102)}
    metadata = {
        service_id: MovieMetadata(str(service_id), ("DRAMA",), None)
        for service_id in mapping
    }
    bundle = ArtifactBundle("als-v1", "snapshot", 0.1, factors, mapping, metadata, "sha256:" + "a" * 64, "sha256:" + "b" * 64)

    results = calculate_user_results(
        bundle,
        [ProfileInput(10_601, 5.0, "rating")],
        [],
        1,
    )

    assert results is not None
    candidate_items = results[1]["items"]
    assert len(candidate_items) == 500
    assert candidate_items[0]["movieId"] == 11_101
    assert candidate_items[-1]["movieId"] == 10_602
    assert len(results) == 2
    assert all(
        item["type"] == "TASTE"
        and "reason" not in item
        and "rerankFeatures" not in item
        for item in candidate_items
    )
