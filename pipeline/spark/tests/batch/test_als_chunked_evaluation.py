"""Behavioral contract for exact, bounded user-chunk offline evaluation."""

import importlib
import math

import pytest
from pyspark.sql import functions as F
from pyspark.sql.types import DoubleType

from tests.fixtures.als_evaluation import (
    ITEM,
    MAPPING_REVISION,
    USER,
    ScoredModel,
    mapping,
    ratings,
)
from tests.fixtures.als_evaluation import spark as spark_fixture

spark = spark_fixture

CANDIDATE_COLUMNS = (USER, ITEM, "score", "rank", "catalog", "service_movie_id")
PER_USER_COLUMNS = (USER, "recall_at_10", "ndcg_at_10", "catalog")
EQUIVALENCE_INPUTS = {
    "known_rows": [(1, 1, 0.5), (2, 1, 1.0)],
    "holdout_rows": [(1, 2, 5.0), (2, 3, 5.0)],
    "mapping_items": [2, 3],
}
LINEAGE_INPUTS = {
    "known_rows": [(1, 1, 0.5), (2, 1, 1.0), (2, 2, 5.0)],
    "holdout_rows": [(1, 2, 5.0), (2, 4, 5.0)],
    "mapping_items": [2, 4],
}


def chunk_module():
    return importlib.import_module("batch.evaluation.chunked")


class EvidenceWriter:
    def __init__(self, *, reuse=False, fail_at=None):
        self.reuse = reuse
        self.fail_at = fail_at
        self.chunk_ids = []
        self.candidates = []
        self.per_user = []
        self.frames = []

    def __call__(self, *, chunk_id, candidates, per_user):
        self.frames.extend((candidates, per_user))
        if chunk_id == self.fail_at:
            raise RuntimeError("injected chunk evidence failure")
        if self.reuse:
            candidates.agg(F.sum("score")).collect()
            candidates.count()
            per_user.agg(F.sum("recall_at_10"), F.sum("ndcg_at_10")).collect()
            per_user.count()
        self.chunk_ids.append(chunk_id)
        self.candidates.extend(
            row.asDict(recursive=True)
            for row in candidates.select(*CANDIDATE_COLUMNS).collect()
        )
        self.per_user.extend(
            row.asDict(recursive=True) for row in per_user.select(*PER_USER_COLUMNS).collect()
        )


def inputs(
    spark,
    *,
    model=None,
    known_rows=None,
    holdout_rows=None,
    mapping_items=None,
):
    model = model or ScoredModel(spark, users=(1, 2, 3), items=range(1, 7))
    known = ratings(
        spark,
        known_rows
        if known_rows is not None
        else [(1, 1, 0.5), (2, 1, 1.0), (2, 2, 5.0)]
        + [(3, item, 1.0) for item in range(1, 7)],
    )
    holdout = ratings(
        spark,
        holdout_rows
        if holdout_rows is not None
        else [
            (1, 2, 5.0),
            (1, 3, 5.0),
            (1, 99, 5.0),
            (2, 4, 5.0),
            (2, 5, 3.0),
            (3, 100, 3.0),
            (4, 2, 5.0),
        ],
    )
    return model, known, holdout, mapping(
        spark, mapping_items if mapping_items is not None else [2, 4, 5, 99]
    )


def evaluate_chunked(spark, chunk_size, *, writer=None, model=None, input_kwargs=None):
    writer = writer or EvidenceWriter()
    result = chunk_module().evaluate_offline_chunked(
        *inputs(spark, model=model, **(input_kwargs or {})),
        stage="validation",
        candidate_size=3,
        positive_rating_threshold=4.0,
        model_version="fixture-v1",
        user_chunk_size=chunk_size,
        seed=42,
        evidence_writer=writer,
    )
    return result, writer


def rows(values, columns):
    return sorted(
        (tuple(value[column] for column in columns) for value in values),
        key=lambda row: tuple("" if value is None else value for value in row),
    )


