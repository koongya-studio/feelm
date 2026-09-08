"""Analytic invariants and a fixture computed by the original research functions."""

import json
from pathlib import Path

import numpy as np
import pytest

from batch.evaluation.reference import (
    evaluation_q,
    fold_in,
    profile_weights,
    rating_index,
    similarity,
    structured_tokens,
    user_equal_prior,
    weighted_similarity_scores,
)

FIXTURE = Path(__file__).parents[1] / "fixtures" / "research-reference.json"


@pytest.mark.parametrize("rating", [0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4, 4.5, 5])
def test_half_stars(rating):
    assert rating_index(rating) == rating * 2 - 1


@pytest.mark.parametrize("rating", [0, 0.7, 5.5, float("nan"), float("inf")])
def test_invalid_rating(rating):
    with pytest.raises(ValueError):
        rating_index(rating)


def test_als_wr_regularizes_by_input_count_and_preserves_alignment():
    factors = np.diag([1.0, 2.0])
    ratings = [4, 2]
    np.testing.assert_allclose(fold_in(factors, ratings, 0.5), [2, 0.8])
    np.testing.assert_allclose(fold_in(factors[::-1], ratings[::-1], 0.5), [2, 0.8])
    assert fold_in(np.empty((0, 2)), [], 0.1) is None


def test_reject_invalid_factor_input():
    for factors, ratings, reg in [([[1]], [4, 5], 0.1), ([[np.nan]], [4], 0.1),
                                  ([[1]], [4], -0.1), ([[1]], [4], np.inf)]:
        with pytest.raises(ValueError):
            fold_in(factors, ratings, reg)


def test_prior_uses_equal_user_weights():
    histograms = [[100] + [0] * 9, [0] * 9 + [1]]
    pi0, prior = user_equal_prior(histograms)
    assert pi0[0] == pi0[9] == 0.5
    assert prior[0] == 0.25 and prior[9] == 0.75
    np.testing.assert_allclose(profile_weights([0.5, 5], prior), [-0.5, 0.5])


def test_missing_and_duplicate_metadata():
    row = {"feature_eligible": True, "genre_ids": [1, 1, 2]}
    assert structured_tokens(row) == structured_tokens({**row, "genre_ids": [2, 1]})
    assert structured_tokens(row) == structured_tokens({**row, "genre_ids": [1.0, 2.0]})
    assert structured_tokens({**row, "feature_eligible": False}) == {}
    assert structured_tokens({}) == {}
    np.testing.assert_allclose(similarity([row], [row]), [[1]])


def test_zero_or_constant_scores_are_inactive():
    scores, active = weighted_similarity_scores([[1], [0]], [0])
    assert not active and np.isnan(scores).all()
    assert not weighted_similarity_scores([[1], [1]], [1])[1]
    scores, active = weighted_similarity_scores([[1], [0]], [-1])
    assert active and scores.tolist() == [-1, 0]


def test_ties_use_midrank_not_a_fixed_twenty_percent_movie_count():
    assert evaluation_q(0.5, [4, 1] + [0] * 8) == 0.4


def test_original_implementation_parity():
    data = json.loads(FIXTURE.read_text(encoding="utf-8"))
    np.testing.assert_allclose(fold_in(data["factors"], data["ratings"], 0.1),
                               data["expected_fold_in"], rtol=1e-12, atol=1e-12)
    weights = profile_weights(data["ratings"], data["prior"])
    np.testing.assert_allclose(weights, data["expected_weights"], rtol=1e-12, atol=1e-12)
    sim = similarity(data["metadata"], data["metadata"][:3])
    # Original CSR is float32; token reference evaluates in float64.
    np.testing.assert_allclose(sim, data["expected_similarity"], rtol=1e-6, atol=1e-7)
    scores, active = weighted_similarity_scores(sim, weights)
    np.testing.assert_allclose(scores, data["expected_scores"], rtol=1e-6, atol=1e-7)
    assert active == data["expected_active"]
