"""Deterministic structured-content feature and profile calculations."""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence

import numpy as np

RATING_VALUES = tuple(index / 2 for index in range(1, 11))


def normalize_metadata_row(row: Mapping[str, object]) -> dict[str, object]:
    """Validate one normalized TMDB row without guessing missing fields."""
    required = {
        "tmdb_id",
        "genre_ids",
        "original_language",
        "release_year",
        "runtime_minutes",
        "director_ids",
        "top5_cast_ids",
        "keyword_ids",
        "feature_eligible",
    }
    missing = required - set(row)
    if missing:
        raise ValueError(f"metadata row is missing fields: {sorted(missing)}")
    language = row["original_language"]
    if language is not None:
        if not isinstance(language, str):
            raise ValueError("original_language must be a string or null")
        language = language.strip().lower() or None
    eligible = row["feature_eligible"]
    if not isinstance(eligible, bool):
        raise ValueError("feature_eligible must be boolean")
    return {
        "tmdb_id": _positive_int(row["tmdb_id"], "tmdb_id"),
        "genre_ids": _id_array(row["genre_ids"], "genre_ids"),
        "original_language": language,
        "release_year": _optional_positive_int(row["release_year"], "release_year", 9999),
        "runtime_minutes": _optional_positive_int(row["runtime_minutes"], "runtime_minutes"),
        "director_ids": _id_array(row["director_ids"], "director_ids"),
        "top5_cast_ids": _id_array(row["top5_cast_ids"], "top5_cast_ids"),
        "keyword_ids": _id_array(row["keyword_ids"], "keyword_ids"),
        "feature_eligible": eligible,
    }


def validate_metadata_collection(rows: Sequence[Mapping[str, object]]) -> list[dict[str, object]]:
    """Validate the immutable metadata snapshot before any artifact staging.

    Cast order is provenance, rather than a value that can safely be recreated
    after normalization.  Producers therefore must supply one source-order
    value per selected cast member (including the empty list for no cast).
    """
    normalized: list[dict[str, object]] = []
    tmdb_ids: set[int] = set()
    for row in rows:
        if "top5_cast_source_order" not in row:
            raise ValueError("metadata row is missing top5 cast source-order provenance")
        result = normalize_metadata_row(row)
        source_order = row["top5_cast_source_order"]
        cast = result["top5_cast_ids"]
        if not isinstance(source_order, list | tuple) or len(source_order) != len(cast):
            raise ValueError("top5 cast source-order must align with top5_cast_ids")
        if any(
            isinstance(value, bool) or not isinstance(value, int) or value < 0
            for value in source_order
        ):
            raise ValueError("top5 cast source-order must contain non-negative integers")
        if list(source_order) != sorted(source_order) or len(set(source_order)) != len(
            source_order
        ):
            raise ValueError("top5 cast source-order must be strictly increasing")
        tmdb_id = int(result["tmdb_id"])
        if tmdb_id in tmdb_ids:
            raise ValueError("metadata collection contains duplicate tmdb_id")
        tmdb_ids.add(tmdb_id)
        normalized.append(result)
    return normalized


def build_content_vector(
    value: Mapping[str, object], vocabulary: Mapping[str, int] | None = None
) -> dict[str, float] | tuple[tuple[int, ...], tuple[float, ...], int]:
    """Build the four-group token vector or encode it through a vocabulary."""
    if vocabulary is not None:
        encoded, unknown = [], 0
        for token, weight in value.items():
            index = vocabulary.get(token)
            if index is None:
                unknown += 1
            else:
                encoded.append((index, float(weight)))
        encoded.sort()
        return tuple(x[0] for x in encoded), tuple(x[1] for x in encoded), unknown
    if value.get("feature_eligible") is not True:
        return {}
    groups = [
        _tokens("genre", value["genre_ids"]),
        _context_tokens(value),
        _tokens("director", value["director_ids"]) + _tokens("cast", value["top5_cast_ids"]),
        _tokens("keyword", value["keyword_ids"]),
    ]
    vector: dict[str, float] = {}
    for group in groups:
        tokens = sorted(set(group))
        if tokens:
            vector.update({token: 0.25 / math.sqrt(len(tokens)) for token in tokens})
    norm = math.sqrt(sum(weight * weight for weight in vector.values()))
    return {token: weight / norm for token, weight in vector.items()} if norm else {}


