"""Deterministic ALS input data matching the S15P21E106-118 Parquet contract."""

from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

from pyspark.sql import SparkSession
from pyspark.sql.types import (
    FloatType,
    IntegerType,
    LongType,
    StringType,
    StructField,
    StructType,
    TimestampType,
)

RATINGS_SCHEMA = StructType(
    [
        StructField("movielens_user_id", IntegerType(), False),
        StructField("movielens_movie_id", IntegerType(), False),
        StructField("rating", FloatType(), False),
        StructField("rated_at_epoch", LongType(), False),
        StructField("rated_at_utc", TimestampType(), False),
    ]
)
SERVICE_MOVIE_MAP_SCHEMA = StructType(
    [
        StructField("service_movie_id", LongType(), False),
        StructField("tmdb_id", IntegerType(), False),
        StructField("movielens_movie_id", IntegerType(), True),
        StructField("mapping_status", StringType(), False),
        StructField("mapping_revision", StringType(), False),
    ]
)


def _rating_rows() -> list[tuple[int, int, float, int, datetime]]:
    rating_pattern = [4.5, 2.0, 3.5, 5.0, 1.5, 4.0, 3.0, 2.5, 4.5, 5.0]
    rows = []
    for user_id in range(1, 7):
        for offset, rating in enumerate(rating_pattern):
            movie_id = ((user_id * 2 + offset - 1) % 14) + 1
            epoch = 1_700_000_000 + user_id * 1_000 + offset
            rows.append(
                (
                    user_id,
                    movie_id,
                    rating,
                    epoch,
                    datetime.fromtimestamp(epoch, tz=timezone.utc),
                )
            )
    return rows


RATING_ROWS = _rating_rows()
MAPPING_REVISION = "sha256:" + "a" * 64
SERVICE_MOVIE_MAP_ROWS = [
    *[
        (10_000 + movie_id, 20_000 + movie_id, movie_id, "MATCHED", MAPPING_REVISION)
        for movie_id in range(1, 13)
    ],
    (10_901, 20_901, None, "SERVICE_ONLY_CONTENT_BASED", MAPPING_REVISION),
    (10_902, 20_902, None, "TMDB_ID_AMBIGUOUS", MAPPING_REVISION),
]


def write_als_input_fixture(spark: SparkSession, root: Path) -> None:
    """Write enough per-user history for future train/validation/test tests."""
    spark.createDataFrame(RATING_ROWS, RATINGS_SCHEMA).write.parquet(str(root / "ratings"))
    spark.createDataFrame(SERVICE_MOVIE_MAP_ROWS, SERVICE_MOVIE_MAP_SCHEMA).write.parquet(
        str(root / "service-movie-id-map")
    )
