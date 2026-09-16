# ruff: noqa: E501


import numpy as np
import pytest

from feelm_consumer import results as results_module
from feelm_consumer import service as service_module
from feelm_consumer.artifacts import ArtifactBundle, MovieMetadata
from feelm_consumer.content import ContentBundle
from feelm_consumer.group_artifacts import GroupAssignment
from feelm_consumer.postgres import ONBOARDING_LIKE_SCORE
from feelm_consumer.profile import ProfileInput, fold_in, merge_inputs
from feelm_consumer.ranking import RankedMovie, rank_candidates
from feelm_consumer.results import build_results
from feelm_consumer.service import calculate_user_results
from feelm_consumer.xai import build_candidate_reasons

MAPPING_REVISION = "sha256:" + "a" * 64
METADATA_REVISION = "sha256:" + "b" * 64
# service 10 and 20 have ALS factors; 30 is a content-only service movie.
FACTORS = {1: np.array([1.0, 0.0]), 2: np.array([0.0, 1.0])}
MAPPING = {10: 1, 20: 2}
METADATA = {service_id: MovieMetadata(str(service_id), ("DRAMA",), None) for service_id in (10, 20, 30)}
CONTENT_VECTORS = {10: ([0, 2], [0.6, 0.8]), 20: ([1, 3], [0.6, 0.8]), 30: ([0, 1], [0.6, 0.8])}


def _bundle() -> ArtifactBundle:
    return ArtifactBundle("als-v1", "snapshot-a", 0.1, dict(FACTORS), dict(MAPPING), dict(METADATA), MAPPING_REVISION, METADATA_REVISION)


def _content(vectors=CONTENT_VECTORS) -> ContentBundle:
    rows, columns, weights = [], [], []
    service_ids = sorted(vectors)
    supported = np.zeros(len(service_ids), dtype=bool)
    for row, service_id in enumerate(service_ids):
        indices, values = vectors[service_id]
        supported[row] = bool(indices)
        rows.extend([row] * len(indices))
        columns.extend(indices)
        weights.extend(values)
    return ContentBundle(
        content_version="content-fixture-v1",
        content_revision="sha256:" + "c" * 64,
        vocabulary_size=4,
        prior_midrank=np.cumsum([0.1] * 10) - 0.05,
        service_movie_ids=np.asarray(service_ids, dtype=np.int64),
        row_of_nonzero=np.asarray(rows, dtype=np.int64),
        column_of_nonzero=np.asarray(columns, dtype=np.int64),
        weight_of_nonzero=np.asarray(weights, dtype=np.float64),
        supported=supported,
    )


def _legacy_results(bundle: ArtifactBundle, ratings, onboarding, candidate_version: int):
    """The ALS-only composition exactly as it was before the hybrid path existed."""
    inverse_mapping = {movielens_id: service_id for service_id, movielens_id in bundle.service_to_movielens.items()}
    merged_inputs = merge_inputs(ratings, onboarding)
    factor_inputs = [
        ProfileInput(bundle.service_to_movielens[item.movie_id], item.score, item.source)
        for item in merged_inputs
        if item.movie_id in bundle.service_to_movielens
    ]
    folded = fold_in(bundle.factors, factor_inputs, bundle.reg_param)
    service_factors = {
        service_id: bundle.factors[movielens_id]
        for movielens_id, service_id in inverse_mapping.items()
        if service_id in bundle.movie_metadata
    }
    return build_results(
        model_version=bundle.model_version,
        vector=folded.vector.tolist(),
        rating_count=len(ratings),
        factor_backed_input_count=folded.factor_backed_input_count,
        candidates=rank_candidates(folded.vector, service_factors),
        reasons=build_candidate_reasons(rank_candidates(folded.vector, service_factors), merged_inputs, bundle.movie_metadata),
    )


@pytest.fixture
def frozen_clock(monkeypatch):
    monkeypatch.setattr(results_module, "utc_now", lambda: "2026-09-15T00:00:00Z")


