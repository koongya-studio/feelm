from __future__ import annotations

from dataclasses import dataclass

import numpy as np


@dataclass(frozen=True)
class RankedMovie:
    movie_id: int
    score: float


def rank_candidates(vector: np.ndarray, factors: dict[int, np.ndarray], size: int = 500) -> list[RankedMovie]:
    scored = [RankedMovie(movie_id, float(vector @ factor)) for movie_id, factor in factors.items()]
    return sorted(scored, key=lambda item: (-item.score, item.movie_id))[:size]
