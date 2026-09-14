"""Unit tests for deterministic structured-content feature preparation."""

from __future__ import annotations

import math

import numpy as np
import pytest

from batch.content.features import (
    build_content_vector,
    build_vocabulary,
    content_profile,
    normalize_metadata_row,
    user_equal_prior,
    validate_metadata_collection,
)


def test_input_validation_normalizes_null_arrays_and_rejects_invalid_values():
    row = normalize_metadata_row(
        {
            "tmdb_id": 8,
            "genre_ids": None,
            "original_language": " KO ",
            "release_year": 2020,
            "runtime_minutes": 120,
            "director_ids": None,
            "top5_cast_ids": None,
            "keyword_ids": None,
            "feature_eligible": True,
        }
    )
    assert row["genre_ids"] == ()
    assert row["original_language"] == "ko"

    for field, value in (("tmdb_id", 0), ("genre_ids", [1, -2]), ("runtime_minutes", 0)):
        invalid = {**row, field: value}
        with pytest.raises(ValueError):
            normalize_metadata_row(invalid)


def test_group_normalization_deduplicates_tokens_and_normalizes_four_groups():
    genre_only = build_content_vector(
        normalize_metadata_row(
            {
                "tmdb_id": 1,
                "genre_ids": [1, 1, 2],
                "original_language": None,
                "release_year": None,
                "runtime_minutes": None,
                "director_ids": [],
                "top5_cast_ids": [],
                "keyword_ids": [],
                "feature_eligible": True,
            }
        )
    )
    assert genre_only == {
        "genre:1": pytest.approx(1 / math.sqrt(2)),
        "genre:2": pytest.approx(1 / math.sqrt(2)),
    }

    every_group = build_content_vector(
        normalize_metadata_row(
            {
                "tmdb_id": 2,
                "genre_ids": [1],
                "original_language": "ko",
                "release_year": None,
                "runtime_minutes": None,
                "director_ids": [2],
                "top5_cast_ids": [],
                "keyword_ids": [3],
                "feature_eligible": True,
            }
        )
    )
    assert every_group == {
        "genre:1": pytest.approx(0.5),
        "language:ko": pytest.approx(0.5),
        "director:2": pytest.approx(0.5),
        "keyword:3": pytest.approx(0.5),
    }


def test_vocabulary_is_stable_and_unknown_tokens_are_ignored():
    first = build_content_vector(
        normalize_metadata_row(
            {
                "tmdb_id": 1,
                "genre_ids": [2],
                "original_language": None,
                "release_year": None,
                "runtime_minutes": None,
                "director_ids": [],
                "top5_cast_ids": [],
                "keyword_ids": [],
                "feature_eligible": True,
            }
        )
    )
    second = build_content_vector(
        normalize_metadata_row(
            {
                "tmdb_id": 2,
                "genre_ids": [1],
                "original_language": "ko",
                "release_year": None,
                "runtime_minutes": None,
                "director_ids": [],
                "top5_cast_ids": [],
                "keyword_ids": [],
                "feature_eligible": True,
            }
        )
    )
    vocabulary = build_vocabulary([second, first])
    assert vocabulary == {"genre:1": 0, "genre:2": 1, "language:ko": 2}

    indices, values, unknown_count = build_content_vector({"genre:404": 1.0}, vocabulary)
    assert indices == () and values == () and unknown_count == 1


def test_user_equal_prior_does_not_pool_rating_counts():
    probability, midrank = user_equal_prior([[0] * 9 + [9], [1] + [0] * 9])
    assert probability[0] == probability[9] == pytest.approx(0.5)
    assert midrank[0] == pytest.approx(0.25)
    assert midrank[9] == pytest.approx(0.75)


def test_profile_uses_supported_history_only_and_preserves_raw_scale():
    vectors = np.asarray([[1.0, 0.0], [0.0, 1.0]])
    profile, supported, missing, status = content_profile(
        vectors,
        [5.0, 0.5, 3.0],
        [True, True, False],
        np.linspace(0.05, 0.95, 10),
    )
    np.testing.assert_allclose(profile, [0.5, -0.5], rtol=1e-12, atol=1e-12)
    assert (supported, missing, status) == (2, 1, "READY")


def test_collection_rejects_duplicate_tmdb_and_missing_actor_order_before_publish():
    row = {
        "tmdb_id": 1,
        "genre_ids": [],
        "original_language": None,
        "release_year": None,
        "runtime_minutes": None,
        "director_ids": [],
        "top5_cast_ids": [7],
        "top5_cast_source_order": [0],
        "keyword_ids": [],
        "feature_eligible": True,
    }
    with pytest.raises(ValueError, match="duplicate"):
        validate_metadata_collection([row, row])
    with pytest.raises(ValueError, match="provenance"):
        validate_metadata_collection(
            [{key: value for key, value in row.items() if key != "top5_cast_source_order"}]
        )
