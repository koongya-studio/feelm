from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

import numpy as np

from feelm_consumer.group_artifacts import GroupBundle
from feelm_consumer.profile import ProfileInput
from feelm_consumer.ranking import RankedMovie

PRIMARY_DISCOVERY_GROUPS = 10
INITIAL_MOVIES_PER_GROUP = 10
MAX_DISCOVERY_CANDIDATES = 100
MAX_VIEWED_PER_GROUP = 2
MAX_VIEWED_GROUP_SHARE = 0.20
DIRECTION_EPSILON = 1e-8
RATING_PRIOR_COUNT = 5
RATING_PRIOR_MEAN = 3.5
MAX_REASON_CONTRIBUTORS = 3


@dataclass(frozen=True)
class DirectionContributor:
    movie_id: int
    group_id: int
    weight: float


@dataclass(frozen=True)
class DiscoveryContributor:
    movie_id: int
    relation: str

    def payload(self) -> dict[str, object]:
        return {"movieId": self.movie_id, "relation": self.relation}


@dataclass(frozen=True)
class DiscoveryReason:
    """Auditable evidence from the aggregate group-selection calculation."""

    direction_source: str
    contributor_count: int
    top_contributors: tuple[DiscoveryContributor, ...]
    top_contribution_coverage: float
    viewed_count_in_group: int
    affinity_rank: int
    popularity_rank_in_group: int

    def payload(self) -> dict[str, object]:
        return {
            "code": "DISCOVERY_AGGREGATE_AFFINITY",
            "directionSource": self.direction_source,
            "contributorCount": self.contributor_count,
            "topContributors": [contributor.payload() for contributor in self.top_contributors],
            "topContributionCoverage": self.top_contribution_coverage,
            "viewedCountInGroup": self.viewed_count_in_group,
            "affinityRank": self.affinity_rank,
            "popularityRankInGroup": self.popularity_rank_in_group,
        }


@dataclass(frozen=True)
class DiscoverySelection:
    candidates: list[RankedMovie]
    reasons: dict[int, DiscoveryReason]
    supplying_group_count: int
    eligible_group_count: int
    mapped_viewed_count: int
    direction_source: str | None


def select_discovery_candidates(
    bundle: GroupBundle,
    ranked_population: list[RankedMovie],
    ratings: list[ProfileInput],
    *,
    popularity_ranking: Sequence[RankedMovie],
    watched_movie_ids: set[int] | frozenset[int] = frozenset(),
    dismissed_movie_ids: set[int] | frozenset[int] = frozenset(),
) -> DiscoverySelection:
    """Select up to 100 movies while prioritizing the ten best low-history groups.

    The serving artifact intentionally exposes stable group representatives rather
    than every source movie vector.  User direction is therefore built from the
    representative of each mapped input movie's group in the same 131-D space.
    """
    direction, direction_contributors = _rating_direction(bundle, ratings)
    source = "RATING" if direction is not None else None
    viewed_ids = {entry.movie_id for entry in ratings} | set(watched_movie_ids)
    mapped_viewed_groups = [
        group_id for movie_id in viewed_ids if (group_id := _mapped_group(bundle, movie_id)) is not None
    ]
    viewed_counts = np.bincount(mapped_viewed_groups, minlength=bundle.group_means.shape[0])
    total_mapped_viewed = len(mapped_viewed_groups)
    if direction is None:
        return DiscoverySelection([], {}, 0, 0, total_mapped_viewed, None)

    excluded = {entry.movie_id for entry in ratings}
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
    reasons: dict[int, DiscoveryReason] = {}
    supplying_group_ids: set[int] = set()

    def append_candidate(group_id: int, affinity_rank: int, popularity_index: int) -> None:
        candidate = movies_by_group[group_id][popularity_index]
        selected.append(candidate)
        reasons[candidate.movie_id] = _build_discovery_reason(
            bundle,
            group_id,
            source,
            direction_contributors,
            viewed_count_in_group=int(viewed_counts[group_id]),
            affinity_rank=affinity_rank,
            popularity_rank_in_group=popularity_index + 1,
        )
        supplying_group_ids.add(group_id)

    primary_groups = eligible_groups[:PRIMARY_DISCOVERY_GROUPS]
    for affinity_rank, group_id in enumerate(primary_groups, start=1):
        for popularity_index in range(min(INITIAL_MOVIES_PER_GROUP, len(movies_by_group[group_id]))):
            append_candidate(group_id, affinity_rank, popularity_index)

    # The first pass preserves the 10 groups x 10 movies diversity target. Sparse
    # primary groups are filled first from their deeper ranks: one 11th-ranked movie
    # per group, then one 12th-ranked movie per group, and so on.
    popularity_index = INITIAL_MOVIES_PER_GROUP
    while len(selected) < MAX_DISCOVERY_CANDIDATES:
        appended = False
        for affinity_rank, group_id in enumerate(primary_groups, start=1):
            if popularity_index >= len(movies_by_group[group_id]):
                continue
            append_candidate(group_id, affinity_rank, popularity_index)
            appended = True
            if len(selected) == MAX_DISCOVERY_CANDIDATES:
                break
        if not appended:
            break
        popularity_index += 1

    # Only after every candidate in the primary groups is exhausted, widen the
    # discovery scope one affinity-ranked group at a time. Each additional group
    # starts at its most popular movie and is exhausted before the next group is
    # opened. The pool remains below 100 only when all eligible groups are exhausted.
    for affinity_rank, group_id in enumerate(
        eligible_groups[PRIMARY_DISCOVERY_GROUPS:], start=PRIMARY_DISCOVERY_GROUPS + 1
    ):
        if len(selected) >= MAX_DISCOVERY_CANDIDATES:
            break
        for popularity_index in range(len(movies_by_group[group_id])):
            append_candidate(group_id, affinity_rank, popularity_index)
            if len(selected) >= MAX_DISCOVERY_CANDIDATES:
                break
    # Popularity only decides which movies enter the bounded Discovery pool.
    # The user's predictor orders that fixed pool for the final recommendation.
    selected.sort(key=lambda item: (-item.score, item.movie_id))
    return DiscoverySelection(
        selected,
        reasons,
        len(supplying_group_ids),
        len(eligible_groups),
        total_mapped_viewed,
        source,
    )