def build_vocabulary(vectors: Sequence[Mapping[str, float]]) -> dict[str, int]:
    """Assign stable feature indexes by group and token spelling."""
    tokens = {token for vector in vectors for token in vector}
    ordered = sorted(tokens, key=lambda token: (_group_order(token), token))
    return {token: index for index, token in enumerate(ordered)}


def user_equal_prior(histograms: Sequence[Sequence[float]]) -> tuple[np.ndarray, np.ndarray]:
    """Calculate a ten-bin rating prior with equal user weights."""
    values = np.asarray(histograms, dtype=np.float64)
    if values.ndim != 2 or values.shape[1:] != (10,) or not np.isfinite(values).all():
        raise ValueError("histograms must be finite rows of ten bins")
    if (values < 0).any():
        raise ValueError("histograms must not contain negative counts")
    totals = values.sum(axis=1)
    if not np.any(totals > 0):
        raise ValueError("at least one nonempty user histogram is required")
    probability = (values[totals > 0] / totals[totals > 0, None]).mean(axis=0)
    probability /= probability.sum()
    return probability, np.cumsum(probability) - 0.5 * probability


def content_profile(
    vectors: np.ndarray,
    ratings: Sequence[float],
    supported: Sequence[bool],
    prior_midrank: Sequence[float],
) -> tuple[np.ndarray | None, int, int, str]:
    """Build an unnormalised weighted profile from supported history only."""
    matrix = np.asarray(vectors, dtype=np.float64)
    prior = np.asarray(prior_midrank, dtype=np.float64)
    if matrix.ndim != 2 or len(supported) != len(ratings):
        raise ValueError("ratings and support flags must align")
    if prior.shape != (10,) or not np.isfinite(prior).all() or np.any(np.diff(prior) < 0):
        raise ValueError("prior must be finite, ordered ten-bin midranks")
    if not np.isfinite(matrix).all():
        raise ValueError("content vectors must be finite")
    bins = np.asarray([_rating_bin(rating) for rating in ratings])
    mask = np.asarray(supported, dtype=bool)
    count, missing = int(mask.sum()), len(ratings) - int(mask.sum())
    if len(matrix) != count:
        raise ValueError("content vectors must contain exactly the supported history rows")
    if not count:
        return None, count, missing, "NO_SUPPORTED_HISTORY"
    observed = bins[mask]
    histogram = np.bincount(observed, minlength=10)
    lower = np.cumsum(histogram) - histogram
    q = (lower[observed] + 0.5 * histogram[observed] + 5 * prior[observed]) / (count + 5)
    weights = 2 * q - 1
    denominator = float(np.abs(weights).sum())
    if denominator == 0 or not math.isfinite(denominator):
        return None, count, missing, "NO_DISCRIMINATION"
    return weights @ matrix / denominator, count, missing, "READY"


def _positive_int(value: object, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ValueError(f"{label} must be a positive integer")
    return value


def _optional_positive_int(value: object, label: str, maximum: int | None = None) -> int | None:
    if value is None:
        return None
    integer = _positive_int(value, label)
    if maximum is not None and integer > maximum:
        raise ValueError(f"{label} exceeds its supported range")
    return integer


def _id_array(value: object, label: str) -> tuple[int, ...]:
    if value is None:
        return ()
    if not isinstance(value, list | tuple):
        raise ValueError(f"{label} must be an array or null")
    return tuple(sorted({_positive_int(item, label) for item in value}))


def _tokens(prefix: str, identifiers: object) -> list[str]:
    return [f"{prefix}:{identifier}" for identifier in identifiers]


def _context_tokens(row: Mapping[str, object]) -> list[str]:
    tokens = []
    if row["original_language"] is not None:
        tokens.append(f"language:{row['original_language']}")
    if row["release_year"] is not None:
        tokens.append(f"decade:{int(row['release_year']) // 10 * 10}")
    if row["runtime_minutes"] is not None:
        tokens.append(f"runtime30:{int(row['runtime_minutes']) // 30}")
    return tokens


def _group_order(token: str) -> int:
    groups = {
        "genre": 0,
        "language": 1,
        "decade": 1,
        "runtime30": 1,
        "director": 2,
        "cast": 2,
        "keyword": 3,
    }
    return groups[token.split(":", 1)[0]]


def _rating_bin(value: float) -> int:
    rating = float(value)
    if not math.isfinite(rating) or rating not in RATING_VALUES:
        raise ValueError("ratings must be 0.5 through 5.0 in half-star increments")
    return int(rating * 2) - 1
