"""Pure scoring policy tests for offline Content/ALS hybrid candidates."""

from __future__ import annotations

import math

import pytest

from batch.evaluation.content_hybrid import (
    ALS_ONLY,
    CONTENT_ONLY,
    HybridPolicy,
    percentile_scores,
    score_candidate,
)


def test_percentiles_are_computed_before_top_n_and_ties_use_midrank():
    assert percentile_scores([1.0, 1.0, 3.0]) == pytest.approx([1 / 3, 1 / 3, 5 / 6])
    assert percentile_scores([7.0]) == [0.5]
    scores = percentile_scores(list(range(501)))
    assert len(scores) == 501
    assert scores[-1] == pytest.approx(500.5 / 501)


@pytest.mark.parametrize(
    ("alpha", "expected"), [(0.25, 0.35), (0.5, 0.5), (0.75, 0.65)]
)
def test_hybrid_mix_uses_percentiles(alpha, expected):
    result = score_candidate(0.8, 0.2, HybridPolicy(alpha))
    assert result.score == pytest.approx(expected)
    assert result.branch == "MIXED"
    assert result.effective_als_weight == alpha


def test_supported_single_component_falls_back_only_in_hybrid_policy():
    als_only = score_candidate(0.8, None, ALS_ONLY)
    assert als_only.score == pytest.approx(0.8)
    assert score_candidate(None, 0.2, ALS_ONLY).score is None
    assert score_candidate(0.8, None, CONTENT_ONLY).score is None
    assert score_candidate(None, 0.2, HybridPolicy(0.5)).score == pytest.approx(0.2)


@pytest.mark.parametrize("bad", [math.nan, math.inf, -math.inf])
def test_nonfinite_component_score_is_an_error_not_a_fallback(bad):
    with pytest.raises(ValueError, match="finite"):
        score_candidate(bad, 0.2, HybridPolicy(0.5))
