"""Same raw GBT prediction for personal full catalog and party subsets."""

from dataclasses import dataclass

import numpy as np

from .errors import GbtError
from .features import Features
from .inputs import id_set, merge_inputs


@dataclass(frozen=True)
class Result:
    status: str
    model_version: str
    serving_revision: str
    input_count: int
    actual_count: int
    dismiss_count: int
    movie_ids: np.ndarray
    scores: np.ndarray

    def ranked_ids(self, limit=None):
        return self.movie_ids[np.lexsort((self.movie_ids, -self.scores))][:limit]


class Scorer:
    def __init__(self, bundle):
        self.bundle = bundle
        self.features = Features(bundle.table)
        self.eligible = bundle.table["eligible_movie"].to_numpy().copy()
        self.eligible.flags.writeable = False

    def score(self, ratings, *, dismissed_movie_ids=(), excluded_movie_ids=(), candidate_ids=None, chunk_size=5000):
        history = merge_inputs(ratings, dismissed_movie_ids, excluded_movie_ids)
        if type(chunk_size) is not int or not 1 <= chunk_size <= 10000:
            raise GbtError("INVALID_CHUNK_SIZE")
        unknown = sum(mid not in self.features.positions for mid, _ in history.ratings)
        if unknown:
            raise GbtError("UNSUPPORTED_HISTORY", input_count=len(history.ratings), unsupported_count=unknown)
        if candidate_ids is None:
            positions = np.flatnonzero(self.eligible)
        else:
            candidates = id_set(candidate_ids)
            unknown = sum(mid not in self.features.positions for mid in candidates)
            if unknown:
                raise GbtError("UNSUPPORTED_CANDIDATE", unsupported_count=unknown)
            positions = np.asarray(sorted(self.features.positions[mid] for mid in candidates), np.int64)
            if not self.eligible[positions].all():
                raise GbtError("INELIGIBLE_CANDIDATE")
        positions = positions[~np.isin(self.features.ids[positions], list(history.excluded))]
        actual = history.sources.count("RATING")
        if not history.ratings:
            return Result("NEEDS_COLD_START", self.bundle.model_version, self.bundle.serving_revision,
                          0, 0, 0, np.empty(0, np.int64), np.empty(0, np.float32))
        prepared = self.features.prepare(history.ratings)
        values = np.empty(len(positions), np.float32)
        for start in range(0, len(positions), chunk_size):
            stop = start + chunk_size
            matrix = prepared.matrix(positions[start:stop])
            if not np.isfinite(matrix).all():
                raise GbtError("NONFINITE_FEATURES")
            values[start:stop] = self.bundle.booster.inplace_predict(matrix)
        if not np.isfinite(values).all():
            raise GbtError("NONFINITE_PREDICTION")
        ids = self.features.ids[positions].copy()
        ids.flags.writeable = False
        values.flags.writeable = False
        return Result("OK", self.bundle.model_version, self.bundle.serving_revision, len(history.ratings),
                      actual, len(history.ratings) - actual, ids, values)
