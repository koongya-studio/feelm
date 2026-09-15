from __future__ import annotations

from dataclasses import dataclass

import numpy as np


@dataclass(frozen=True)
class ProfileInput:
    movie_id: int
    score: float
    source: str


@dataclass(frozen=True)
class FoldInResult:
    vector: np.ndarray
    factor_backed_input_count: int


def merge_inputs(
    ratings: list[ProfileInput], onboarding: list[ProfileInput]
) -> list[ProfileInput]:
    """Use a real rating over an onboarding choice for the same movie."""
    merged = {entry.movie_id: entry for entry in onboarding}
    merged.update({entry.movie_id: entry for entry in ratings})
    return [merged[movie_id] for movie_id in sorted(merged)]


def fold_in(
    item_factors: dict[int, np.ndarray], inputs: list[ProfileInput], reg_param: float
) -> FoldInResult | None:
    """Solve (Y.T @ Y + reg_param * m * I) p = Y.T @ r in float64."""
    supported = [(item_factors[entry.movie_id], entry.score) for entry in inputs if entry.movie_id in item_factors]
    if not supported:
        return None
    matrix = np.asarray([factor for factor, _ in supported], dtype=np.float64)
    scores = np.asarray([score for _, score in supported], dtype=np.float64)
    dimension = matrix.shape[1]
    lhs = matrix.T @ matrix + (reg_param * len(supported) * np.eye(dimension))
    rhs = matrix.T @ scores
    try:
        vector = np.linalg.solve(lhs, rhs)
    except np.linalg.LinAlgError:
        return None
    if not np.isfinite(vector).all():
        return None
    return FoldInResult(vector=vector, factor_backed_input_count=len(supported))
