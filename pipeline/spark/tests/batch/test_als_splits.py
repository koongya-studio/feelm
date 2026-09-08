import pytest
from pyspark.sql import SparkSession

from batch.train.inputs import validate_ratings
from batch.train.splits import split_ratings, summarize_splits
from tests.fixtures.als_baseline import RATING_ROWS, RATINGS_SCHEMA


@pytest.fixture(scope="module")
def spark():
    session = (
        SparkSession.builder.master("local[2]")
        .appName("als-split-test")
        .config("spark.ui.enabled", "false")
        .config("spark.sql.shuffle.partitions", "2")
        .config("spark.sql.session.timeZone", "UTC")
        .getOrCreate()
    )
    session.sparkContext.setLogLevel("ERROR")
    yield session
    session.stop()


SCHEMA = "movielens_user_id int, movielens_movie_id int, rating float, rated_at_epoch long"


def history(user, size):
    return [(user, i + 1, 4.5, 1_700_000_000 + i) for i in range(size)]


def assignments(result):
    return {
        (r.movielens_user_id, r.movielens_movie_id): r.split for r in result.assignments.collect()
    }


def test_counts_row_preservation_and_chronology(spark):
    expected = {
        1: (1, 0, 0),
        2: (2, 0, 0),
        3: (1, 1, 1),
        9: (7, 1, 1),
        10: (8, 1, 1),
        11: (8, 1, 2),
        20: (16, 2, 2),
    }
    rows = [row for n in expected for row in history(n, n)]
    frame = spark.createDataFrame(rows, SCHEMA)
    result = split_ratings(frame)
    actual = result.assignments.collect()
    assert len(actual) == len(rows)
    assert {tuple(r[:-1]) for r in actual} == set(rows)
    assert len({(r.movielens_user_id, r.movielens_movie_id) for r in actual}) == len(rows)
    for user, counts in expected.items():
        ordered = sorted(
            (r for r in actual if r.movielens_user_id == user), key=lambda r: r.rated_at_epoch
        )
        assert [r.split for r in ordered] == [
            name
            for name, count in zip(("train", "validation", "test"), counts, strict=True)
            for _ in range(count)
        ]
    for subset in (result.train, result.validation, result.test):
        assert subset.schema == frame.schema


def test_splits_validated_267_fixture_and_preserves_contract(spark):
    fixture = spark.createDataFrame(RATING_ROWS, RATINGS_SCHEMA)
    validated, ratings_count = validate_ratings(fixture)

    result = split_ratings(validated)
    counts = {
        row["split"]: row["count"] for row in result.assignments.groupBy("split").count().collect()
    }

    assert ratings_count == 60
    assert counts == {"train": 48, "validation": 6, "test": 6}
    assert result.assignments.drop("split").schema == validated.schema


def test_ties_are_stable_across_input_order_and_partition_count(spark):
    rows = [(1, movie, 4.5, 100) for movie in range(1, 21)]
    first = split_ratings(spark.createDataFrame(rows, SCHEMA).repartition(2))
    second = split_ratings(spark.createDataFrame(rows[::-1], SCHEMA).repartition(5))
    assert assignments(first) == assignments(second)
    different_seed = split_ratings(spark.createDataFrame(rows, SCHEMA), seed=7)
    assert assignments(first) != assignments(different_seed)


def test_seed_and_rating_values_do_not_change_distinct_time_order(spark):
    rows = history(1, 10)
    original = split_ratings(spark.createDataFrame(rows, SCHEMA))
    changed = [(u, m, 0.5, t) for u, m, _, t in rows]
    other = split_ratings(spark.createDataFrame(changed, SCHEMA), seed=7)
    assert assignments(original) == assignments(other)


def test_custom_ratios_and_minimum_history(spark):
    frame = spark.createDataFrame(history(1, 10) + history(2, 9), SCHEMA)
    result = split_ratings(frame, train_ratio=0.7, validation_ratio=0.2, min_eval_ratings=10)
    actual = assignments(result)
    assert [actual[1, i] for i in range(1, 11)] == ["train"] * 7 + ["validation"] * 2 + ["test"]
    assert all(actual[2, i] == "train" for i in range(1, 10))


@pytest.mark.parametrize(
    ("history_size", "validation_ratio", "expected"),
    [
        (111, 0.036036036036036036, {"train": 88, "validation": 3, "test": 20}),
        (1657, 0.0024140012070006035, {"train": 1325, "validation": 3, "test": 329}),
    ],
)
def test_decimal_ratio_floor_does_not_round_up(spark, history_size, validation_ratio, expected):
    frame = spark.createDataFrame(history(1, history_size), SCHEMA)

    result = split_ratings(
        frame,
        train_ratio=0.8,
        validation_ratio=validation_ratio,
    )
    counts = {
        row["split"]: row["count"] for row in result.assignments.groupBy("split").count().collect()
    }

    assert counts == expected


def test_report_keeps_cold_start_movies_and_counts_sparse_users(spark):
    rows = history(1, 10) + history(2, 2) + [(3, 1, 4.5, 1), (3, 2, 4.5, 2), (3, 10, 4.5, 3)]
    result = split_ratings(spark.createDataFrame(rows, SCHEMA))
    result.assignments.cache()
    try:
        report = summarize_splits(result)
        assert report.total_ratings == 15
        assert report.train_only_users == 1
        assert report.by_split["train"].ratings == 11
        assert report.by_split["train"].users == 3
        assert report.by_split["train"].movies == 8
        assert report.by_split["validation"].movies_not_in_train == 1
        assert report.by_split["validation"].cold_start_ratings == 1
        assert report.by_split["test"].ratings == 2
        assert report.by_split["test"].movies == 1
        assert report.by_split["test"].movies_not_in_train == 1
        assert report.by_split["test"].cold_start_ratings == 2
        assert all(c.users_not_in_train == 0 for c in report.by_split.values())
    finally:
        result.assignments.unpersist()


def test_empty_input_has_three_empty_splits_and_zero_report(spark):
    result = split_ratings(spark.createDataFrame([], SCHEMA))
    report = summarize_splits(result)
    assert report.total_ratings == report.train_only_users == 0
    assert set(report.by_split) == {"train", "validation", "test"}
    assert all(
        c.ratings == c.users == c.movies == c.cold_start_ratings == 0
        for c in report.by_split.values()
    )


@pytest.mark.parametrize(
    "options",
    [
        {"train_ratio": 0},
        {"validation_ratio": -0.1},
        {"train_ratio": float("nan")},
        {"validation_ratio": float("inf")},
        {"train_ratio": 0.9, "validation_ratio": 0.1},
        {"min_eval_ratings": 2},
        {"min_eval_ratings": 3.5},
        {"seed": 2**63},
    ],
)
def test_invalid_configuration_rejected_before_spark_jobs(spark, options):
    with pytest.raises(ValueError):
        split_ratings(spark.createDataFrame([], SCHEMA), **options)


def test_missing_and_reserved_columns_are_rejected(spark):
    frame = spark.createDataFrame([], SCHEMA)
    with pytest.raises(ValueError, match="missing split columns"):
        split_ratings(frame.drop("rated_at_epoch"))
    with pytest.raises(ValueError, match="reserved split columns"):
        split_ratings(frame.withColumnRenamed("rating", "split"))
