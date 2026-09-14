"""C18: real local Spark prepare -> validation -> final -> coverage parity."""

from __future__ import annotations

import pytest
from pyspark.sql import SparkSession

import batch.evaluation.content_hybrid_run as content_hybrid_run
from batch.content.artifacts import write_content_artifact
from batch.evaluation.content_hybrid import ALS_ONLY, CONTENT_ONLY, HybridPolicy
from batch.evaluation.content_hybrid_run import (
    _content_percentiles,
    _selection_path,
    _validate_evaluation_input_ids,
    _validate_movie_artifact_join,
    run_local_content_hybrid,
)


def test_selection_paths_are_isolated_per_immutable_content_version(tmp_path):
    first = _selection_path(tmp_path, "content-v1", "als-v1", "map-v1")
    second = _selection_path(tmp_path, "content-v2", "als-v1", "map-v1")
    collision_left = _selection_path(tmp_path, "a--b", "c", "d")
    collision_right = _selection_path(tmp_path, "a", "b", "c--d")

    assert first != second
    assert first == (
        tmp_path
        / "content-evaluations"
        / "content-v1"
        / "als-v1"
        / "map-v1"
        / "selection"
        / "selection.json"
    )
    assert second != first
    assert collision_left != collision_right


def test_content_percentiles_disable_non_discriminating_catalog_and_mapping_damage():
    assert _content_percentiles([({}, None, 0.5), ({}, None, 0.5)]) is None
    assert _content_percentiles([({}, None, 0.5), ({}, None, 0.7)]) is not None
    payload = {"features": [{"tmdb_id": 1}, {"tmdb_id": 2}]}
    with pytest.raises(ValueError, match="unique"):
        _validate_movie_artifact_join(
            [
                {
                    "service_movie_id": 1,
                    "movielens_movie_id": 1,
                    "tmdb_id": 1,
                    "mapping_status": "MATCHED",
                },
                {
                    "service_movie_id": 2,
                    "movielens_movie_id": 2,
                    "tmdb_id": 1,
                    "mapping_status": "MATCHED",
                },
            ],
            payload,
        )
    with pytest.raises(ValueError, match="missing from content artifact"):
        _validate_movie_artifact_join(
            [
                {
                    "service_movie_id": 1,
                    "movielens_movie_id": 1,
                    "tmdb_id": 3,
                    "mapping_status": "MATCHED",
                }
            ],
            payload,
        )


@pytest.mark.parametrize(
    "status,movielens_movie_id,accepted",
    [
        ("MATCHED", 1, True),
        ("MATCHED", None, False),
        ("SERVICE_ONLY_CONTENT_BASED", None, True),
        ("SERVICE_ONLY_CONTENT_BASED", 1, False),
        ("TMDB_ID_AMBIGUOUS", None, True),
        ("TMDB_ID_AMBIGUOUS", 1, False),
    ],
)
def test_movie_artifact_join_enforces_mapping_status_nullability(
    status, movielens_movie_id, accepted
):
    movie = {
        "service_movie_id": 1,
        "tmdb_id": 1,
        "movielens_movie_id": movielens_movie_id,
        "mapping_status": status,
    }
    payload = {"features": [{"tmdb_id": 1}]}
    if accepted:
        _validate_movie_artifact_join([movie], payload)
    else:
        with pytest.raises(ValueError, match="movielens_movie_id"):
            _validate_movie_artifact_join([movie], payload)


@pytest.mark.parametrize(
    "field,bad_value",
    [
        ("service_movie_id", True),
        ("service_movie_id", 1.0),
        ("service_movie_id", "1"),
        ("tmdb_id", True),
        ("tmdb_id", 1.0),
        ("tmdb_id", "1"),
        ("movielens_movie_id", True),
        ("movielens_movie_id", 1.0),
        ("movielens_movie_id", "1"),
    ],
)
def test_movie_artifact_join_rejects_coercible_id_types(field, bad_value):
    movie = {
        "service_movie_id": 1,
        "tmdb_id": 1,
        "movielens_movie_id": 1,
        "mapping_status": "MATCHED",
    }
    movie[field] = bad_value
    with pytest.raises(ValueError, match="positive"):
        _validate_movie_artifact_join([movie], {"features": [{"tmdb_id": 1}]})


