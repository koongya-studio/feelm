"""Research calculation references, not a production Consumer or replay runner.

Source lineage and parity fixtures: pipeline/docs/assets/provenance.json.
No candidate selection, tie-breaking, label access or service policy lives here.
"""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence

import numpy as np


def rating_index(value: float) -> int:
    """Accept only the ten stored half-star values; never round user input."""
    value = float(value)
    if not math.isfinite(value) or not 0.5 <= value <= 5.0 or value * 2 != int(value * 2):
        raise ValueError("rating must be 0.5 through 5.0 in increments of 0.5")
    return int(value * 2) - 1


def fold_in(factors, raw_ratings, reg: float):
    """Solve raw-rating ALS-WR for already ID-aligned, factor-backed inputs."""
    y = np.asarray(factors, dtype=np.float64)
    r = np.asarray(raw_ratings, dtype=np.float64)
    if y.ndim != 2 or y.shape[1] == 0 or r.shape != (len(y),):
        raise ValueError("expected input-movies by rank factors and aligned ratings")
    if not np.isfinite(y).all() or not math.isfinite(reg) or reg < 0:
        raise ValueError("finite factors and nonnegative finite regularization required")
    for value in r:
        rating_index(value)
    if len(y) == 0:
        return None
    # m excludes inputs without factors; callers must align/filter IDs together.
    return np.linalg.solve(y.T @ y + reg * len(y) * np.eye(y.shape[1]), y.T @ r)


def user_equal_prior(histograms):
    """Average per-training-user distributions, not pooled rating counts."""
    hist = np.asarray(histograms, dtype=np.float64)
    if hist.ndim != 2 or hist.shape[1] != 10 or not np.isfinite(hist).all():
        raise ValueError("finite users by ten-bin histograms required")
    if (hist < 0).any() or not (hist.sum(axis=1) > 0).any():
        raise ValueError("nonnegative counts and at least one nonempty user required")
    totals = hist.sum(axis=1)
    hist = hist[totals > 0] / totals[totals > 0, None]
    pi0 = hist.mean(axis=0)
    pi0 /= pi0.sum()
    return pi0, np.cumsum(pi0) - 0.5 * pi0


def profile_weights(raw_ratings, g0_mid):
    """REC030 PERCENTILE_MAGNITUDE, using observed input ratings only."""
    indices = np.asarray([rating_index(value) for value in raw_ratings], dtype=np.int64)
    prior = np.asarray(g0_mid, dtype=np.float64)
    if prior.shape != (10,) or not np.isfinite(prior).all():
        raise ValueError("finite ten-bin training prior required")
    if (prior < 0).any() or (prior > 1).any() or (np.diff(prior) < 0).any():
        raise ValueError("prior midranks must be nondecreasing in [0, 1]")
    hist = np.bincount(indices, minlength=10)
    below = np.cumsum(hist) - hist
    q = (below[indices] + 0.5 * hist[indices] + 5 * prior[indices]) / (len(indices) + 5)
    return 2 * q - 1


def structured_tokens(row: Mapping) -> dict[str, float]:
    """Sparse token-keyed reference to the four-group binary feature transform.

    Input JSON uses null for missing fields and arrays for ID lists. Token keys
    replace CSR column numbers; this is not a persisted production vocabulary.
    """
    if row.get("feature_eligible") is not True:
        return {}
    year, runtime = row.get("release_year"), row.get("runtime_minutes")
    context = []
    if row.get("original_language") is not None:
        context.append(f"language:{row['original_language']}")
    if year is not None:
        context.append(f"decade:{int(year) // 10 * 10}")
    if runtime is not None:
        context.append(f"runtime30:{int(runtime) // 30}")
    groups = [
        [f"genre:{int(x)}" for x in (row.get("genre_ids") or [])],
        context,
        [f"director:{int(x)}" for x in (row.get("director_ids") or [])]
        + [f"cast:{int(x)}" for x in (row.get("top5_cast_ids") or [])],
        [f"keyword:{int(x)}" for x in (row.get("keyword_ids") or [])],
    ]
    vector = {}
    for group in groups:
        tokens = set(group)
        if tokens:
            vector.update(dict.fromkeys(tokens, 0.25 / math.sqrt(len(tokens))))
    norm = math.sqrt(sum(value * value for value in vector.values()))
    return {token: value / norm for token, value in vector.items()} if norm else {}


def similarity(targets: Sequence[Mapping], inputs: Sequence[Mapping]):
    target_vectors = [structured_tokens(row) for row in targets]
    input_vectors = [structured_tokens(row) for row in inputs]
    return np.asarray([
        [sum(value * other.get(token, 0) for token, value in vector.items())
         for other in input_vectors]
        for vector in target_vectors
    ], dtype=np.float64).reshape(len(targets), len(inputs))


def weighted_similarity_scores(matrix, weights):
    matrix, vector = np.asarray(matrix, dtype=np.float64), np.asarray(weights, dtype=np.float64)
    if matrix.ndim != 2 or vector.ndim != 1 or matrix.shape[1] != len(vector):
        raise ValueError("expected target by input similarity and input weights")
    denominator = float(np.abs(vector).sum())
    if denominator <= 0 or not math.isfinite(denominator):
        return np.full(matrix.shape[0], np.nan), False
    scores = matrix @ vector / denominator
    return scores, bool(np.isfinite(scores).all() and np.unique(scores).size >= 2)


def evaluation_q(raw_rating, full_histogram):
    """Evaluator-only midrank. NEVER pass the full histogram to a scorer."""
    index = rating_index(raw_rating)
    hist = np.asarray(full_histogram, dtype=np.float64)
    if hist.shape != (10,) or not np.isfinite(hist).all() or (hist < 0).any():
        raise ValueError("nonnegative finite ten-bin label histogram required")
    if hist.sum() <= 0:
        raise ValueError("label histogram must be nonempty")
    return float((hist[:index].sum() + 0.5 * hist[index]) / hist.sum())
