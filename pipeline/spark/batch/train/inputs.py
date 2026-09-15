"""Input contract validation for the ALS baseline batch."""

from __future__ import annotations

import re
from dataclasses import dataclass

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F
from pyspark.sql.types import (
    FloatType,
    IntegerType,
    LongType,
    StringType,
    TimestampType,
)

MATCHED = "MATCHED"
SERVICE_ONLY_CONTENT_BASED = "SERVICE_ONLY_CONTENT_BASED"
TMDB_ID_AMBIGUOUS = "TMDB_ID_AMBIGUOUS"
SERVICE_MAPPING_STATUSES = {
    MATCHED,
    SERVICE_ONLY_CONTENT_BASED,
    TMDB_ID_AMBIGUOUS,
}

RATINGS_SCHEMA = {
    "movielens_user_id": IntegerType,
    "movielens_movie_id": IntegerType,
    "rating": FloatType,
    "rated_at_epoch": LongType,
    "rated_at_utc": TimestampType,
}
SERVICE_MOVIE_MAP_SCHEMA = {
    "service_movie_id": LongType,
    "tmdb_id": IntegerType,
    "movielens_movie_id": IntegerType,
    "mapping_status": StringType,
    "mapping_revision": StringType,
}


@dataclass(frozen=True)
class TrainingInputs:
    """Validated data frames and counts consumed by ALS training."""

    ratings: DataFrame
    service_movie_map: DataFrame
    ratings_count: int
    service_movie_count: int
    matched_service_movie_count: int
    mapping_revision: str


def _validate_schema(
    frame: DataFrame,
    expected: dict[str, type],
    dataset_name: str,
) -> None:
    missing = sorted(set(expected) - set(frame.columns))
    if missing:
        raise ValueError(f"{dataset_name} is missing required columns: {missing}")

    wrong_types = []
    for column_name, expected_type in expected.items():
        actual_type = frame.schema[column_name].dataType
        if not isinstance(actual_type, expected_type):
            wrong_types.append(
                f"{column_name}={actual_type.simpleString()} "
                f"(expected {expected_type().simpleString()})"
            )
    if wrong_types:
        raise ValueError(f"{dataset_name} has incompatible column types: {', '.join(wrong_types)}")


def _assert_no_duplicate_key(
    frame: DataFrame,
    key_columns: list[str],
    dataset_name: str,
) -> None:
    duplicate_exists = (
        frame.groupBy(*key_columns).count().filter(F.col("count") > 1).limit(1).count()
    )
    if duplicate_exists:
        raise ValueError(f"{dataset_name} contains duplicate keys for columns: {key_columns}")


def validate_ratings(frame: DataFrame) -> tuple[DataFrame, int]:
    """Validate and select the S15P21E106-118 ratings output contract."""
    _validate_schema(frame, RATINGS_SCHEMA, "ratings")
    ratings = frame.select(*RATINGS_SCHEMA)
    summary = ratings.agg(
        F.count(F.lit(1)).alias("row_count"),
        F.sum(
            F.when(
                F.col("movielens_user_id").isNull()
                | (F.col("movielens_user_id") <= 0)
                | F.col("movielens_movie_id").isNull()
                | (F.col("movielens_movie_id") <= 0)
                | F.col("rating").isNull()
                | F.isnan("rating")
                | ~F.col("rating").between(0.5, 5.0)
                | (F.abs(F.col("rating") * 2 - F.round(F.col("rating") * 2)) > 0.000001)
                | F.col("rated_at_epoch").isNull()
                | (F.col("rated_at_epoch") < 0)
                | F.col("rated_at_utc").isNull(),
                1,
            ).otherwise(0)
        ).alias("invalid_count"),
    ).first()
    row_count = summary["row_count"]
    if row_count == 0:
        raise ValueError("ratings is empty")
    if summary["invalid_count"]:
        raise ValueError(f"ratings contains {summary['invalid_count']} invalid rows")

    _assert_no_duplicate_key(
        ratings,
        ["movielens_user_id", "movielens_movie_id"],
        "ratings",
    )
    return ratings, row_count


def validate_service_movie_map(frame: DataFrame) -> tuple[DataFrame, int, int, str]:
    """Validate and select the S15P21E106-118 service mapping contract."""
    _validate_schema(frame, SERVICE_MOVIE_MAP_SCHEMA, "service-movie-id-map")
    service_movie_map = frame.select(*SERVICE_MOVIE_MAP_SCHEMA)
    invalid_status = ~F.col("mapping_status").isin(sorted(SERVICE_MAPPING_STATUSES))
    invalid_mapping = (
        (F.col("mapping_status") == MATCHED)
        & (F.col("movielens_movie_id").isNull() | (F.col("movielens_movie_id") <= 0))
    ) | ((F.col("mapping_status") != MATCHED) & F.col("movielens_movie_id").isNotNull())
    summary = service_movie_map.agg(
        F.count(F.lit(1)).alias("row_count"),
        F.sum(F.when(F.col("mapping_status") == MATCHED, 1).otherwise(0)).alias("matched_count"),
        F.sum(
            F.when(
                F.col("service_movie_id").isNull()
                | (F.col("service_movie_id") <= 0)
                | F.col("tmdb_id").isNull()
                | (F.col("tmdb_id") <= 0)
                | F.col("mapping_status").isNull()
                | invalid_status
                | invalid_mapping,
                1,
            ).otherwise(0)
        ).alias("invalid_count"),
    ).first()
    row_count = summary["row_count"]
    matched_count = summary["matched_count"]
    if row_count == 0:
        raise ValueError("service-movie-id-map is empty")
    if summary["invalid_count"]:
        raise ValueError(f"service-movie-id-map contains {summary['invalid_count']} invalid rows")
    if matched_count == 0:
        raise ValueError("service-movie-id-map contains no MATCHED movies")
    revisions = [
        row["mapping_revision"]
        for row in service_movie_map.select("mapping_revision").distinct().collect()
    ]
    if len(revisions) != 1 or not re.fullmatch(r"sha256:[0-9a-f]{64}", revisions[0] or ""):
        raise ValueError("service-movie-id-map requires one sha256 mapping revision")

    _assert_no_duplicate_key(
        service_movie_map,
        ["service_movie_id"],
        "service-movie-id-map",
    )
    _assert_no_duplicate_key(
        service_movie_map,
        ["tmdb_id"],
        "service-movie-id-map",
    )
    return service_movie_map, row_count, matched_count, revisions[0]


def load_training_inputs(
    spark: SparkSession,
    ratings_uri: str,
    service_movie_map_uri: str,
) -> TrainingInputs:
    """Read and validate both Parquet inputs required by ALS training."""
    ratings, ratings_count = validate_ratings(spark.read.parquet(ratings_uri))
    service_movie_map, service_movie_count, matched_service_movie_count, mapping_revision = (
        validate_service_movie_map(spark.read.parquet(service_movie_map_uri))
    )
    return TrainingInputs(
        ratings=ratings,
        service_movie_map=service_movie_map,
        ratings_count=ratings_count,
        service_movie_count=service_movie_count,
        matched_service_movie_count=matched_service_movie_count,
        mapping_revision=mapping_revision,
    )
