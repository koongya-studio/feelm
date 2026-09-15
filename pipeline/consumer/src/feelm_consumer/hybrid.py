"""S15P21E106-315 hybrid scoring rules, ported to the Consumer runtime.

The Spark project owns the same rules in ``batch/evaluation/content_hybrid.py`` and
``batch/content/features.py``.  Consumer and Spark are independent runtimes that do
not import each other, so the rules live twice and parity tests keep them equal.

Three rules matter:

1. a rating-weighted content profile built from supported history only,
2. tie-aware percentiles per component over that component's supported population,
3. a fixed ALS weight mix that falls back to whichever component exists.
"""

from __future__ import annotations

import math
from collections.abc import Sequence
from dataclasses import dataclass

import numpy as np

from feelm_consumer.content import PRIOR_BINS, ContentBundle

PRIOR_STRENGTH = 5
NO_SUPPORTED_HISTORY = "NO_SUPPORTED_HISTORY"
NO_DISCRIMINATION = "NO_DISCRIMINATION"
READY = "READY"


@dataclass(frozen=True)
class CandidateScore:
    score: float
    branch: str


def rating_bin(rating: float) -> int:
    """Map a half-star rating in [0.5, 5.0] onto its zero-based ten-bin index.

    Ratings are half-star by contract (``docs/api/README.md``).  A value between
    steps would silently land in the lower bin and drift away from the Spark
    implementation, so it is rejected instead.
    """
    if not math.isfinite(rating) or not 0.5 <= rating <= 5.0:
        raise ValueError("rating must be between 0.5 and 5.0")
    doubled = rating * 2
    if doubled != int(doubled):
        raise ValueError("rating must be a half-star value")
    return int(doubled) - 1


def build_profile(
    content: ContentBundle, rows: Sequence[int], ratings: Sequence[float]
) -> tuple[np.ndarray | None, str]:
    """Accumulate the weighted content profile of one user's supported history.

    ``rows`` are content matrix rows of rated movies that have a non-empty vector.
    The weight of a rating is ``2q - 1`` where ``q`` is its smoothed midrank inside
    the user's own rating distribution, so a rating only pulls the profile towards
    a movie when it is high *for that user*.

    Returns ``(None, reason)`` when no profile can be built: no supported history,
    no discrimination between the ratings, or no positive weight at all.
    """
    if len(rows) != len(ratings):
        raise ValueError("rows and ratings must align")
    count = len(rows)
    if not count:
        return None, NO_SUPPORTED_HISTORY
    observed = np.asarray([rating_bin(rating) for rating in ratings], dtype=np.int64)
    histogram = np.bincount(observed, minlength=PRIOR_BINS)
    lower = np.cumsum(histogram) - histogram
    q = (lower[observed] + 0.5 * histogram[observed] + PRIOR_STRENGTH * content.prior_midrank[observed]) / (
        count + PRIOR_STRENGTH
    )
    weights = 2 * q - 1
    denominator = float(np.abs(weights).sum())
    if denominator == 0 or not math.isfinite(denominator):
        return None, NO_DISCRIMINATION
    profile = np.zeros(content.vocabulary_size, dtype=np.float64)
    for row, weight in zip(rows, weights.tolist(), strict=True):
        columns, values = content.nonzeros(row)
        profile[columns] += weight * values
    profile /= denominator
    return profile, READY


def percentile_scores(scores: Sequence[float]) -> list[float]:
    """Return tie-aware percentiles over the full supported candidate population."""
    if not scores:
        return []
    if any(not math.isfinite(score) for score in scores):
        raise ValueError("component scores must be finite")
    ordered = sorted(enumerate(scores), key=lambda pair: pair[1])
    result = [0.0] * len(scores)
    start = 0
    while start < len(ordered):
        end = start + 1
        while end < len(ordered) and ordered[end][1] == ordered[start][1]:
            end += 1
        percentile = (start + 0.5 * (end - start)) / len(ordered)
        for index, _ in ordered[start:end]:
            result[index] = percentile
        start = end
    return result


def score_candidate(
    als_percentile: float | None, content_percentile: float | None, alpha: float
) -> CandidateScore | None:
    """Mix both components, or fall back to the only one that supports the movie."""
    if not math.isfinite(alpha) or not 0 <= alpha <= 1:
        raise ValueError("hybrid alpha must be finite and between zero and one")
    for value in (als_percentile, content_percentile):
        if value is not None and (not math.isfinite(value) or not 0 <= value <= 1):
            raise ValueError("component percentile must be a finite value in [0, 1]")
    if als_percentile is not None and content_percentile is not None:
        return CandidateScore(alpha * als_percentile + (1 - alpha) * content_percentile, "MIXED")
    if als_percentile is not None:
        return CandidateScore(als_percentile, "ALS_ONLY")
    if content_percentile is not None:
        return CandidateScore(content_percentile, "CONTENT_ONLY")
    return None
