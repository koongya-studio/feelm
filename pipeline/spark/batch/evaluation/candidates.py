"""Deterministic offline ALS candidate generation."""

from __future__ import annotations

from pyspark.sql import DataFrame
from pyspark.sql import functions as F
from pyspark.sql.window import Window

USER = "movielens_user_id"
ITEM = "movielens_movie_id"
MATCHED = "MATCHED"


def _assert_unique_matched_movies(service_movie_map: DataFrame) -> DataFrame:
    matched = service_movie_map.filter(F.col("mapping_status") == MATCHED).select(
        F.col(ITEM), F.col("service_movie_id").cast("long").alias("service_movie_id")
    )
    duplicate = matched.groupBy(ITEM).count().filter(F.col("count") > 1).limit(1).count()
    if duplicate:
        raise ValueError("service movie mapping contains multiple MATCHED rows for one movie")
    return matched


def _assert_finite_scores(scored: DataFrame) -> None:
    score = F.col("score")
    invalid = scored.filter(
        score.isNull() | F.isnan(score) | (F.abs(score) == F.lit(float("inf")))
    ).limit(1)
    if invalid.count():
        raise ValueError("model prediction score must be finite and non-null")


def _rank(scored: DataFrame, candidate_size: int) -> DataFrame:
    order = Window.partitionBy(USER).orderBy(F.col("score").desc(), F.col(ITEM).asc())
    return scored.withColumn("rank", F.row_number().over(order)).filter(
        F.col("rank") <= candidate_size
    )


def generate_candidates(
    model,
    users: DataFrame,
    known_ratings: DataFrame,
    service_movie_map: DataFrame,
    candidate_size: int = 500,
) -> DataFrame:
    """Generate independent MovieLens and MATCHED-service Top-N rankings.

    Only users and items with factors are scored. Every known user-item pair is
    excluded regardless of rating; holdout data is deliberately not accepted.
    """
    if candidate_size <= 0:
        raise ValueError("candidate_size must be positive")

    user_col = model.getUserCol()
    item_col = model.getItemCol()
    prediction_col = model.getPredictionCol()
    if user_col != USER or item_col != ITEM:
        raise ValueError("ALS model must use MovieLens user and movie ID columns")

    matched = _assert_unique_matched_movies(service_movie_map)
    factor_users = model.userFactors.select(F.col("id").cast("int").alias(USER))
    factor_items = model.itemFactors.select(F.col("id").cast("int").alias(ITEM))
    requested_users = users.select(USER).distinct().join(factor_users, USER, "inner")
    known = known_ratings.select(USER, ITEM).distinct()
    eligible = requested_users.crossJoin(factor_items).join(known, [USER, ITEM], "left_anti")
    scored = model.transform(eligible).select(
        USER, ITEM, F.col(prediction_col).alias("score")
    )
    _assert_finite_scores(scored)

    movielens = (
        _rank(scored, candidate_size)
        .withColumn("catalog", F.lit("movielens"))
        .withColumn("service_movie_id", F.lit(None).cast("long"))
    )
    service = (
        _rank(scored.join(matched, ITEM, "inner"), candidate_size)
        .withColumn("catalog", F.lit("service"))
    )
    columns = [USER, ITEM, "score", "rank", "catalog", "service_movie_id"]
    return movielens.select(*columns).unionByName(service.select(*columns))
