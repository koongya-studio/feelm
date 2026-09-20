"""Offline orchestration: leakage boundaries, diagnostics and real ALS integration."""

import importlib
import math

import pytest

from batch.train.settings import AlsConfig
from batch.train.training import train_als_model
from tests.fixtures.als_evaluation import (
    ITEM,
    USER,
    ScoredModel,
    mapping,
    ratings,
)
from tests.fixtures.als_evaluation import spark as spark_fixture

spark = spark_fixture


def evaluate(model, known, holdout, movie_map, stage="validation", n=10):
    return importlib.import_module("batch.evaluation.offline").evaluate_offline(
        model,
        known,
        holdout,
        movie_map,
        stage=stage,
        candidate_size=n,
        positive_rating_threshold=4.0,
        model_version="fixture-v1",
    )


def collected_candidates(frame):
    rows = frame.orderBy("catalog", USER, "rank").collect()
    grouped = {}
    for row in rows:
        grouped.setdefault((row.catalog, row[USER]), []).append(row)
    return grouped


def test_catalog_populations_cold_truth_and_shortage_counts(spark):
    # u1: warm positives 2,3; cold positive 99. u2: cold user, warm movie 2.
    # u3: no positive labels. Movie 3 is unmapped. Catalogs have different denominators.
    model = ScoredModel(spark, users=(1, 3), items=(1, 2, 3))
    result = evaluate(
        model,
        ratings(spark, [(1, 1, 0.5)]),
        ratings(spark, [(1, 2, 4.0), (1, 3, 5.0), (1, 99, 5.0), (2, 2, 4.5), (3, 1, 3.5)]),
        mapping(spark, [2, 99]),
    )
    ml, service = result.summary["movielens"], result.summary["service"]
    for summary in (ml, service):
        assert summary["target_users"] == 3
        assert summary["evaluated_users"] == 2
        assert summary["users_without_positives"] == 1
        assert summary["cold_users"] == 1
        assert summary["cold_items"] == 1
        assert summary["cold_ratings"] == 2
        assert summary["cold_positive_items"] == 2  # distinct user-item positives
        assert summary["zero_candidate_users"] == 1
        assert summary["fewer_than_k_users"] == 3
        assert summary["fewer_than_n_users"] == 3
        assert summary["evaluated_zero_candidate_users"] == 1
        assert summary["evaluated_fewer_than_k_users"] == 2
        assert summary["evaluated_fewer_than_n_users"] == 2
        assert summary["duplicate_recommendations_removed"] == 0
    assert ml["positive_items"] == 4
    assert service["positive_items"] == 3
    assert ml["mapping_excluded_positive_items"] == 0
    assert service["mapping_excluded_positive_items"] == 1
    assert ml["recall_at_10"] == pytest.approx(1 / 3)
    assert service["recall_at_10"] == pytest.approx(1 / 4)
    assert ml["ndcg_at_10"] == pytest.approx(
        (1 + 1 / math.log2(3)) / (1 + 1 / math.log2(3) + 0.5) / 2
    )
    assert service["ndcg_at_10"] == pytest.approx(1 / (1 + 1 / math.log2(3)) / 2)
    assert result.metadata == {
        "model_uid": model.uid,
        "model_version": "fixture-v1",
        "stage": "validation",
        "positive_rating_threshold": 4.0,
        "candidate_size": 10,
        "k": 10,
        "policy_version": "als-offline-v1",
    }


def test_mapping_changes_population_without_removing_cold_positives(spark):
    result = evaluate(
        ScoredModel(spark, items=(1, 99)),
        ratings(spark, []),
        ratings(spark, [(1, 1, 5.0), (99, 99, 5.0)]),
        mapping(spark, [99]),
    )
    assert result.summary["movielens"]["evaluated_users"] == 2
    service = result.summary["service"]
    assert service["evaluated_users"] == 1
    assert service["users_without_positives"] == 1
    assert service["positive_items"] == 1
    assert service["recall_at_10"] == service["ndcg_at_10"] == 0


def test_same_users_different_holdout_items_and_labels_leave_candidates_unchanged(spark):
    model = ScoredModel(spark, items=(1, 2, 3))
    known, movie_map = ratings(spark, [(1, 1, 1.0)]), mapping(spark, (1, 2, 3))
    a = evaluate(model, known, ratings(spark, [(1, 2, 5.0)]), movie_map, n=2)
    b = evaluate(model, known, ratings(spark, [(1, 3, 3.5)]), movie_map, n=2)
    a_candidates = collected_candidates(a.candidates)
    b_candidates = collected_candidates(b.candidates)
    for catalog in ("movielens", "service"):
        assert a_candidates[catalog, 1] == b_candidates[catalog, 1]
        assert 2 in [r[ITEM] for r in a_candidates[catalog, 1]]
        assert a.summary[catalog]["recall_at_10"] == 1
        assert b.summary[catalog]["recall_at_10"] is None


