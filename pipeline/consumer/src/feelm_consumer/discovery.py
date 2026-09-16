from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

import numpy as np

from feelm_consumer.group_artifacts import GroupBundle
from feelm_consumer.profile import ProfileInput, merge_inputs
from feelm_consumer.ranking import RankedMovie

MAX_DISCOVERY_GROUPS = 10
MAX_MOVIES_PER_GROUP = 10
MAX_VIEWED_PER_GROUP = 2
MAX_VIEWED_GROUP_SHARE = 0.20
DIRECTION_EPSILON = 1e-8
RATING_PRIOR_COUNT = 5
RATING_PRIOR_MEAN = 3.5


@dataclass(frozen=True)
class DiscoverySelection:
    candidates: list[RankedMovie]
    supplying_group_count: int
    eligible_group_count: int
    mapped_viewed_count: int
    direction_source: str | None


def select_discovery_candidates(
    bundle: GroupBundle,
    ranked_population: list[RankedMovie],
    ratings: list[ProfileInput],
    onboarding: list[ProfileInput],
    *,
    popularity_ranking: Sequence[RankedMovie],
    watched_movie_ids: set[int] | frozenset[int] = frozenset(),
    dismissed_movie_ids: set[int] | frozenset[int] = frozenset(),
) -> DiscoverySelection:
    """Select at most ten movies from each of at most ten low-history groups.

    The serving artifact intentionally exposes stable group representatives rather
    than every source movie vector.  User direction is therefore built from the
    representative of each mapped input movie's group in the same 131-D space.
    """
    direction, source = _user_direction(bundle, ratings, onboarding)
    viewed_ids = {entry.movie_id for entry in ratings} | set(watched_movie_ids)
    mapped_viewed_groups = [
        group_id for movie_id in viewed_ids if (group_id := _mapped_group(bundle, movie_id)) is not None
    ]
    viewed_counts = np.bincount(mapped_viewed_groups, minlength=bundle.group_means.shape[0])
    total_mapped_viewed = len(mapped_viewed_groups)
    if direction is None:
        return DiscoverySelection([], 0, 0, total_mapped_viewed, None)

    excluded = {entry.movie_id for entry in merge_inputs(ratings, onboarding)}
    excluded.update(watched_movie_ids)
    excluded.update(dismissed_movie_ids)
    personal_scores = {candidate.movie_id: candidate for candidate in ranked_population}
    movies_by_group: dict[int, list[RankedMovie]] = {}
    seen_popularity_ids: set[int] = set()
    for popular in popularity_ranking:
        if popular.movie_id in seen_popularity_ids:
            continue
        seen_popularity_ids.add(popular.movie_id)
        candidate = personal_scores.get(popular.movie_id)
        if candidate is None or candidate.movie_id in excluded:
            continue
        group_id = _mapped_group(bundle, candidate.movie_id)
        if group_id is None:
            continue
        movies_by_group.setdefault(group_id, []).append(candidate)

    eligible_groups = [
        group_id
        for group_id in movies_by_group
        if int(viewed_counts[group_id]) <= MAX_VIEWED_PER_GROUP
        and int(viewed_counts[group_id]) / max(1, total_mapped_viewed) <= MAX_VIEWED_GROUP_SHARE
    ]
    compatibility = bundle.group_means @ direction
    eligible_groups.sort(key=lambda group_id: (-float(compatibility[group_id]), group_id))

    selected: list[RankedMovie] = []
    supplying_groups = 0
    for group_id in eligible_groups:
        group_candidates = movies_by_group[group_id][:MAX_MOVIES_PER_GROUP]
        if not group_candidates:
            continue
        selected.extend(group_candidates)
        supplying_groups += 1
        if supplying_groups == MAX_DISCOVERY_GROUPS:
            break
    # Popularity only decides which movies enter the bounded Discovery pool.
    # The user's predictor orders that fixed pool for the final recommendation.
    selected.sort(key=lambda item: (-item.score, item.movie_id))
    return DiscoverySelection(selected, supplying_groups, len(eligible_groups), total_mapped_viewed, source)


def _user_direction(
    bundle: GroupBundle,
    ratings: list[ProfileInput],
    onboarding: list[ProfileInput],
) -> tuple[np.ndarray | None, str | None]:
    rating_direction = _rating_direction(bundle, ratings)
    if rating_direction is not None:
        return rating_direction, "RATING"

    rated_ids = {entry.movie_id for entry in ratings}
    onboarding_vectors: list[np.ndarray] = []
    onboarding_signs: list[float] = []
    for entry in onboarding:
        if entry.movie_id in rated_ids:
            continue
        group_id = _mapped_group(bundle, entry.movie_id)
        if group_id is None:
            continue
        onboarding_vectors.append(bundle.group_means[group_id])
        onboarding_signs.append(1.0 if entry.score >= RATING_PRIOR_MEAN else -1.0)
    if not onboarding_vectors:
        return None, None
    raw = np.sum(np.asarray(onboarding_vectors) * np.asarray(onboarding_signs)[:, None], axis=0)
    return _normalized_direction(raw), "ONBOARDING"


def _rating_direction(bundle: GroupBundle, ratings: list[ProfileInput]) -> np.ndarray | None:
    if not ratings:
        return None
    rating_mean = (sum(entry.score for entry in ratings) + RATING_PRIOR_COUNT * RATING_PRIOR_MEAN) / (
        len(ratings) + RATING_PRIOR_COUNT
    )
    anchor = 3.0 + 0.5 * (rating_mean - 3.0)
    weighted: list[np.ndarray] = []
    weights: list[float] = []
    for entry in ratings:
        group_id = _mapped_group(bundle, entry.movie_id)
        if group_id is None:
            continue
        weight = entry.score - anchor
        weighted.append(bundle.group_means[group_id] * weight)
        weights.append(abs(weight))
    if not weighted:
        return None
    raw = np.sum(weighted, axis=0) / (sum(weights) + RATING_PRIOR_COUNT)
    return _normalized_direction(raw)


def _normalized_direction(raw: np.ndarray) -> np.ndarray | None:
    norm = float(np.linalg.norm(raw))
    if not np.isfinite(norm) or norm <= DIRECTION_EPSILON:
        return None
    return np.asarray(raw, dtype=np.float64) / norm


def _mapped_group(bundle: GroupBundle, movie_id: int) -> int | None:
    assignment = bundle.assignment_for(movie_id)
    if assignment is None or not assignment.group_mapped:
        return None
    return assignment.group_id
