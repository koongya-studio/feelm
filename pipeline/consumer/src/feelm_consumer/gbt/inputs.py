"""All-history input contract, independent of DB/Kafka and mode-specific exclusions."""

import math
from dataclasses import dataclass
from numbers import Integral, Real

from .errors import GbtError


def movie_id(value):
    if isinstance(value, bool) or not isinstance(value, Integral) or not 0 < value < 2**63:
        raise GbtError("INVALID_MOVIE_ID")
    return int(value)


def id_set(values):
    return frozenset(movie_id(value) for value in values)


@dataclass(frozen=True)
class History:
    ratings: tuple[tuple[int, float], ...]
    sources: tuple[str, ...]
    excluded: frozenset[int]


def merge_inputs(ratings, dismissed_movie_ids=(), excluded_movie_ids=()):
    actual = {}
    for entry in ratings:
        if not isinstance(entry, (tuple, list)) or len(entry) != 2:
            raise GbtError("INVALID_RATING")
        mid, score = entry
        mid = movie_id(mid)
        if mid in actual:
            raise GbtError("DUPLICATE_RATING")
        if (isinstance(score, bool) or not isinstance(score, Real) or not 0.5 <= score <= 5
                or not math.isfinite(score) or score * 2 != int(score * 2)):
            raise GbtError("INVALID_RATING")
        actual[mid] = float(score)
    dismissed = id_set(dismissed_movie_ids)
    excluded = id_set(excluded_movie_ids) | dismissed | actual.keys()
    merged = dict.fromkeys(dismissed, 1.0)
    merged.update(actual)
    ordered = tuple(sorted(merged.items()))
    return History(ordered, tuple("RATING" if mid in actual else "DISMISS" for mid, _ in ordered), excluded)