def test_als_only_preserves_legacy_relative_scores(frozen_clock) -> None:
    """Without a content bundle, publish the active model's relative score unchanged."""
    ratings = [ProfileInput(10, 5.0, "rating")]

    actual = calculate_user_results(_bundle(), ratings, [], 7)
    legacy = _legacy_results(_bundle(), ratings, [], 7)

    assert actual is not None
    assert {key: value for key, value in actual[0].items() if key != "generationId"} == {
        key: value for key, value in legacy[0].items() if key != "generationId"
    }
    assert {key: value for key, value in actual[1].items() if key != "generationId"} == {
        key: value for key, value in legacy[1].items() if key != "generationId"
    }


def test_content_only_movies_become_candidates(frozen_clock) -> None:
    ratings = [ProfileInput(10, 5.0, "rating"), ProfileInput(20, 1.0, "rating")]

    results = calculate_user_results(_bundle(), ratings, [], 7, content=_content(), hybrid_alpha=0.5)

    assert results is not None
    items = results[1]["taste"]
    published = {item["movieId"] for item in items}
    # 30 has no ALS factor at all and still gets scored through its content vector.
    assert published == {10, 20, 30}
    assert all(0.0 <= item["score"] <= 1.0 for item in items)
    ordering = [(-item["score"], item["movieId"]) for item in items]
    assert ordering == sorted(ordering)


def test_user_without_als_backed_input_still_gets_content_candidates(frozen_clock, caplog) -> None:
    """Rating only a content-only movie leaves no ALS input, but content works."""
    ratings = [ProfileInput(30, 5.0, "rating")]

    with caplog.at_level("WARNING"):
        results = calculate_user_results(_bundle(), ratings, [], 7, content=_content(), hybrid_alpha=0.5)

    assert results is not None
    assert results[0]["vector"] == []
    assert results[0]["factorBackedInputCount"] == 0
    assert {item["movieId"] for item in results[1]["taste"]} == {10, 20, 30}
    assert "foldin_no_factor_backed_input" in caplog.text


def test_user_with_als_input_but_no_supported_content_history(frozen_clock, caplog) -> None:
    """ALS carries the user when none of their rated movies has a content vector."""
    content = _content({10: ([], []), 20: ([1, 3], [0.6, 0.8]), 30: ([0, 1], [0.6, 0.8])})

    with caplog.at_level("WARNING"):
        results = calculate_user_results(
            _bundle(), [ProfileInput(10, 5.0, "rating")], [], 7, content=content, hybrid_alpha=0.5
        )

    assert results is not None
    assert "NO_SUPPORTED_HISTORY" in caplog.text
    # Without a content profile only the ALS-supported movies remain.
    assert {item["movieId"] for item in results[1]["taste"]} == {10, 20}


REAL_PRIOR_MIDRANK = np.asarray([0.0069, 0.0279, 0.0475, 0.0797, 0.1227, 0.2316, 0.3729, 0.5594, 0.7456, 0.8971])


def _real_prior_content(vectors=CONTENT_VECTORS) -> ContentBundle:
    content = _content(vectors)
    return ContentBundle(
        content_version=content.content_version,
        content_revision=content.content_revision,
        vocabulary_size=content.vocabulary_size,
        prior_midrank=REAL_PRIOR_MIDRANK,
        service_movie_ids=content.service_movie_ids,
        row_of_nonzero=content.row_of_nonzero,
        column_of_nonzero=content.column_of_nonzero,
        weight_of_nonzero=content.weight_of_nonzero,
        supported=content.supported,
    )


def test_low_only_history_still_publishes_content_candidates(frozen_clock, caplog) -> None:
    """A 3.5-and-below history scores every movie at or below zero and is still published.

    The list is not a ranking in that state — movies sharing no token with the
    profile all sit at 0.0 — but the product prefers a list to nothing.
    """
    ratings = [ProfileInput(10, 3.5, "rating"), ProfileInput(20, 2.0, "rating")]

    with caplog.at_level("WARNING"):
        results = calculate_user_results(_bundle(), ratings, [], 7, content=_real_prior_content(), hybrid_alpha=0.5)

    assert results is not None
    assert "content_scores_non_positive" in caplog.text
    # The content-only movie 30 is a candidate even though its score is not positive.
    assert {item["movieId"] for item in results[1]["taste"]} == {10, 20, 30}