def assert_summary(actual, expected):
    assert actual.keys() == expected.keys()
    for catalog, expected_catalog in expected.items():
        assert actual[catalog].keys() == expected_catalog.keys()
        for key, value in expected_catalog.items():
            if isinstance(value, float):
                assert actual[catalog][key] == pytest.approx(value)
            else:
                assert actual[catalog][key] == value


@pytest.fixture(scope="module")
def equivalence_model(spark):
    return ScoredModel(spark, users=(1, 2), items=range(1, 4))


@pytest.fixture(scope="module")
def legacy(spark, equivalence_model):
    result = importlib.import_module("batch.evaluation.offline").evaluate_offline(
        *inputs(spark, model=equivalence_model, **EQUIVALENCE_INPUTS),
        stage="validation",
        candidate_size=3,
        positive_rating_threshold=4.0,
        model_version="fixture-v1",
    )
    return {
        "candidates": [row.asDict(recursive=True) for row in result.candidates.collect()],
        "per_user": [row.asDict(recursive=True) for row in result.per_user.collect()],
        "summary": result.summary,
    }


@pytest.mark.parametrize("chunk_size", [1, 2, 99])
def test_chunk_sizes_match_legacy_candidates_per_user_and_summary(
    spark, equivalence_model, legacy, chunk_size
):
    result, written = evaluate_chunked(
        spark,
        chunk_size,
        model=equivalence_model,
        input_kwargs=EQUIVALENCE_INPUTS,
    )

    assert rows(written.candidates, CANDIDATE_COLUMNS) == rows(
        legacy["candidates"], CANDIDATE_COLUMNS
    )
    assert rows(written.per_user, PER_USER_COLUMNS) == rows(
        legacy["per_user"], PER_USER_COLUMNS
    )
    assert_summary(result.summary, legacy["summary"])


def test_chunk_assignment_is_complete_unique_bounded_and_reproducible(spark):
    users = spark.createDataFrame([(4,), (1,), (3,), (2,), (2,), (1,)], f"{USER} int")
    reversed_users = spark.createDataFrame([(1,), (2,), (2,), (3,), (1,), (4,)], f"{USER} int")

    first = chunk_module().assign_user_chunks(users.repartition(3), user_chunk_size=2, seed=42)
    second = chunk_module().assign_user_chunks(
        reversed_users.repartition(2), user_chunk_size=2, seed=42
    )

    assert sorted((row[USER], row.chunk_id) for row in first.collect()) == sorted(
        (row[USER], row.chunk_id) for row in second.collect()
    )
    counts = first.groupBy("chunk_id").count().orderBy("chunk_id").collect()
    assert first.select(USER).distinct().count() == 4
    assert [row.chunk_id for row in counts] == list(range(len(counts)))
    assert all(1 <= row["count"] <= 2 for row in counts)


def test_chunk_preserves_tie_order_known_exclusion_and_service_boundary(spark):
    model = ScoredModel(
        spark,
        users=(1, 2),
        items=range(1, 7),
        scores=[(user, item, 1.0) for user in (1, 2) for item in range(1, 7)],
    )
    writer = EvidenceWriter()
    chunk_module().evaluate_offline_chunked(
        model,
        ratings(spark, [(1, 1, 0.5), (2, 1, 5.0), (2, 2, 1.0)]),
        ratings(spark, [(1, 6, 5.0), (2, 6, 5.0)]),
        mapping(spark, [2, 4, 5, 6]),
        stage="validation",
        candidate_size=3,
        user_chunk_size=1,
        seed=42,
        evidence_writer=writer,
    )
    actual = {}
    for row in writer.candidates:
        actual.setdefault((row[USER], row["catalog"]), []).append(row)
    actual = {
        key: [row[ITEM] for row in sorted(values, key=lambda row: row["rank"])]
        for key, values in actual.items()
    }
    assert actual == {
        (1, "movielens"): [2, 3, 4],
        (1, "service"): [2, 4, 5],
        (2, "movielens"): [3, 4, 5],
        (2, "service"): [4, 5, 6],
    }
    assert all(
        row["service_movie_id"] > 2**31
        for row in writer.candidates
        if row["catalog"] == "service"
    )