@pytest.mark.parametrize(
    "field,bad_value",
    [
        ("service_movie_id", 1.0),
        ("service_movie_id", "1"),
        ("service_movie_id", True),
        ("tmdb_id", 1.0),
        ("tmdb_id", "1"),
        ("tmdb_id", True),
        ("movielens_movie_id", 1.0),
        ("movielens_movie_id", "1"),
        ("movielens_movie_id", True),
    ],
)
def test_movie_artifact_join_rejects_coercion_before_duplicate_detection(field, bad_value):
    movies = [
        {
            "service_movie_id": 1,
            "tmdb_id": 1,
            "movielens_movie_id": 1,
            "mapping_status": "MATCHED",
        },
        {
            "service_movie_id": 2,
            "tmdb_id": 2,
            "movielens_movie_id": 2,
            "mapping_status": "MATCHED",
        },
    ]
    movies[1][field] = bad_value
    with pytest.raises(ValueError, match="positive"):
        _validate_movie_artifact_join(movies, {"features": [{"tmdb_id": 1}, {"tmdb_id": 2}]})


@pytest.mark.parametrize("bad_value", [True, 1.0, "1"])
@pytest.mark.parametrize("field", ["user_id", "movielens_movie_id"])
def test_rating_ids_reject_bool_float_and_string_without_coercion(field, bad_value):
    ratings = [{"user_id": 1, "movielens_movie_id": 1, "rating": 5.0, "stage": "train"}]
    ratings[0][field] = bad_value
    with pytest.raises(ValueError, match="rating .*positive"):
        _validate_evaluation_input_ids(ratings, [])


@pytest.mark.parametrize("bad_value", [True, 1.0, "1"])
@pytest.mark.parametrize("field", ["user_id", "service_movie_id"])
def test_score_ids_reject_bool_float_and_string_without_coercion(field, bad_value):
    scores = [{"user_id": 1, "service_movie_id": 1, "als_score": 1.0}]
    scores[0][field] = bad_value
    with pytest.raises(ValueError, match="score .*positive"):
        _validate_evaluation_input_ids([], scores)


@pytest.mark.parametrize(
    "scores,match",
    [
        (
            [
                {"user_id": 1, "service_movie_id": 1, "als_score": 1.0},
                {"user_id": 1, "service_movie_id": 1, "als_score": 2.0},
            ],
            "duplicate",
        ),
        (
            [
                {"user_id": 1, "service_movie_id": 1, "als_score": 1.0},
                {"user_id": "1", "service_movie_id": True, "als_score": 2.0},
            ],
            "score .*positive",
        ),
    ],
)
def test_score_duplicate_and_coercion_collision_rows_are_rejected(scores, match):
    with pytest.raises(ValueError, match=match):
        _validate_evaluation_input_ids([], scores)


@pytest.fixture(scope="module")
def spark():
    session = (
        SparkSession.builder.master("local[1]").appName("content-hybrid-local-e2e").getOrCreate()
    )
    yield session
    session.stop()


def test_local_spark_e2e_chunk_parity_and_sealed_final_execution(spark, tmp_path, monkeypatch):
    original_final_policies = content_hybrid_run.final_test_policies_from_selection
    final_policy_calls = []

    def spy_final_policies(*args, **kwargs):
        policies = original_final_policies(*args, **kwargs)
        final_policy_calls.append(policies)
        return policies

    monkeypatch.setattr(
        content_hybrid_run, "final_test_policies_from_selection", spy_final_policies
    )
    metadata = [
        {
            "tmdb_id": item,
            "genre_ids": [1] if item in {1, 4, 5} else [item],
            "original_language": "ko",
            "release_year": 2020,
            "runtime_minutes": 100,
            "director_ids": [],
            "top5_cast_ids": [],
            "top5_cast_source_order": [],
            "keyword_ids": [],
            "feature_eligible": True,
        }
        for item in (*range(1, 504), 900)
    ]
    movies = [
        {
            "service_movie_id": item,
            "movielens_movie_id": item,
            "tmdb_id": item,
            "mapping_status": "MATCHED",
        }
        for item in range(1, 504)
    ]
    movies += [
        {
            "service_movie_id": 900,
            "movielens_movie_id": None,
            "tmdb_id": 900,
            "mapping_status": "SERVICE_ONLY_CONTENT_BASED",
        }
    ]
    ratings = [
        {"user_id": 1, "movielens_movie_id": 1, "rating": 0.5, "stage": "train"},
        {"user_id": 1, "movielens_movie_id": 4, "rating": 5.0, "stage": "train"},
        {"user_id": 1, "movielens_movie_id": 2, "rating": 5.0, "stage": "validation"},
        {"user_id": 1, "movielens_movie_id": 3, "rating": 5.0, "stage": "test"},
    ]
    scores = [
        {
            "user_id": 1,
            "service_movie_id": item,
            "als_score": float(600 - item),
            # Hostile input that must not influence content scoring.
            "content_score": float(item * 1000),
        }
        for item in range(1, 504)
    ]
    scores.append(
        {"user_id": 1, "service_movie_id": 900, "als_score": 700.0, "content_score": 999999.0}
    )
    results = [
        run_local_content_hybrid(
            spark,
            metadata=metadata,
            ratings=ratings,
            movies=movies,
            scores=scores,
            output_uri=tmp_path / f"chunk-{chunk}",
            content_version="content-v1",
            als_model_version="als-v1",
            mapping_snapshot="map-v1",
            chunk_size=chunk,
        )
        for chunk in (1, 2, 100)
    ]
    assert [result.validation for result in results] == [results[0].validation] * 3
    assert [result.final for result in results] == [results[0].final] * 3
    assert [result.candidates for result in results] == [results[0].candidates] * 3
    assert {row["policy_id"] for row in results[0].candidates} == {
        "ALS_ONLY",
        "CONTENT_ONLY",
        "HYBRID_A025",
    }
    content_only_top = next(
        row
        for row in results[0].candidates
        if row["catalog"] == "MATCHED_QUALITY"
        and row["policy_id"] == "CONTENT_ONLY"
        and row["rank"] == 1
    )
    # The sealed profile ranks movie 3 first.  The hostile input content_score
    # would rank movie 503 first, proving this path does not use input JSON.
    assert content_only_top["service_movie_id"] == 3
    assert max(row["rank"] for row in results[0].candidates) == 500
    assert results[0].coverage["service_only_recommended_items"] == 1
    assert any(
        row["catalog"] == "SERVICE_COVERAGE" and row["service_movie_id"] == 900
        for row in results[0].candidates
    )
    assert results[0].cache_releases == 2
    # SERVICE_COVERAGE is intentionally outside _evaluate_stage's MATCHED quality comparison.
    assert final_policy_calls == [(ALS_ONLY, CONTENT_ONLY, HybridPolicy(0.25))] * 3
    assert all(HybridPolicy(0.5) not in policies for policies in final_policy_calls)
    assert all(HybridPolicy(0.75) not in policies for policies in final_policy_calls)
    assert all(
        result.selection_path
        == tmp_path
        / f"chunk-{chunk}"
        / "content-evaluations"
        / "content-v1"
        / "als-v1"
        / "map-v1"
        / "selection"
        / "selection.json"
        for result, chunk in zip(results, (1, 2, 100), strict=True)
    )