def test_scores_peaking_at_exactly_zero_are_published_with_a_warning(frozen_clock, caplog) -> None:
    """The measured degenerate case peaks at exactly 0.0 and ties there.

    Rating 10 high and 20 low leaves the shared token negative, so the best
    content score belongs to movie 30 — the one sharing no token at all — at
    exactly 0.0.  The log records how many movies sit at that top score, because
    the published order among them is the service movie ID order.
    """
    vectors = {10: ([0], [1.0]), 20: ([0], [1.0]), 30: ([1], [1.0])}
    ratings = [ProfileInput(10, 5.0, "rating"), ProfileInput(20, 0.5, "rating")]

    with caplog.at_level("WARNING"):
        results = calculate_user_results(
            _bundle(), ratings, [], 7, content=_real_prior_content(vectors), hybrid_alpha=0.5
        )

    assert results is not None
    assert "content_scores_non_positive" in caplog.text and "tied_at_max=1" in caplog.text
    assert {item["movieId"] for item in results[1]["taste"]} == {10, 20, 30}


def test_low_only_history_without_als_publishes_the_content_list(frozen_clock, caplog) -> None:
    """Rating one content-only movie low still yields a list, not silence."""
    with caplog.at_level("WARNING"):
        results = calculate_user_results(
            _bundle(), [ProfileInput(30, 3.5, "rating")], [], 7, content=_real_prior_content(), hybrid_alpha=0.5
        )

    assert results is not None
    assert "content_scores_non_positive" in caplog.text
    assert results[0]["vector"] == []
    assert {item["movieId"] for item in results[1]["taste"]} == {10, 20, 30}


def test_users_below_the_minimum_input_count_are_skipped(frozen_clock, caplog) -> None:
    """Fold-in from a handful of inputs is noise; the product gates on this too."""
    ratings = [ProfileInput(10, 5.0, "rating"), ProfileInput(20, 4.5, "rating")]

    with caplog.at_level("INFO"):
        results = calculate_user_results(_bundle(), ratings, [], 7, content=_content(), min_input_count=10)

    assert results is None
    assert "below_minimum_input_count inputs=2 minimum=10" in caplog.text


def test_onboarding_preferences_count_towards_the_minimum(frozen_clock) -> None:
    ratings = [ProfileInput(10, 5.0, "rating")]
    onboarding = [ProfileInput(20, 5.0, "onboarding"), ProfileInput(30, 5.0, "onboarding")]

    assert calculate_user_results(_bundle(), ratings, onboarding, 7, content=_content(), min_input_count=3) is not None
    assert calculate_user_results(_bundle(), ratings, onboarding, 7, content=_content(), min_input_count=4) is None


def test_minimum_gate_counts_distinct_movies_after_rating_override(frozen_clock) -> None:
    rating = [ProfileInput(10, 4.5, "rating")]
    onboarding = [ProfileInput(10, 5.0, "onboarding")]
    onboarding.extend(ProfileInput(movie_id, 5.0, "onboarding") for movie_id in range(100, 108))

    assert calculate_user_results(_bundle(), rating, onboarding, 7, min_input_count=10) is None
    onboarding.append(ProfileInput(108, 5.0, "onboarding"))
    assert calculate_user_results(_bundle(), rating, onboarding, 7, min_input_count=10) is not None
    onboarding.append(ProfileInput(109, 5.0, "onboarding"))
    assert calculate_user_results(_bundle(), rating, onboarding, 7, min_input_count=10) is not None