def test_chunk_summary_uses_catalog_eligible_user_denominators(spark):
    summary = evaluate_chunked(spark, 3)[0].summary
    assert summary["movielens"]["recall_at_10"] == pytest.approx(5 / 9)
    assert summary["service"]["recall_at_10"] == pytest.approx(0.5)
    assert summary["movielens"]["ndcg_at_10"] == pytest.approx(
        (
            (1 + 1 / math.log2(3))
            / (1 + 1 / math.log2(3) + 1 / math.log2(4))
            + 1 / math.log2(3)
        )
        / 3
    )
    fields = (
        "target_users",
        "evaluated_users",
        "users_without_positives",
        "cold_users",
        "cold_items",
        "cold_ratings",
        "cold_positive_items",
        "mapping_excluded_positive_items",
        "zero_candidate_users",
        "fewer_than_k_users",
        "fewer_than_n_users",
        "evaluated_zero_candidate_users",
        "evaluated_fewer_than_k_users",
        "evaluated_fewer_than_n_users",
    )
    assert tuple(summary["movielens"][field] for field in fields) == (
        4, 3, 1, 1, 2, 3, 2, 0, 2, 4, 2, 1, 3, 1
    )
    assert tuple(summary["service"][field] for field in fields) == (
        4, 3, 1, 1, 2, 3, 2, 1, 2, 4, 3, 1, 3, 2
    )


class CountingModel(ScoredModel):
    def __init__(self, spark, score_calls):
        super().__init__(spark, users=(1, 2), items=range(1, 5))
        self.score_calls = score_calls

    def transform(self, frame):
        score_calls = self.score_calls

        @F.udf(returnType=DoubleType())
        def score(item):
            score_calls.add(1)
            return float(100 - item)

        return frame.withColumn("prediction", score(F.col(ITEM)))


def test_chunk_lineage_runs_once_and_owned_caches_are_released(spark):
    score_calls = spark.sparkContext.accumulator(0)
    writer = EvidenceWriter(reuse=True)
    persistent_before = spark.sparkContext._jsc.getPersistentRDDs().size()  # noqa: SLF001
    evaluate_chunked(
        spark,
        1,
        writer=writer,
        model=CountingModel(spark, score_calls),
        input_kwargs=LINEAGE_INPUTS,
    )

    # Five eligible pairs exist: user 1 has three and user 2 has two. Repeated
    # summary/evidence actions must consume the materialization, not rescore them.
    assert score_calls.value == 5
    assert writer.frames and all(not frame.is_cached for frame in writer.frames)
    assert spark.sparkContext._jsc.getPersistentRDDs().size() == persistent_before  # noqa: SLF001


def test_candidate_materialization_does_not_mutate_model_test_hooks(spark):
    class ForbiddenCounter:
        def add(self, _value):
            raise AssertionError("candidate generation must not mutate model.accumulator")

    model = ScoredModel(spark, users=(1, 2), items=range(1, 5))
    model.accumulator = ForbiddenCounter()

    evaluate_chunked(spark, 1, model=model, input_kwargs=EQUIVALENCE_INPUTS)


def test_chunk_requests_one_materialized_per_user_lineage_per_catalog(spark, monkeypatch):
    module = chunk_module()
    original = module.evaluate_ranking
    calls = []

    def observed(*args, materialize_per_user=False, **kwargs):
        calls.append(materialize_per_user)
        assert materialize_per_user is True
        return original(*args, **kwargs)

    monkeypatch.setattr(module, "evaluate_ranking", observed)
    evaluate_chunked(
        spark,
        99,
        writer=EvidenceWriter(reuse=True),
        input_kwargs=EQUIVALENCE_INPUTS,
    )

    assert calls == [True, True]