def test_local_run_reuses_only_exact_public_artifact_lineage(spark, tmp_path, monkeypatch):
    write_content_artifact(
        tmp_path,
        version="content-v1",
        features=[
            {"tmdb_id": 1, "indices": [0], "values": [1.0]},
            {"tmdb_id": 2, "indices": [0], "values": [1.0]},
            {"tmdb_id": 3, "indices": [0], "values": [1.0]},
        ],
        vocabulary={"genre:1": 0},
        prior=[0.1] * 10,
        als_model_version="als-v1",
        mapping_snapshot="map-v1",
    )
    movies = [
        {
            "service_movie_id": item,
            "movielens_movie_id": item,
            "tmdb_id": item,
            "mapping_status": "MATCHED",
        }
        for item in (1, 2, 3)
    ]
    ratings = [
        {"user_id": 1, "movielens_movie_id": 1, "rating": 5.0, "stage": "train"},
        {"user_id": 1, "movielens_movie_id": 2, "rating": 5.0, "stage": "validation"},
        {"user_id": 1, "movielens_movie_id": 3, "rating": 5.0, "stage": "test"},
    ]
    kwargs = dict(
        metadata=[],
        ratings=ratings,
        movies=movies,
        scores=[{"user_id": 1, "service_movie_id": 3, "als_score": 1.0}],
        output_uri=tmp_path,
        content_version="content-v1",
        als_model_version="als-v1",
        mapping_snapshot="map-v1",
        reuse_existing_artifact=True,
    )
    original_evaluate_stage = content_hybrid_run._evaluate_stage
    failed_final = False

    def fail_final_once(stage, *args, **kwargs):
        nonlocal failed_final
        if stage == "test" and not failed_final:
            failed_final = True
            raise RuntimeError("transient final failure")
        return original_evaluate_stage(stage, *args, **kwargs)

    monkeypatch.setattr(content_hybrid_run, "_evaluate_stage", fail_final_once)
    with pytest.raises(RuntimeError, match="transient final failure"):
        run_local_content_hybrid(spark, **kwargs)
    selection_path = _selection_path(tmp_path, "content-v1", "als-v1", "map-v1")
    sealed_selection = selection_path.read_bytes()
    monkeypatch.setattr(content_hybrid_run, "_evaluate_stage", original_evaluate_stage)
    result = run_local_content_hybrid(spark, **kwargs)
    assert result.selection_path.is_file()
    assert result.validation == {}
    assert result.final
    assert result.coverage
    assert selection_path.read_bytes() == sealed_selection
    with pytest.raises(ValueError, match="ALS model version mismatch"):
        run_local_content_hybrid(spark, **{**kwargs, "als_model_version": "als-v2"})
    with pytest.raises(ValueError, match="mapping snapshot mismatch"):
        run_local_content_hybrid(spark, **{**kwargs, "mapping_snapshot": "map-v2"})