def test_onboarding_exact_pseudo_target_reaches_als_fold_in(frozen_clock) -> None:
    bundle = ArtifactBundle(
        "als-v1",
        "snapshot-a",
        0.1,
        {1: np.array([1.0])},
        {10: 1},
        {10: MovieMetadata("10", (), None)},
        MAPPING_REVISION,
        METADATA_REVISION,
    )

    results = calculate_user_results(
        bundle,
        [],
        [ProfileInput(10, ONBOARDING_LIKE_SCORE, "onboarding")],
        7,
        min_input_count=1,
    )

    assert results is not None
    assert results[0]["vector"] == pytest.approx([ONBOARDING_LIKE_SCORE / 1.1])


def test_real_rating_overrides_onboarding_pseudo_target_in_fold_in(frozen_clock) -> None:
    bundle = ArtifactBundle(
        "als-v1",
        "snapshot-a",
        0.1,
        {1: np.array([1.0])},
        {10: 1},
        {10: MovieMetadata("10", (), None)},
        MAPPING_REVISION,
        METADATA_REVISION,
    )

    results = calculate_user_results(
        bundle,
        [ProfileInput(10, 0.5, "rating")],
        [ProfileInput(10, ONBOARDING_LIKE_SCORE, "onboarding")],
        7,
    )

    assert results is not None
    assert results[0]["vector"] == pytest.approx([0.5 / 1.1])


def test_group_enabled_service_emits_discovery_without_changing_taste(frozen_clock) -> None:
    class Groups:
        group_means = np.zeros((1024, 131), dtype=np.float64)
        group_means[0, 0] = 1.0
        group_means[1, 0] = 0.5

        @staticmethod
        def assignment_for(movie_id: int) -> GroupAssignment | None:
            group_id = {10: 0, 20: 1, 30: 1}.get(movie_id)
            if group_id is None:
                return None
            return GroupAssignment(0, group_id, group_id, True, True, True)

    legacy = calculate_user_results(_bundle(), [ProfileInput(10, 5.0, "rating")], [], 7)
    grouped = calculate_user_results(
        _bundle(),
        [ProfileInput(10, 5.0, "rating")],
        [],
        7,
        groups=Groups(),
        discovery_popularity=[RankedMovie(20, 1.0), RankedMovie(30, 0.5)],
    )

    assert legacy is not None and grouped is not None
    assert grouped[1]["taste"] == legacy[1]["taste"]
    assert [item["movieId"] for item in grouped[1]["discovery"]] == [20]
    assert "flavorCode" not in grouped[1]["discovery"][0]
    assert grouped[1]["discovery"][0]["reason"] == {
        "code": "DISCOVERY_AGGREGATE_AFFINITY",
        "directionSource": "RATING",
        "contributorCount": 1,
        "topContributors": [{"movieId": 10, "relation": "POSITIVE_ALIGNMENT"}],
        "topContributionCoverage": 1.0,
        "viewedCountInGroup": 0,
        "affinityRank": 1,
        "popularityRankInGroup": 1,
    }


def test_fold_in_failure_with_inputs_is_raised_not_skipped() -> None:
    """A singular solve is a computation failure, not a user without signal."""
    bundle = ArtifactBundle(
        "als-v1", "snapshot-a", 0.0, {1: np.array([1.0, 0.0])}, {10: 1}, dict(METADATA), MAPPING_REVISION, METADATA_REVISION
    )

    with pytest.raises(ValueError, match="fold-in failed despite factor-backed input"):
        calculate_user_results(bundle, [ProfileInput(10, 5.0, "rating")], [], 7)


def test_user_without_any_history_publishes_nothing(frozen_clock) -> None:
    assert calculate_user_results(_bundle(), [], [], 7, content=_content(), hybrid_alpha=0.5) is None


def test_als_only_mode_without_input_publishes_nothing(frozen_clock) -> None:
    assert calculate_user_results(_bundle(), [], [], 7) is None


def test_user_without_any_supported_input_publishes_nothing(frozen_clock) -> None:
    """No ALS factor and no supported content history means no candidates."""
    content = _content({10: ([0, 2], [0.6, 0.8]), 20: ([1, 3], [0.6, 0.8]), 30: ([], [])})

    assert calculate_user_results(_bundle(), [ProfileInput(30, 5.0, "rating")], [], 7, content=content) is None