def test_known_holdout_pair_overlap_is_rejected_even_for_low_rating(spark):
    with pytest.raises(ValueError, match="(?i)(overlap|known|leak)"):
        result = evaluate(
            ScoredModel(spark),
            ratings(spark, [(1, 1, 1.0)]),
            ratings(spark, [(1, 1, 3.5)]),
            mapping(spark, [1]),
        )
        result.per_user.collect()


def test_shortage_distinguishes_zero_below_ten_and_below_n(spark):
    result = evaluate(
        ScoredModel(spark, users=(1, 2, 3), items=range(1, 13)),
        ratings(spark, [(2, i, 1.0) for i in range(1, 9)] + [(3, i, 1.0) for i in range(1, 13)]),
        ratings(spark, [(1, 99, 5.0), (2, 99, 5.0), (3, 99, 3.5)]),
        mapping(spark, [*range(1, 13), 99]),
        n=15,
    )
    for summary in result.summary.values():
        assert summary["zero_candidate_users"] == 1
        assert summary["fewer_than_k_users"] == 2
        assert summary["fewer_than_n_users"] == 3
        assert summary["evaluated_zero_candidate_users"] == 0
        assert summary["evaluated_fewer_than_k_users"] == 1
        assert summary["evaluated_fewer_than_n_users"] == 2


def test_test_stage_uses_new_factors_and_excludes_validation_history(spark):
    train = ratings(spark, [(1, 1, 4.0)])
    validation = ratings(spark, [(2, 2, 5.0)])
    test = ratings(spark, [(2, 3, 5.0)])
    movie_map = mapping(spark, [1, 2, 3])
    old = evaluate(ScoredModel(spark, users=(1,), items=(1, 3)), train, validation, movie_map)
    new = evaluate(
        ScoredModel(spark, users=(1, 2), items=(1, 2, 3)),
        train.unionByName(validation),
        test,
        movie_map,
        stage="test",
    )
    assert old.summary["movielens"]["cold_users"] == 1
    assert old.summary["movielens"]["cold_items"] == 1
    assert new.summary["movielens"]["cold_users"] == 0
    assert new.summary["movielens"]["cold_items"] == 0
    candidates_by_population = collected_candidates(new.candidates)
    for catalog in ("movielens", "service"):
        assert [r[ITEM] for r in candidates_by_population[catalog, 2]] == [1, 3]
    assert new.metadata["stage"] == "test"


@pytest.fixture(scope="module")
def trained(spark):
    # Each user sees two of four movies; every item has a learned factor.
    train = ratings(
        spark,
        [
            (1, 1, 5.0),
            (1, 2, 2.0),
            (2, 2, 4.0),
            (2, 3, 5.0),
            (3, 3, 2.0),
            (3, 4, 5.0),
            (4, 4, 4.0),
            (4, 1, 3.0),
        ],
    )
    return train, train_als_model(train, AlsConfig(rank=2, reg_param=0.1, max_iter=2), seed=42)


def test_real_als_fixture_produces_finite_predictions(spark, trained):
    train, model = trained
    assert train.count() == 8
    assert model.userFactors.count() == model.itemFactors.count() == 4
    scores = model.transform(ratings(spark, [(1, 3, 5.0), (1, 4, 5.0)])).collect()
    assert len(scores) == 2
    assert all(math.isfinite(r.prediction) for r in scores)


def test_actual_als_training_candidates_and_metrics_match_independent_oracle(spark, trained):
    train, model = trained
    strategy = model.getColdStartStrategy()
    result = evaluate(
        model, train, ratings(spark, [(1, 3, 5.0), (99, 3, 5.0)]), mapping(spark, [3, 4])
    )
    # Score both eligible items directly with ALS; the oracle never calls candidate code.
    predictions = model.transform(ratings(spark, [(1, 3, 5.0), (1, 4, 5.0)])).collect()
    expected = sorted(predictions, key=lambda r: (-r.prediction, r[ITEM]))
    hit_rank = next(i for i, r in enumerate(expected, 1) if r[ITEM] == 3)
    candidates_by_population = collected_candidates(result.candidates)
    for catalog in ("movielens", "service"):
        actual = candidates_by_population[catalog, 1]
        assert [r[ITEM] for r in actual] == [r[ITEM] for r in expected]
        assert [r.score for r in actual] == pytest.approx([r.prediction for r in expected])
        assert result.summary[catalog]["recall_at_10"] == pytest.approx(0.5)
        assert result.summary[catalog]["ndcg_at_10"] == pytest.approx(
            1 / math.log2(hit_rank + 1) / 2
        )
        assert candidates_by_population.get((catalog, 99), []) == []
    assert model.getColdStartStrategy() == strategy
    assert train.count() == 8
    assert set(result.per_user.columns) >= {USER, "catalog", "recall_at_10", "ndcg_at_10"}
