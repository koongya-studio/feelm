"""Small, explicit oracles for 270; no production evaluation logic is copied here."""

from datetime import datetime, timezone

import pytest
from pyspark.sql import SparkSession
from pyspark.sql import functions as F

from tests.fixtures.als_baseline import (
    MAPPING_REVISION,
    RATINGS_SCHEMA,
    SERVICE_MOVIE_MAP_SCHEMA,
)

USER = "movielens_user_id"
ITEM = "movielens_movie_id"
CANDIDATE_SCHEMA = (
    f"{USER} int, {ITEM} int, score double, rank int, catalog string, service_movie_id long"
)


@pytest.fixture(scope="module")
def spark():
    session = (
        SparkSession.builder.master("local[2]")
        .appName("als-evaluation-test")
        .config("spark.ui.enabled", "false")
        .config("spark.sql.shuffle.partitions", "2")
        .getOrCreate()
    )
    session.sparkContext.setLogLevel("ERROR")
    yield session
    session.stop()


def ratings(spark, rows):
    return spark.createDataFrame(
        [
            (u, i, float(r), 1_700_000_000, datetime(2023, 11, 14, tzinfo=timezone.utc))
            for u, i, r in rows
        ],
        RATINGS_SCHEMA,
    )


def mapping(spark, items):
    # Deliberately outside int32; a cast to int must fail the assertions.
    return spark.createDataFrame(
        [(2**33 + i, 1000 + i, i, "MATCHED", MAPPING_REVISION) for i in items]
        + [
            (2**34, 2000, None, "SERVICE_ONLY_CONTENT_BASED", MAPPING_REVISION),
            (2**34 + 1, 2001, None, "TMDB_ID_AMBIGUOUS", MAPPING_REVISION),
        ],
        SERVICE_MOVIE_MAP_SCHEMA,
    )


def candidates(spark, rows, catalog="movielens"):
    return spark.createDataFrame(
        [
            (u, i, score, rank, catalog, 2**33 + i if catalog == "service" else None)
            for u, i, score, rank in rows
        ],
        CANDIDATE_SCHEMA,
    )


class ScoredModel:
    """Model double only: score lookup uses Spark joins, never ranks or filters known IDs.

    The integration tests separately exercise a real ALSModel. Unknown IDs produce
    null predictions so silently scoring cold users cannot look like a correct result.
    """

    uid = "synthetic-model-270"

    def __init__(self, spark, users=(1,), items=None, scores=None):
        if items is None:
            items = range(1, 16)
        self.userFactors = spark.createDataFrame(
            [(u, [1.0]) for u in users], "id int, features array<float>"
        )
        self.itemFactors = spark.createDataFrame(
            [(i, [1.0]) for i in items], "id int, features array<float>"
        )
        if scores is None:
            scores = [(u, i, float(100 - i)) for u in users for i in items]
        self.scores = spark.createDataFrame(scores, f"{USER} int, {ITEM} int, prediction double")
        self.strategy = "nan"

    def getUserCol(self):
        return USER

    def getItemCol(self):
        return ITEM

    def getPredictionCol(self):
        return "prediction"

    def getColdStartStrategy(self):
        return self.strategy

    def setColdStartStrategy(self, strategy):
        raise AssertionError("candidate generation must not mutate the supplied model")

    def transform(self, frame):
        return frame.join(self.scores, [USER, ITEM], "left")


def ordered(frame, catalog="movielens", user=1):
    return (
        frame.filter((F.col("catalog") == catalog) & (F.col(USER) == user))
        .orderBy("rank")
        .collect()
    )