def test_active_model_scores_are_not_normalized_again(monkeypatch, frozen_clock) -> None:
    """The serving contract preserves model-relative values without fixing their range."""
    ratings = [ProfileInput(10, 5.0, "rating"), ProfileInput(20, 1.0, "rating")]
    expected = [RankedMovie(10, 4.32), RankedMovie(20, 3.91), RankedMovie(30, 3.75)]
    monkeypatch.setattr(service_module, "_hybrid_candidates", lambda *_args: expected)

    results = calculate_user_results(_bundle(), ratings, [], 7, content=_content(), hybrid_alpha=0.5)

    assert results is not None
    assert [item["score"] for item in results[1]["taste"]] == [4.32, 3.91, 3.75]


def test_published_scores_keep_ties_equal(frozen_clock) -> None:
    vectors = {10: ([0, 2], [0.6, 0.8]), 20: ([1, 3], [0.6, 0.8])}
    vectors.update({service_id: ([0, 1], [0.6, 0.8]) for service_id in range(100, 104)})
    metadata = {**METADATA, **{service_id: MovieMetadata(str(service_id), (), None) for service_id in range(100, 104)}}
    bundle = ArtifactBundle("als-v1", "snapshot-a", 0.1, dict(FACTORS), dict(MAPPING), metadata, MAPPING_REVISION, METADATA_REVISION)

    results = calculate_user_results(bundle, [ProfileInput(10, 5.0, "rating")], [], 7, content=_content(vectors), hybrid_alpha=0.5)

    assert results is not None
    tied = {item["score"] for item in results[1]["taste"] if item["movieId"] >= 100}
    assert len(tied) == 1


def test_alpha_weights_the_als_component_not_the_content_one(frozen_clock) -> None:
    """Pin the direction: a high alpha must follow ALS, a low alpha must follow content.

    Movie 10 is the ALS favourite and movie 20 the content favourite, so swapping
    the two components or inverting the mix flips this order.
    """
    # Only 10 is rated, so ALS favours it; 20's vector points the same way but
    # longer, so the content component favours 20 instead.
    ratings = [ProfileInput(10, 5.0, "rating")]
    content = _content({10: ([0, 2], [0.6, 0.8]), 20: ([0, 2], [1.2, 1.6]), 30: ([0, 1], [0.6, 0.8])})

    als_leaning = calculate_user_results(_bundle(), ratings, [], 7, content=content, hybrid_alpha=0.75)
    content_leaning = calculate_user_results(_bundle(), ratings, [], 7, content=content, hybrid_alpha=0.25)

    assert als_leaning is not None and content_leaning is not None
    als_order = {item["movieId"]: index for index, item in enumerate(als_leaning[1]["taste"])}
    content_order = {item["movieId"]: index for index, item in enumerate(content_leaning[1]["taste"])}
    assert als_order[10] < als_order[20]
    assert content_order[20] < content_order[10]


def test_movies_without_any_component_are_not_candidates(frozen_clock) -> None:
    """Contract: a movie with neither an ALS factor nor a content vector is excluded."""
    # 40 is in the metadata and the content matrix but has an empty vector and no factor.
    vectors = {**CONTENT_VECTORS, 40: ([], [])}
    metadata = {**METADATA, 40: MovieMetadata("40", (), None)}
    bundle = ArtifactBundle("als-v1", "snapshot-a", 0.1, dict(FACTORS), dict(MAPPING), metadata, MAPPING_REVISION, METADATA_REVISION)

    results = calculate_user_results(bundle, [ProfileInput(10, 5.0, "rating")], [], 7, content=_content(vectors), hybrid_alpha=0.5)

    assert results is not None
    assert 40 not in {item["movieId"] for item in results[1]["taste"]}


