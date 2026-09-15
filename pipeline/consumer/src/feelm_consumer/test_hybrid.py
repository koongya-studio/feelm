"""Parity tests against S15P21E106-315.

Expected values are produced by the Spark implementation itself and pinned here:

    docker run --rm -v <worktree>:/workspace:ro -v <local-data>:/scripts:ro \
      apache/spark:4.1.3-scala2.13-java21-python3-ubuntu \
      bash /scripts/run-parity-expectations.sh

Regenerate them with the same command when the 315 rules change; a difference
means the two runtimes drifted apart, which is exactly what these tests guard.
"""

import numpy as np
import pytest

from feelm_consumer.content import ContentBundle
from feelm_consumer.hybrid import (
    NO_DISCRIMINATION,
    NO_SUPPORTED_HISTORY,
    READY,
    build_profile,
    percentile_scores,
    rating_bin,
    score_candidate,
)

PRIOR_MIDRANK = np.cumsum([0.1] * 10) - 0.05
VECTORS = [
    ([0, 2], [0.6, 0.8]),
    ([1, 3], [0.6, 0.8]),
    ([0, 1, 4], [0.5, 0.5, 0.7071067811865476]),
]
EXPECTED_PROFILE = [
    0.39629629629629626,
    0.08518518518518514,
    0.20740740740740737,
    -0.20740740740740748,
    0.34045882057130067,
    0.0,
]
EXPECTED_CONTENT_SCORES = [0.4037037037037037, -0.11481481481481493, 0.48148148148148145]


def _content(vectors=VECTORS, vocabulary_size: int = 6) -> ContentBundle:
    rows, columns, weights = [], [], []
    supported = np.zeros(len(vectors), dtype=bool)
    for row, (indices, values) in enumerate(vectors):
        supported[row] = bool(indices)
        rows.extend([row] * len(indices))
        columns.extend(indices)
        weights.extend(values)
    return ContentBundle(
        content_version="content-fixture-v1",
        content_revision="sha256:" + "c" * 64,
        vocabulary_size=vocabulary_size,
        prior_midrank=PRIOR_MIDRANK,
        service_movie_ids=np.arange(10, 10 * (len(vectors) + 1), 10, dtype=np.int64),
        row_of_nonzero=np.asarray(rows, dtype=np.int64),
        column_of_nonzero=np.asarray(columns, dtype=np.int64),
        weight_of_nonzero=np.asarray(weights, dtype=np.float64),
        supported=supported,
    )


def test_content_profile_matches_315() -> None:
    profile, reason = build_profile(_content(), [0, 1, 2], [4.5, 2.0, 5.0])

    assert reason == READY
    assert profile is not None
    np.testing.assert_allclose(profile, EXPECTED_PROFILE, rtol=1e-12, atol=0)


def test_content_scores_match_315() -> None:
    content = _content()
    profile, _ = build_profile(content, [0, 1, 2], [4.5, 2.0, 5.0])

    np.testing.assert_allclose(content.scores(profile), EXPECTED_CONTENT_SCORES, rtol=1e-12, atol=0)


def test_single_rating_profile_matches_315() -> None:
    """One rating leaves the profile equal to that movie's own vector."""
    profile, reason = build_profile(_content(), [0], [4.0])

    assert reason == READY
    np.testing.assert_allclose(profile, [0.6, 0.0, 0.8, 0.0, 0.0, 0.0], rtol=1e-12, atol=0)


def test_profile_without_supported_history() -> None:
    profile, reason = build_profile(_content(), [], [])

    assert profile is None and reason == NO_SUPPORTED_HISTORY


def test_profile_without_discrimination() -> None:
    """Equal ratings on every movie cancel out, so no direction can be learned."""
    flat_prior = np.full(10, 0.5)
    content = ContentBundle(
        content_version="content-fixture-v1",
        content_revision="sha256:" + "c" * 64,
        vocabulary_size=6,
        prior_midrank=flat_prior,
        service_movie_ids=np.asarray([10], dtype=np.int64),
        row_of_nonzero=np.asarray([0, 0], dtype=np.int64),
        column_of_nonzero=np.asarray([0, 2], dtype=np.int64),
        weight_of_nonzero=np.asarray([0.6, 0.8], dtype=np.float64),
        supported=np.asarray([True]),
    )

    profile, reason = build_profile(content, [0], [3.0])

    assert profile is None and reason == NO_DISCRIMINATION