def _rating_direction(
    bundle: GroupBundle, ratings: list[ProfileInput]
) -> tuple[np.ndarray | None, tuple[DirectionContributor, ...]]:
    if not ratings:
        return None, ()
    rating_mean = (sum(entry.score for entry in ratings) + RATING_PRIOR_COUNT * RATING_PRIOR_MEAN) / (
        len(ratings) + RATING_PRIOR_COUNT
    )
    anchor = 3.0 + 0.5 * (rating_mean - 3.0)
    weighted: list[np.ndarray] = []
    weights: list[float] = []
    contributors: list[DirectionContributor] = []
    for entry in ratings:
        group_id = _mapped_group(bundle, entry.movie_id)
        if group_id is None:
            continue
        weight = entry.score - anchor
        weighted.append(bundle.group_means[group_id] * weight)
        weights.append(abs(weight))
        contributors.append(DirectionContributor(entry.movie_id, group_id, weight))
    if not weighted:
        return None, ()
    raw = np.sum(weighted, axis=0) / (sum(weights) + RATING_PRIOR_COUNT)
    direction = _normalized_direction(raw)
    if direction is None:
        return None, ()
    return direction, tuple(contributors)


def _build_discovery_reason(
    bundle: GroupBundle,
    candidate_group_id: int,
    direction_source: str | None,
    direction_contributors: tuple[DirectionContributor, ...],
    *,
    viewed_count_in_group: int,
    affinity_rank: int,
    popularity_rank_in_group: int,
) -> DiscoveryReason:
    if direction_source is None:
        raise ValueError("Discovery reason requires a direction source")
    candidate_group = bundle.group_means[candidate_group_id]
    positive: list[tuple[float, DirectionContributor]] = []
    for contributor in direction_contributors:
        similarity = float(candidate_group @ bundle.group_means[contributor.group_id])
        contribution = contributor.weight * similarity
        if contribution > 0.0:
            positive.append((contribution, contributor))
    positive.sort(key=lambda value: (-value[0], value[1].movie_id))
    top = positive[:MAX_REASON_CONTRIBUTORS]
    positive_total = sum(value for value, _ in positive)
    coverage = min(1.0, sum(value for value, _ in top) / positive_total) if positive_total else 0.0
    return DiscoveryReason(
        direction_source=direction_source,
        contributor_count=len(positive),
        top_contributors=tuple(
            DiscoveryContributor(
                contributor.movie_id,
                "POSITIVE_ALIGNMENT" if contributor.weight > 0 else "NEGATIVE_CONTRAST",
            )
            for _, contributor in top
        ),
        top_contribution_coverage=coverage,
        viewed_count_in_group=viewed_count_in_group,
        affinity_rank=affinity_rank,
        popularity_rank_in_group=popularity_rank_in_group,
    )


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