def test_coverage_failure_exact_retry_preserves_selection_and_reruns_final_and_coverage(
    spark, tmp_path, monkeypatch
):
    write_content_artifact(
        tmp_path,
        version="content-v1",
        features=[
            {"tmdb_id": 1, "indices": [0], "values": [1.0]},
            {"tmdb_id": 2, "indices": [0], "values": [1.0]},
            {"tmdb_id": 3, "indices": [0], "values": [1.0]},
        ],
        vocabulary={"genre:1": 0},
        prior=[0.1] * 10,
        als_model_version="als-v1",
        mapping_snapshot="map-v1",
    )
    kwargs = dict(
        metadata=[],
        ratings=[
            {"user_id": 1, "movielens_movie_id": 1, "rating": 5.0, "stage": "train"},
            {"user_id": 1, "movielens_movie_id": 2, "rating": 5.0, "stage": "validation"},
            {"user_id": 1, "movielens_movie_id": 3, "rating": 5.0, "stage": "test"},
        ],
        movies=[
            {
                "service_movie_id": item,
                "movielens_movie_id": item,
                "tmdb_id": item,
                "mapping_status": "MATCHED",
            }
            for item in (1, 2, 3)
        ],
        scores=[{"user_id": 1, "service_movie_id": 3, "als_score": 1.0}],
        output_uri=tmp_path,
        content_version="content-v1",
        als_model_version="als-v1",
        mapping_snapshot="map-v1",
        reuse_existing_artifact=True,
    )
    original_evaluate_stage = content_hybrid_run._evaluate_stage
    original_coverage_evidence = content_hybrid_run._coverage_evidence
    evaluated_stages = []
    coverage_calls = 0

    def observe_evaluate_stage(stage, *args, **stage_kwargs):
        evaluated_stages.append(stage)
        return original_evaluate_stage(stage, *args, **stage_kwargs)

    def fail_coverage_once(*args, **coverage_kwargs):
        nonlocal coverage_calls
        coverage_calls += 1
        if coverage_calls == 1:
            raise RuntimeError("transient coverage failure")
        return original_coverage_evidence(*args, **coverage_kwargs)

    monkeypatch.setattr(content_hybrid_run, "_evaluate_stage", observe_evaluate_stage)
    monkeypatch.setattr(content_hybrid_run, "_coverage_evidence", fail_coverage_once)
    with pytest.raises(RuntimeError, match="transient coverage failure"):
        run_local_content_hybrid(spark, **kwargs)

    selection_path = _selection_path(tmp_path, "content-v1", "als-v1", "map-v1")
    sealed_selection = selection_path.read_bytes()
    result = run_local_content_hybrid(spark, **kwargs)

    assert result.validation == {}
    assert result.final
    assert result.coverage
    assert selection_path.read_bytes() == sealed_selection
    assert evaluated_stages == ["validation", "test", "test"]
    assert coverage_calls == 2


@pytest.mark.parametrize(
    ("first_stage", "second_stage"),
    [("train", "validation"), ("validation", "test")],
)
def test_local_run_rejects_known_holdout_overlap_before_artifact_publish(
    spark, tmp_path, first_stage, second_stage
):
    metadata = [
        {
            "tmdb_id": 7,
            "genre_ids": [1],
            "original_language": "ko",
            "release_year": 2020,
            "runtime_minutes": 100,
            "director_ids": [],
            "top5_cast_ids": [],
            "top5_cast_source_order": [],
            "keyword_ids": [],
            "feature_eligible": True,
        }
    ]
    ratings = [
        {"user_id": 1, "movielens_movie_id": 7, "rating": 5.0, "stage": first_stage},
        # A low-rated holdout must be rejected too; it cannot be silently skipped
        # merely because it is not a quality positive.
        {"user_id": 1, "movielens_movie_id": 7, "rating": 1.0, "stage": second_stage},
    ]
    with pytest.raises(ValueError, match="overlap"):
        run_local_content_hybrid(
            spark,
            metadata=metadata,
            ratings=ratings,
            movies=[
                {
                    "service_movie_id": 7,
                    "movielens_movie_id": 7,
                    "tmdb_id": 7,
                    "mapping_status": "MATCHED",
                }
            ],
            scores=[],
            output_uri=tmp_path / f"overlap-{first_stage}-{second_stage}",
            content_version="content-v1",
            als_model_version="als-v1",
            mapping_snapshot="map-v1",
        )
    assert not (
        tmp_path / f"overlap-{first_stage}-{second_stage}" / "content-artifacts" / "content-v1"
    ).exists()
