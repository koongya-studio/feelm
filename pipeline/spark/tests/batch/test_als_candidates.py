"""Candidate acceptance tests. Imports stay inside tests to expose every RED case."""

import importlib

import pytest
from pyspark.sql.types import LongType

from tests.fixtures.als_evaluation import (
    ITEM,
    MAPPING_REVISION,
    USER,
    ScoredModel,
    mapping,
    ordered,
    ratings,
)
from tests.fixtures.als_evaluation import spark as spark_fixture

spark = spark_fixture


def generate(model, users, known, movie_map, n=10):
    return importlib.import_module("batch.evaluation.candidates").generate_candidates(
        model, users, known, movie_map, candidate_size=n
    )


def test_fixture_score_lookup_and_schema_are_valid(spark):
    model = ScoredModel(spark)
    frame = ratings(spark, [(1, 1, 1.0), (1, 15, 5.0)])
    assert [r.prediction for r in model.transform(frame).orderBy(ITEM).collect()] == [99.0, 85.0]
    assert isinstance(mapping(spark, [1]).schema["service_movie_id"].dataType, LongType)
    assert ratings(spark, []).count() == 0


def test_known_low_ratings_excluded_before_each_catalog_top_n(spark):
    model = ScoredModel(spark)
    users = spark.createDataFrame([(1,), (1,), (99,)], f"{USER} int")
    result = generate(
        model, users, ratings(spark, [(1, 1, 0.5), (1, 2, 5.0)]), mapping(spark, range(6, 16))
    )
    assert [r[ITEM] for r in ordered(result)] == list(range(3, 13))
    service = ordered(result, "service")
    assert [r[ITEM] for r in service] == list(range(6, 16))
    assert [r.service_movie_id for r in service] == [2**33 + i for i in range(6, 16)]
    assert isinstance(result.schema["service_movie_id"].dataType, LongType)
    assert [r.rank for r in service] == list(range(1, 11))
    assert ordered(result, user=99) == []
    assert ordered(result, "service", user=99) == []
    assert result.count() == 20  # duplicated request user cannot duplicate candidates
    assert model.getColdStartStrategy() == "nan"


def test_ties_repartition_and_reverse_input_have_identical_rankings(spark):
    model = ScoredModel(spark, scores=[(1, i, 2.0) for i in reversed(range(1, 16))])
    users = spark.createDataFrame([(1,)], f"{USER} int")
    known = ratings(spark, [])
    movie_map = mapping(spark, reversed(range(1, 16)))
    first = generate(model, users, known, movie_map)
    model.scores = model.scores.orderBy(ITEM).repartition(3)
    model.itemFactors = model.itemFactors.repartition(3)
    second = generate(model, users.repartition(3), known, movie_map.repartition(3))
    for catalog in ("movielens", "service"):
        assert ordered(first, catalog) == ordered(second, catalog)
        assert [r[ITEM] for r in ordered(second, catalog)] == list(range(1, 11))


def test_short_and_empty_catalogs_are_not_padded(spark):
    model = ScoredModel(spark, items=(1, 2, 3))
    users = spark.createDataFrame([(1,)], f"{USER} int")
    result = generate(model, users, ratings(spark, [(1, 1, 2.0)]), mapping(spark, []))
    assert [r[ITEM] for r in ordered(result)] == [2, 3]
    assert ordered(result, "service") == []


def test_duplicate_matched_movielens_id_is_rejected(spark):
    movie_map = mapping(spark, [1])
    duplicate = spark.createDataFrame(
        [(2**35, 3001, 1, "MATCHED", MAPPING_REVISION)], movie_map.schema
    )
    with pytest.raises(ValueError, match="(?i)(duplicate|ambiguous|multiple)"):
        generate(
            ScoredModel(spark),
            spark.createDataFrame([(1,)], f"{USER} int"),
            ratings(spark, []),
            movie_map.unionByName(duplicate),
        ).collect()


@pytest.mark.parametrize("score", [float("nan"), float("inf"), float("-inf"), None])
def test_invalid_model_scores_are_errors_not_cold_start(spark, score):
    model = ScoredModel(spark, items=(1,), scores=[(1, 1, score)])
    with pytest.raises(ValueError, match="(?i)(score|prediction|finite|null)"):
        generate(
            model,
            spark.createDataFrame([(1,)], f"{USER} int"),
            ratings(spark, []),
            mapping(spark, [1]),
        ).collect()
