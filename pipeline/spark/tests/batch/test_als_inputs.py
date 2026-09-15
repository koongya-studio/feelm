from datetime import timezone
from pathlib import Path

import pytest
from pyspark.sql import SparkSession

from batch.train.inputs import (
    load_training_inputs,
    validate_ratings,
    validate_service_movie_map,
)
from tests.fixtures.als_baseline import (
    MAPPING_REVISION,
    RATING_ROWS,
    RATINGS_SCHEMA,
    SERVICE_MOVIE_MAP_ROWS,
    SERVICE_MOVIE_MAP_SCHEMA,
    write_als_input_fixture,
)


@pytest.fixture(scope="module")
def spark() -> SparkSession:
    session = (
        SparkSession.builder.master("local[1]")
        .appName("als-input-test")
        .config("spark.ui.enabled", "false")
        .config("spark.sql.shuffle.partitions", "1")
        .config("spark.sql.session.timeZone", "UTC")
        .getOrCreate()
    )
    session.sparkContext.setLogLevel("ERROR")
    yield session
    session.stop()


def test_loads_118_contract_and_preserves_unmapped_ratings(
    spark: SparkSession,
    tmp_path: Path,
) -> None:
    write_als_input_fixture(spark, tmp_path)

    inputs = load_training_inputs(
        spark,
        str(tmp_path / "ratings"),
        str(tmp_path / "service-movie-id-map"),
    )

    assert inputs.ratings_count == 60
    assert inputs.service_movie_count == 14
    assert inputs.matched_service_movie_count == 12
    assert inputs.mapping_revision == MAPPING_REVISION
    assert inputs.ratings.select("movielens_user_id").distinct().count() == 6
    assert inputs.ratings.filter("movielens_movie_id IN (13, 14)").count() > 0
    assert (
        inputs.service_movie_map.filter("mapping_status != 'MATCHED'")
        .filter("movielens_movie_id IS NOT NULL")
        .count()
        == 0
    )


def test_fixture_has_multiple_unique_ratings_and_unseen_candidates_per_user(
    spark: SparkSession,
) -> None:
    ratings = spark.createDataFrame(RATING_ROWS, RATINGS_SCHEMA)
    per_user = ratings.groupBy("movielens_user_id").count().collect()

    assert {row["count"] for row in per_user} == {10}
    assert ratings.select("movielens_user_id", "movielens_movie_id").distinct().count() == 60
    assert ratings.select("movielens_movie_id").distinct().count() == 14

    matched_movie_ids = set(range(1, 13))
    for user_id in range(1, 7):
        user_rows = [row for row in RATING_ROWS if row[0] == user_id]
        rated_movie_ids = {row[1] for row in user_rows}

        assert all(row[2] >= 4.0 for row in user_rows[-2:])
        assert matched_movie_ids - rated_movie_ids


def test_fixture_utc_timestamp_matches_epoch() -> None:
    for row in RATING_ROWS:
        rated_at_epoch = row[3]
        rated_at_utc = row[4]

        assert rated_at_utc.tzinfo is timezone.utc
        assert int(rated_at_utc.timestamp()) == rated_at_epoch


def test_rejects_duplicate_user_movie_ratings(spark: SparkSession) -> None:
    duplicate_ratings = spark.createDataFrame(
        [*RATING_ROWS, RATING_ROWS[0]],
        RATINGS_SCHEMA,
    )

    with pytest.raises(ValueError, match="duplicate keys"):
        validate_ratings(duplicate_ratings)


@pytest.mark.parametrize("invalid_rating", [4.2, 6.0])
def test_rejects_invalid_ratings(
    spark: SparkSession,
    invalid_rating: float,
) -> None:
    invalid_row = list(RATING_ROWS[0])
    invalid_row[2] = invalid_rating
    ratings = spark.createDataFrame(
        [tuple(invalid_row), *RATING_ROWS[1:]],
        RATINGS_SCHEMA,
    )

    with pytest.raises(ValueError, match="invalid rows"):
        validate_ratings(ratings)


def test_rejects_incompatible_118_schema(spark: SparkSession) -> None:
    wrong_schema = spark.createDataFrame(RATING_ROWS, RATINGS_SCHEMA).withColumnRenamed(
        "rated_at_epoch", "timestamp"
    )

    with pytest.raises(ValueError, match="missing required columns"):
        validate_ratings(wrong_schema)


def test_rejects_unknown_mapping_status(spark: SparkSession) -> None:
    invalid_row = (*SERVICE_MOVIE_MAP_ROWS[0][:-2], "UNKNOWN", MAPPING_REVISION)
    service_movie_map = spark.createDataFrame(
        [invalid_row, *SERVICE_MOVIE_MAP_ROWS[1:]],
        SERVICE_MOVIE_MAP_SCHEMA,
    )

    with pytest.raises(ValueError, match="invalid rows"):
        validate_service_movie_map(service_movie_map)


@pytest.mark.parametrize(
    ("row_index", "invalid_row"),
    [
        (0, (10_001, 20_001, None, "MATCHED", MAPPING_REVISION)),
        (-2, (10_901, 20_901, 13, "SERVICE_ONLY_CONTENT_BASED", MAPPING_REVISION)),
    ],
)
def test_rejects_mapping_status_id_mismatch(
    spark: SparkSession,
    row_index: int,
    invalid_row: tuple[int, int, int | None, str, str],
) -> None:
    rows = list(SERVICE_MOVIE_MAP_ROWS)
    rows[row_index] = invalid_row
    service_movie_map = spark.createDataFrame(rows, SERVICE_MOVIE_MAP_SCHEMA)

    with pytest.raises(ValueError, match="invalid rows"):
        validate_service_movie_map(service_movie_map)


@pytest.mark.parametrize(
    "revisions",
    [
        ["sha256:not-a-digest"] * len(SERVICE_MOVIE_MAP_ROWS),
        [MAPPING_REVISION] * (len(SERVICE_MOVIE_MAP_ROWS) - 1) + ["sha256:" + "c" * 64],
    ],
)
def test_rejects_invalid_or_mixed_mapping_revisions(spark: SparkSession, revisions) -> None:
    rows = [
        (*row[:-1], revision)
        for row, revision in zip(SERVICE_MOVIE_MAP_ROWS, revisions, strict=True)
    ]
    frame = spark.createDataFrame(rows, SERVICE_MOVIE_MAP_SCHEMA)

    with pytest.raises(ValueError, match="mapping revision"):
        validate_service_movie_map(frame)
