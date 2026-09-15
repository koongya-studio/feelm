"""Independent hand-calculated ranking oracles, including population denominators."""

import importlib
import math

import pytest
from pyspark.sql import functions as F
from pyspark.sql.types import DoubleType

from tests.fixtures.als_evaluation import (
    ITEM,
    candidates,
    ratings,
)
from tests.fixtures.als_evaluation import spark as spark_fixture

spark = spark_fixture


def score(recommendations, holdout, users=None):
    if users is None:
        users = holdout.select("movielens_user_id").distinct()
    return importlib.import_module("batch.evaluation.metrics").evaluate_ranking(
        recommendations,
        holdout,
        users,
        catalog="movielens",
        positive_rating_threshold=4.0,
        k=10,
    )


def test_hand_computed_recall_and_ndcg_with_short_recommendations(spark):
    result = score(
        candidates(spark, [(1, 1, 9.0, 1), (1, 9, 8.0, 2), (1, 2, 7.0, 3)]),
        ratings(spark, [(1, 1, 4.0), (1, 2, 5.0), (1, 3, 4.5)]),
    )
    row = result.per_user.collect()[0]
    assert row.recall_at_10 == pytest.approx(2 / 3)
    assert row.ndcg_at_10 == pytest.approx(1.5 / (1 + 1 / math.log2(3) + 0.5))
    assert result.summary["evaluated_users"] == 1
    assert result.summary["positive_items"] == 3
    assert result.summary["recall_at_10"] == pytest.approx(2 / 3)
    assert result.summary["ndcg_at_10"] == pytest.approx(row.ndcg_at_10)


def test_cutoff_ten_does_not_shrink_twelve_item_truth_denominator(spark):
    result = score(
        candidates(spark, [(1, i, float(20 - i), i) for i in range(1, 13)]),
        ratings(spark, [(1, i, 5.0) for i in range(1, 13)]),
    )
    row = result.per_user.collect()[0]
    assert row.recall_at_10 == pytest.approx(10 / 12)
    assert row.ndcg_at_10 == pytest.approx(1.0)


def test_macro_population_includes_empty_recommendations_but_not_no_positives(spark):
    result = score(
        candidates(spark, [(1, 1, 1.0, 1), (3, 3, 1.0, 1)]),
        ratings(spark, [(1, 1, 4.0), (2, 2, 5.0), (2, 4, 4.5), (3, 3, 3.5)]),
    )
    rows = {r.movielens_user_id: r for r in result.per_user.collect()}
    assert set(rows) == {1, 2}
    assert rows[2].recall_at_10 == rows[2].ndcg_at_10 == 0
    assert result.summary["target_users"] == 3
    assert result.summary["evaluated_users"] == 2
    assert result.summary["users_without_positives"] == 1
    assert result.summary["positive_items"] == 3
    assert result.summary["recall_at_10"] == pytest.approx(0.5)
    assert result.summary["ndcg_at_10"] == pytest.approx(0.5)


@pytest.mark.parametrize("holdout_rows", [[], [(1, 1, 3.5)]])
def test_no_eligible_users_returns_null_averages(spark, holdout_rows):
    result = score(candidates(spark, []), ratings(spark, holdout_rows))
    assert result.per_user.count() == 0
    assert result.summary["evaluated_users"] == 0
    assert result.summary["recall_at_10"] is None
    assert result.summary["ndcg_at_10"] is None


def test_materialized_per_user_is_shared_by_summary_and_later_consumers(spark):
    rating_calls = spark.sparkContext.accumulator(0)

    @F.udf(returnType=DoubleType())
    def observed_rating(value):
        rating_calls.add(1)
        return value

    holdout = ratings(spark, [(1, 2, 5.0)]).withColumn(
        "rating", observed_rating("rating")
    )
    users = holdout.select("movielens_user_id")
    module = importlib.import_module("batch.evaluation.metrics")
    result = module.evaluate_ranking(
        candidates(spark, [(1, 2, 1.0, 1)]),
        holdout,
        users,
        catalog="movielens",
        positive_rating_threshold=4.0,
        k=10,
        materialize_per_user=True,
    )
    try:
        assert result.per_user.is_cached
        assert rating_calls.value == 2
        result.per_user.collect()
        result.per_user.count()
        assert rating_calls.value == 2
    finally:
        result.per_user.unpersist()


def test_duplicates_keep_highest_score_then_recompute_contiguous_ranks(spark):
    # The duplicate's highest score moves item 2 to rank 1. Supplied ranks are stale.
    recs = candidates(spark, [(1, 1, 8.0, 50), (1, 9, 7.0, 60), (1, 2, 6.0, 70), (1, 2, 9.0, 80)])
    truth = ratings(spark, [(1, 1, 5.0), (1, 2, 5.0), (1, 2, 5.0), (1, 3, 5.0)])
    result = score(recs, truth)
    row = result.per_user.collect()[0]
    assert row.recall_at_10 == pytest.approx(2 / 3)
    assert row.ndcg_at_10 == pytest.approx((1 + 1 / math.log2(3)) / (1 + 1 / math.log2(3) + 0.5))
    assert result.summary["duplicate_recommendations_removed"] == 1
    assert result.summary["positive_items"] == 3


def test_metric_ties_at_cutoff_use_item_id_across_partitions(spark):
    recs = candidates(spark, [(1, i, 1.0, 16 - i) for i in reversed(range(1, 16))])
    truth = ratings(spark, [(1, 10, 5.0), (1, 11, 5.0)])
    for frame in (recs, recs.orderBy(ITEM).repartition(3)):
        result = score(frame, truth)
        row = result.per_user.collect()[0]
        assert row.recall_at_10 == pytest.approx(0.5)
        assert row.ndcg_at_10 == pytest.approx((1 / math.log2(11)) / (1 + 1 / math.log2(3)))


@pytest.mark.parametrize("bad_score", [float("nan"), float("inf"), float("-inf"), None])
def test_invalid_recommendation_score_is_rejected_even_below_cutoff(spark, bad_score):
    recs = candidates(
        spark, [(1, i, float(20 - i), i) for i in range(1, 11)] + [(1, 11, bad_score, 11)]
    )
    with pytest.raises(ValueError, match="(?i)(score|finite|null)"):
        result = score(recs, ratings(spark, [(1, 1, 5.0)]))
        result.per_user.collect()