def test_hybrid_publishes_the_top_500_not_an_arbitrary_500(frozen_clock) -> None:
    """Distinct scores everywhere, so keeping the wrong 500 is detectable.

    Movie 100+index has a longer vector the higher its index, so the content
    ranking is strictly decreasing in service ID and the published set must be
    the *highest* 500 of the 700 candidates.
    """
    vectors = {10: ([0, 2], [0.6, 0.8]), 20: ([1, 3], [0.6, 0.8])}
    vectors.update({100 + index: ([0], [1.0 + index * 0.01]) for index in range(700)})
    metadata = {**METADATA, **{100 + index: MovieMetadata(str(index), (), None) for index in range(700)}}
    bundle = ArtifactBundle("als-v1", "snapshot-a", 0.1, dict(FACTORS), dict(MAPPING), metadata, MAPPING_REVISION, METADATA_REVISION)

    results = calculate_user_results(
        bundle, [ProfileInput(10, 5.0, "rating")], [], 7, content=_content(vectors), hybrid_alpha=0.5
    )

    assert results is not None
    published = [item["movieId"] for item in results[1]["taste"]]
    assert len(published) == 500
    # The weakest content vectors (100..299) must not make the cut.
    assert min(movie_id for movie_id in published if movie_id >= 100) > 100 + 700 - 500 - 5
    assert 799 in published and 100 not in published


def test_hybrid_candidates_are_limited_to_500_and_tie_broken_by_service_id(frozen_clock) -> None:
    """Content-only movies that share one vector tie, so the service ID decides."""
    vectors = {10: ([0, 2], [0.6, 0.8]), 20: ([1, 3], [0.6, 0.8])}
    vectors.update({service_id: ([0, 1], [0.6, 0.8]) for service_id in range(100, 700)})
    content = _content(vectors)
    metadata = {**METADATA, **{service_id: MovieMetadata(str(service_id), (), None) for service_id in range(100, 700)}}
    bundle = ArtifactBundle("als-v1", "snapshot-a", 0.1, dict(FACTORS), dict(MAPPING), metadata, MAPPING_REVISION, METADATA_REVISION)

    results = calculate_user_results(bundle, [ProfileInput(10, 5.0, "rating")], [], 7, content=content, hybrid_alpha=0.5)

    assert results is not None
    items = results[1]["taste"]
    assert len(items) == 500
    tied = [item["movieId"] for item in items if item["movieId"] >= 100]
    assert tied == sorted(tied)
    ordering = [(-item["score"], item["movieId"]) for item in items]
    assert ordering == sorted(ordering)


def test_alpha_one_reproduces_the_als_ordering(frozen_clock) -> None:
    """alpha=1 keeps ALS ranking among ALS-supported movies; content-only movies stay."""
    ratings = [ProfileInput(10, 5.0, "rating")]

    results = calculate_user_results(_bundle(), ratings, [], 7, content=_content(), hybrid_alpha=1.0)

    assert results is not None
    items = results[1]["taste"]
    scores = {item["movieId"]: item["score"] for item in items}
    assert scores[10] > scores[20]
    assert 30 in scores


def test_candidate_top500_is_selected_after_service_publishability_filtering() -> None:
    # The first 600 Factor IDs are deliberately unmapped.  Limiting all
    # factors first would return no service candidates.
    factors = {movie_id: np.array([float(movie_id)]) for movie_id in range(1, 1102)}
    mapping = {10_000 + movie_id: movie_id for movie_id in range(601, 1102)}
    metadata = {
        service_id: MovieMetadata(str(service_id), ("DRAMA",), None)
        for service_id in mapping
    }
    bundle = ArtifactBundle("als-v1", "snapshot", 0.1, factors, mapping, metadata, "sha256:" + "a" * 64, "sha256:" + "b" * 64)

    results = calculate_user_results(
        bundle,
        [ProfileInput(10_601, 5.0, "rating")],
        [],
        1,
    )

    assert results is not None
    candidate_items = results[1]["taste"]
    assert len(candidate_items) == 500
    assert candidate_items[0]["movieId"] == 11_101
    assert candidate_items[-1]["movieId"] == 10_602
    assert len(results) == 2
    assert all("reason" in item and "rerankFeatures" not in item for item in candidate_items)
