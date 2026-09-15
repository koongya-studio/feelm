import numpy as np

from feelm_consumer.ranking import rank_candidates


def test_rank_candidates_keeps_known_items_and_breaks_ties_by_movie_id() -> None:
    ranked = rank_candidates(
        np.array([1.0]),
        {9: np.array([1.0]), 3: np.array([1.0]), 4: np.array([-1.0])},
        size=500,
    )

    assert [(item.movie_id, item.score) for item in ranked] == [(3, 1.0), (9, 1.0), (4, -1.0)]