REAL_PRIOR_MIDRANK = np.asarray(
    # Measured on the published bundle: the MovieLens median sits between 3.5 and 4.0.
    [0.0069, 0.0279, 0.0475, 0.0797, 0.1227, 0.2316, 0.3729, 0.5594, 0.7456, 0.8971]
)


def _real_prior_content() -> ContentBundle:
    content = _content()
    return ContentBundle(
        content_version=content.content_version,
        content_revision=content.content_revision,
        vocabulary_size=content.vocabulary_size,
        prior_midrank=REAL_PRIOR_MIDRANK,
        service_movie_ids=content.service_movie_ids,
        row_of_nonzero=content.row_of_nonzero,
        column_of_nonzero=content.column_of_nonzero,
        weight_of_nonzero=content.weight_of_nonzero,
        supported=content.supported,
    )


@pytest.mark.parametrize("rating", [0.5, 2.0, 3.0, 3.5])
def test_low_only_history_builds_a_negative_profile(rating) -> None:
    """315 rule: below-median ratings give negative weights, so the profile flips.

    The port keeps this as-is; whether such a profile is usable for serving is
    decided on the resulting scores in ``service._content_scores``.
    """
    profile, reason = build_profile(_real_prior_content(), [0, 1, 2], [rating] * 3)

    assert reason == READY
    assert profile is not None and not np.any(profile > 0)


def test_one_high_rating_keeps_the_profile() -> None:
    """A single rating above the prior median is still a usable signal."""
    profile, reason = build_profile(_real_prior_content(), [0, 1, 2], [3.5, 3.5, 4.5])

    assert reason == READY
    assert profile is not None and np.any(profile > 0)


def test_percentile_scores_are_tie_aware() -> None:
    assert percentile_scores([1.0, 2.0, 2.0, 3.0, 2.0]) == [0.1, 0.5, 0.5, 0.9, 0.5]
    assert percentile_scores([7.5]) == [0.5]
    assert percentile_scores([]) == []


def test_percentile_scores_reject_non_finite_components() -> None:
    with pytest.raises(ValueError, match="finite"):
        percentile_scores([1.0, float("nan")])


@pytest.mark.parametrize(
    ("als", "content", "expected_score", "expected_branch"),
    [
        (0.8, 0.2, 0.5, "MIXED"),
        (0.8, None, 0.8, "ALS_ONLY"),
        (None, 0.2, 0.2, "CONTENT_ONLY"),
    ],
)
def test_score_candidate_matches_315(als, content, expected_score, expected_branch) -> None:
    scored = score_candidate(als, content, 0.5)

    assert scored is not None
    assert scored.score == pytest.approx(expected_score, rel=1e-12)
    assert scored.branch == expected_branch


def test_unsupported_candidate_has_no_score() -> None:
    """315 calls this UNSUPPORTED; serving simply drops the movie from candidates."""
    assert score_candidate(None, None, 0.5) is None


@pytest.mark.parametrize("alpha", [-0.1, 1.5, float("nan")])
def test_invalid_alpha_is_rejected(alpha) -> None:
    with pytest.raises(ValueError, match="alpha"):
        score_candidate(0.5, 0.5, alpha)


@pytest.mark.parametrize(("rating", "expected"), [(0.5, 0), (3.0, 5), (5.0, 9)])
def test_rating_bin(rating, expected) -> None:
    assert rating_bin(rating) == expected


@pytest.mark.parametrize("rating", [0.0, 5.5, float("inf")])
def test_rating_bin_rejects_out_of_range(rating) -> None:
    with pytest.raises(ValueError, match="rating"):
        rating_bin(rating)


@pytest.mark.parametrize("rating", [3.7, 4.25, 0.51])
def test_rating_bin_rejects_values_between_half_stars(rating) -> None:
    """Spark would bucket these silently; ratings are half-star by contract."""
    with pytest.raises(ValueError, match="half-star"):
        rating_bin(rating)
