from __future__ import annotations

import numpy as np

from feelm_consumer.discovery import select_discovery_candidates
from feelm_consumer.group_artifacts import GroupAssignment
from feelm_consumer.profile import ProfileInput
from feelm_consumer.ranking import RankedMovie


class FakeGroupBundle:
    def __init__(self, assignments: dict[int, int]) -> None:
        self.assignments = assignments
        self.group_means = np.zeros((1024, 131), dtype=np.float64)
        for group_id in set(assignments.values()):
            self.group_means[group_id, group_id % 131] = 1.0

    def assignment_for(self, movie_id: int) -> GroupAssignment | None:
        group_id = self.assignments.get(movie_id)
        if group_id is None:
            return None
        return GroupAssignment(group_id // 128, group_id % 128, group_id, True, True, True)


def test_discovery_stops_after_primary_ten_groups_when_they_fill_the_limit() -> None:
    assignments = {1: 0}
    ranked: list[RankedMovie] = []
    for group_id in range(1, 13):
        for offset in range(11):
            movie_id = 1000 + group_id * 100 + offset
            assignments[movie_id] = group_id
            ranked.append(RankedMovie(movie_id, 1.0 - group_id / 100 - offset / 10_000))
    bundle = FakeGroupBundle(assignments)
    # Make lower group IDs the most compatible with the user's positive group-0 direction.
    for group_id in range(1, 13):
        bundle.group_means[group_id, 0] = 1.0 - group_id / 100

    selection = select_discovery_candidates(
        bundle, ranked, [ProfileInput(1, 5.0, "rating")], popularity_ranking=ranked
    )

    assert selection.supplying_group_count == 10
    assert selection.eligible_group_count == 12
    assert len(selection.candidates) == 100
    selected_ids = {item.movie_id for item in selection.candidates}
    assert all(sum(movie_id // 100 == 10 + group_id for movie_id in selected_ids) == 10 for group_id in range(1, 11))
    assert all(movie_id < 2100 for movie_id in selected_ids)


def test_discovery_opens_next_affinity_groups_after_primary_groups_are_exhausted() -> None:
    assignments = {1: 0}
    ranked: list[RankedMovie] = []
    for group_id in range(1, 13):
        for offset in range(9):
            movie_id = 1000 + group_id * 100 + offset
            assignments[movie_id] = group_id
            ranked.append(RankedMovie(movie_id, 1.0 - group_id / 100 - offset / 10_000))
    bundle = FakeGroupBundle(assignments)
    for group_id in range(1, 13):
        bundle.group_means[group_id, 0] = 1.0 - group_id / 100

    selection = select_discovery_candidates(
        bundle, ranked, [ProfileInput(1, 5.0, "rating")], popularity_ranking=ranked
    )

    assert len(selection.candidates) == 100
    assert selection.supplying_group_count == 12
    selected_ids = {item.movie_id for item in selection.candidates}
    assert all(sum(movie_id // 100 == 10 + group_id for movie_id in selected_ids) == 9 for group_id in range(1, 12))
    assert sum(movie_id // 100 == 22 for movie_id in selected_ids) == 1


def test_discovery_remains_short_only_after_all_eligible_groups_are_exhausted() -> None:
    assignments = {1: 0}
    ranked: list[RankedMovie] = []
    for group_id in range(1, 12):
        for offset in range(9):
            movie_id = 1000 + group_id * 100 + offset
            assignments[movie_id] = group_id
            ranked.append(RankedMovie(movie_id, 1.0 - group_id / 100 - offset / 10_000))
    bundle = FakeGroupBundle(assignments)
    for group_id in range(1, 12):
        bundle.group_means[group_id, 0] = 1.0 - group_id / 100

    selection = select_discovery_candidates(
        bundle, ranked, [ProfileInput(1, 5.0, "rating")], popularity_ranking=ranked
    )

    assert len(selection.candidates) == 99
    assert selection.supplying_group_count == 11
    assert selection.eligible_group_count == 11


def test_discovery_deepens_primary_groups_one_popularity_rank_at_a_time() -> None:
    assignments = {1: 0}
    ranked: list[RankedMovie] = []
    for group_id in range(1, 12):
        group_size = 20 if group_id <= 5 or group_id == 11 else 9
        for offset in range(group_size):
            movie_id = 1000 + group_id * 100 + offset
            assignments[movie_id] = group_id
            ranked.append(RankedMovie(movie_id, 1.0 - group_id / 100 - offset / 10_000))
    bundle = FakeGroupBundle(assignments)
    for group_id in range(1, 12):
        bundle.group_means[group_id, 0] = 1.0 - group_id / 100

    selection = select_discovery_candidates(
        bundle, ranked, [ProfileInput(1, 5.0, "rating")], popularity_ranking=ranked
    )

    assert len(selection.candidates) == 100
    assert selection.supplying_group_count == 10
    selected_ids = {item.movie_id for item in selection.candidates}
    assert all(sum(movie_id // 100 == 10 + group_id for movie_id in selected_ids) == 11 for group_id in range(1, 6))
    assert all(sum(movie_id // 100 == 10 + group_id for movie_id in selected_ids) == 9 for group_id in range(6, 11))
    assert all(movie_id < 2100 for movie_id in selected_ids)
    assert max(reason.popularity_rank_in_group for reason in selection.reasons.values()) == 11


def test_discovery_caps_mid_layer_by_group_affinity_order() -> None:
    assignments = {1: 0}
    ranked: list[RankedMovie] = []
    for group_id in range(1, 12):
        group_size = 30 if group_id <= 4 or group_id == 11 else 9
        for offset in range(group_size):
            movie_id = 1000 + group_id * 100 + offset
            assignments[movie_id] = group_id
            ranked.append(RankedMovie(movie_id, 1.0 - group_id / 100 - offset / 10_000))
    bundle = FakeGroupBundle(assignments)
    for group_id in range(1, 12):
        bundle.group_means[group_id, 0] = 1.0 - group_id / 100

    selection = select_discovery_candidates(
        bundle, ranked, [ProfileInput(1, 5.0, "rating")], popularity_ranking=ranked
    )

    selected_ids = {item.movie_id for item in selection.candidates}
    assert len(selected_ids) == 100
    assert all(sum(movie_id // 100 == 10 + group_id for movie_id in selected_ids) == 12 for group_id in (1, 2))
    assert all(sum(movie_id // 100 == 10 + group_id for movie_id in selected_ids) == 11 for group_id in (3, 4))
    assert all(sum(movie_id // 100 == 10 + group_id for movie_id in selected_ids) == 9 for group_id in range(5, 11))
    assert all(movie_id < 2100 for movie_id in selected_ids)


def test_discovery_group_experience_uses_confirmed_viewing_boundary() -> None:
    assignments = {movie_id: group_id for movie_id, group_id in zip(range(1, 11), range(10), strict=True)}
    assignments.update({101: 20, 102: 20, 201: 21, 202: 21, 203: 21, 301: 20, 302: 21})
    bundle = FakeGroupBundle(assignments)
    ranked = [RankedMovie(301, 0.9), RankedMovie(302, 0.8)]
    ratings = [ProfileInput(movie_id, 5.0, "rating") for movie_id in range(1, 11)]
    watched = {101, 102, 201, 202, 203}

    selection = select_discovery_candidates(
        bundle, ranked, ratings, popularity_ranking=ranked, watched_movie_ids=watched
    )

    # total mapped viewed=15: group 20 has 2/15 and is eligible; group 21 has 3 and is not.
    assert selection.mapped_viewed_count == 15
    assert [item.movie_id for item in selection.candidates] == [301]


def test_discovery_group_share_accepts_exactly_twenty_percent_only() -> None:
    assignments = {movie_id: group_id for movie_id, group_id in zip(range(1, 9), range(8), strict=True)}
    assignments.update({21: 20, 22: 20, 30: 20})
    bundle = FakeGroupBundle(assignments)
    ranked = [RankedMovie(30, 1.0)]
    ratings = [ProfileInput(movie_id, 5.0, "rating") for movie_id in range(1, 9)]

    at_boundary = select_discovery_candidates(
        bundle, ranked, ratings, popularity_ranking=ranked, watched_movie_ids={21, 22}
    )
    above_boundary = select_discovery_candidates(
        bundle, ranked, ratings[:-1], popularity_ranking=ranked, watched_movie_ids={21, 22}
    )

    assert at_boundary.mapped_viewed_count == 10
    assert [item.movie_id for item in at_boundary.candidates] == [30]
    assert above_boundary.mapped_viewed_count == 9
    assert above_boundary.candidates == []


def test_discovery_excludes_profile_watched_and_dismissed_movies() -> None:
    assignments = {1: 0, 2: 1, 3: 1, 4: 1, 5: 1, **{10 + group: 10 + group for group in range(10)}}
    bundle = FakeGroupBundle(assignments)
    bundle.group_means[1, 0] = 1.0
    ranked = [RankedMovie(movie_id, 1.0 - movie_id / 10) for movie_id in (2, 3, 4, 5)]

    selection = select_discovery_candidates(
        bundle,
        ranked,
        [ProfileInput(1, 5.0, "rating"), ProfileInput(2, 4.0, "rating")],
        popularity_ranking=ranked,
        watched_movie_ids={3, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19},
        dismissed_movie_ids={4},
    )

    assert [item.movie_id for item in selection.candidates] == [5]


def test_discovery_requires_a_rating_direction() -> None:
    assignments = {1: 0, 2: 1, 3: 2}
    bundle = FakeGroupBundle(assignments)
    bundle.group_means[1, 0] = 0.8
    bundle.group_means[2, 0] = -0.8
    ranked = [RankedMovie(2, 0.8), RankedMovie(3, 0.7)]

    without_ratings = select_discovery_candidates(bundle, ranked, [], popularity_ranking=ranked)
    with_rating = select_discovery_candidates(
        bundle,
        ranked,
        [ProfileInput(1, 4.0, "rating")],
        popularity_ranking=ranked,
    )

    assert without_ratings.direction_source is None
    assert without_ratings.candidates == []
    assert with_rating.direction_source == "RATING"


def test_discovery_reason_uses_onboarding_derived_ratings_as_rating_contributors() -> None:
    bundle = FakeGroupBundle({1: 0, 2: 1, 3: 2, 20: 3})
    bundle.group_means[[0, 1, 2, 3], :] = 0.0
    bundle.group_means[0, 0] = 0.5
    bundle.group_means[1, 0] = 0.25
    bundle.group_means[2, 0] = -0.25
    bundle.group_means[3, 0] = 1.0
    ratings = [
        ProfileInput(1, 4.0, "rating"),
        ProfileInput(2, 4.0, "rating"),
        ProfileInput(3, 2.0, "rating"),
    ]

    selection = select_discovery_candidates(
        bundle,
        [RankedMovie(20, 0.9)],
        ratings,
        popularity_ranking=[RankedMovie(20, 1.0)],
    )

    reason = selection.reasons[20]
    assert reason.direction_source == "RATING"
    assert reason.contributor_count == 3
    assert [contributor.payload() for contributor in reason.top_contributors] == [
        {"movieId": 1, "relation": "POSITIVE_ALIGNMENT"},
        {"movieId": 3, "relation": "NEGATIVE_CONTRAST"},
        {"movieId": 2, "relation": "POSITIVE_ALIGNMENT"},
    ]
    assert reason.top_contribution_coverage == 1.0


def test_discovery_reason_coverage_uses_all_positive_rating_contributors() -> None:
    assignments = {movie_id: 0 for movie_id in range(1, 11)}
    assignments[20] = 1
    bundle = FakeGroupBundle(assignments)
    bundle.group_means[1, 0] = 1.0
    ratings = [ProfileInput(movie_id, 5.0, "rating") for movie_id in range(1, 11)]

    selection = select_discovery_candidates(
        bundle,
        [RankedMovie(20, 0.9)],
        ratings,
        popularity_ranking=[RankedMovie(20, 1.0)],
    )

    reason = selection.reasons[20]
    assert reason.direction_source == "RATING"
    assert reason.contributor_count == 10
    assert [contributor.movie_id for contributor in reason.top_contributors] == [1, 2, 3]
    assert reason.top_contribution_coverage == 0.3
    assert reason.viewed_count_in_group == 0
    assert reason.affinity_rank == 1
    assert reason.popularity_rank_in_group == 1


def test_discovery_reason_has_zero_coverage_without_positive_contributors() -> None:
    bundle = FakeGroupBundle({1: 0, 20: 1})

    selection = select_discovery_candidates(
        bundle,
        [RankedMovie(20, 0.9)],
        [ProfileInput(1, 5.0, "rating")],
        popularity_ranking=[RankedMovie(20, 1.0)],
    )

    reason = selection.reasons[20]
    assert reason.contributor_count == 0
    assert reason.top_contributors == ()
    assert reason.top_contribution_coverage == 0.0


def test_discovery_reason_marks_low_rating_opposition_as_negative_contrast() -> None:
    bundle = FakeGroupBundle({1: 0, 20: 1})
    bundle.group_means[1, :] = 0.0
    bundle.group_means[1, 0] = -1.0

    selection = select_discovery_candidates(
        bundle,
        [RankedMovie(20, 0.9)],
        [ProfileInput(1, 0.5, "rating")],
        popularity_ranking=[RankedMovie(20, 1.0)],
    )

    reason = selection.reasons[20]
    assert reason.direction_source == "RATING"
    assert reason.contributor_count == 1
    assert reason.top_contributors[0].payload() == {
        "movieId": 1,
        "relation": "NEGATIVE_CONTRAST",
    }
    assert reason.top_contribution_coverage == 1.0


def test_popularity_orders_group_pool_then_personal_score_orders_selected_candidates() -> None:
    assignments = {1: 0, **{movie_id: 1 for movie_id in range(10, 22)}}
    bundle = FakeGroupBundle(assignments)
    bundle.group_means[1, 0] = 1.0
    personal = [RankedMovie(movie_id, float(movie_id)) for movie_id in range(10, 22)]
    popularity = [RankedMovie(movie_id, float(100 - movie_id)) for movie_id in range(10, 22)]

    selection = select_discovery_candidates(
        bundle,
        personal,
        [ProfileInput(1, 5.0, "rating")],
        popularity_ranking=popularity,
    )

    # The first pass admits 10..19, then the fill pass admits 20..21 because the
    # total list is still below 100. Personal score orders the complete pool.
    assert [item.movie_id for item in selection.candidates] == list(range(21, 9, -1))