@pytest.mark.parametrize("holdout_rows", [[], [(9, 1, 5.0)]])
def test_empty_or_all_cold_input_still_rejects_duplicate_matched_mapping(
    spark, holdout_rows
):
    movie_map = mapping(spark, [1])
    duplicate = spark.createDataFrame(
        [(2**35, 3001, 1, "MATCHED", MAPPING_REVISION)], movie_map.schema
    )

    with pytest.raises(ValueError, match="(?i)(duplicate|ambiguous|multiple)"):
        chunk_module().evaluate_offline_chunked(
            ScoredModel(spark, users=(1,), items=(1,)),
            ratings(spark, []),
            ratings(spark, holdout_rows),
            movie_map.unionByName(duplicate),
            stage="validation",
            candidate_size=3,
            user_chunk_size=1,
            seed=42,
            evidence_writer=EvidenceWriter(),
        )


def test_empty_input_writes_one_schema_correct_empty_evidence_chunk(spark):
    writer = EvidenceWriter()
    model = ScoredModel(spark, users=(1,), items=(1,))
    result = chunk_module().evaluate_offline_chunked(
        model,
        ratings(spark, []),
        ratings(spark, []),
        mapping(spark, [1]),
        stage="validation",
        candidate_size=3,
        user_chunk_size=1,
        seed=42,
        evidence_writer=writer,
    )

    assert writer.chunk_ids == [0]
    assert len(writer.frames) == 2
    assert tuple(writer.frames[0].columns) == CANDIDATE_COLUMNS
    assert tuple(writer.frames[1].columns) == PER_USER_COLUMNS
    assert all(frame.count() == 0 for frame in writer.frames)
    assert all(summary["target_users"] == 0 for summary in result.summary.values())


@pytest.mark.parametrize("bad_score", [float("nan"), float("inf"), float("-inf"), None])
def test_invalid_score_keeps_candidate_chunk_context_and_releases_all_caches(
    spark, bad_score
):
    persistent_before = spark.sparkContext._jsc.getPersistentRDDs().size()  # noqa: SLF001
    model = ScoredModel(spark, users=(1,), items=(1,), scores=[(1, 1, bad_score)])

    with pytest.raises(ValueError, match="(?i)(score|prediction|finite|null)") as caught:
        chunk_module().evaluate_offline_chunked(
            model,
            ratings(spark, []),
            ratings(spark, [(1, 2, 5.0)]),
            mapping(spark, [1]),
            stage="validation",
            candidate_size=3,
            user_chunk_size=1,
            seed=42,
            evidence_writer=EvidenceWriter(),
        )

    assert (caught.value.stage, caught.value.chunk_id) == ("candidates", 0)
    assert spark.sparkContext._jsc.getPersistentRDDs().size() == persistent_before  # noqa: SLF001


def test_metric_failure_keeps_chunk_context_and_releases_all_caches(spark, monkeypatch):
    persistent_before = spark.sparkContext._jsc.getPersistentRDDs().size()  # noqa: SLF001
    injected = RuntimeError("injected metric failure")

    def fail_metrics(*_args, **_kwargs):
        raise injected

    monkeypatch.setattr(chunk_module(), "evaluate_ranking", fail_metrics)
    with pytest.raises(RuntimeError, match="injected metric failure") as caught:
        evaluate_chunked(spark, 1)

    assert caught.value is injected
    assert (caught.value.stage, caught.value.chunk_id) == ("metrics", 0)
    assert spark.sparkContext._jsc.getPersistentRDDs().size() == persistent_before  # noqa: SLF001


def test_chunk_failure_releases_owned_caches(spark):
    failed = EvidenceWriter(fail_at=0)
    persistent_before = spark.sparkContext._jsc.getPersistentRDDs().size()  # noqa: SLF001
    with pytest.raises(RuntimeError, match="injected chunk evidence failure") as caught:
        evaluate_chunked(
            spark,
            1,
            writer=failed,
            model=ScoredModel(spark, users=(1, 2), items=range(1, 4)),
            input_kwargs=EQUIVALENCE_INPUTS,
        )
    assert (getattr(caught.value, "stage", None), getattr(caught.value, "chunk_id", None)) == (
        "evidence",
        0,
    )
    assert failed.frames and all(not frame.is_cached for frame in failed.frames)
    assert spark.sparkContext._jsc.getPersistentRDDs().size() == persistent_before  # noqa: SLF001
