"""Recall@K and binary NDCG@K for offline recommendation evaluation."""

from __future__ import annotations

from dataclasses import dataclass

from pyspark.sql import DataFrame
from pyspark.sql import functions as F
from pyspark.sql.window import Window

USER = "movielens_user_id"
ITEM = "movielens_movie_id"


@dataclass(frozen=True)
class RankingEvaluation:
    per_user: DataFrame
    summary: dict[str, int | float | None]


def _finite_score_filter(frame: DataFrame):
    score = F.col("score")
    return score.isNull() | F.isnan(score) | (F.abs(score) == F.lit(float("inf")))


def evaluate_ranking(
    candidates: DataFrame,
    holdout_ratings: DataFrame,
    users: DataFrame,
    *,
    catalog: str,
    positive_rating_threshold: float = 4.0,
    k: int = 10,
    materialize_per_user: bool = False,
) -> RankingEvaluation:
    """Evaluate a catalog ranking, retaining empty recommendations as zero scores.

    When ``materialize_per_user`` is true, the returned per-user frame is a
    caller-owned cache. Failures inside this function release that cache.
    """
    if k <= 0:
        raise ValueError("k must be positive")

    target_users = users.select(USER).distinct()
    recommendations = candidates.filter(F.col("catalog") == catalog).join(
        target_users, USER, "inner"
    )
    if recommendations.filter(_finite_score_filter(recommendations)).limit(1).count():
        raise ValueError("recommendation score must be finite and non-null")

    recommendation_count = recommendations.count()
    dedup_window = Window.partitionBy(USER, ITEM).orderBy(F.col("score").desc())
    deduplicated = (
        recommendations.withColumn("_duplicate_rank", F.row_number().over(dedup_window))
        .filter(F.col("_duplicate_rank") == 1)
        .drop("_duplicate_rank", "rank")
    )
    rank_window = Window.partitionBy(USER).orderBy(F.col("score").desc(), F.col(ITEM).asc())
    ranked = deduplicated.withColumn("rank", F.row_number().over(rank_window))
    deduplicated_count = ranked.count()

    positives = (
        holdout_ratings.filter(F.col("rating") >= positive_rating_threshold)
        .select(USER, ITEM)
        .distinct()
        .join(target_users, USER, "inner")
    )
    positive_counts = positives.groupBy(USER).agg(F.count(F.lit(1)).alias("positive_count"))
    eligible = target_users.join(positive_counts, USER, "inner")

    hits = (
        ranked.filter(F.col("rank") <= k)
        .join(positives.withColumn("_positive", F.lit(1)), [USER, ITEM], "inner")
        .groupBy(USER)
        .agg(
            F.count(F.lit(1)).alias("hit_count"),
            F.sum(F.lit(1.0) / F.log2(F.col("rank") + F.lit(1))).alias("dcg"),
        )
    )
    scored = (
        eligible.join(hits, USER, "left")
        .fillna({"hit_count": 0, "dcg": 0.0})
        .withColumn("recall_at_10", F.col("hit_count") / F.col("positive_count"))
        .withColumn(
            "idcg",
            F.aggregate(
                F.sequence(F.lit(1), F.least(F.lit(k), F.col("positive_count"))),
                F.lit(0.0),
                lambda total, rank: total + F.lit(1.0) / F.log2(rank + F.lit(1)),
            ),
        )
        .withColumn("ndcg_at_10", F.col("dcg") / F.col("idcg"))
        .select(USER, "recall_at_10", "ndcg_at_10")
    )

    if materialize_per_user:
        scored = scored.persist()

    try:
        population = target_users.agg(F.count(F.lit(1)).alias("target_users")).first()
        positive_summary = positive_counts.agg(
            F.count(F.lit(1)).alias("evaluated_users"),
            F.coalesce(F.sum("positive_count"), F.lit(0)).alias("positive_items"),
        ).first()
        if materialize_per_user:
            scored.count()
        averages = scored.agg(
            F.avg("recall_at_10").alias("recall_at_10"),
            F.avg("ndcg_at_10").alias("ndcg_at_10"),
        ).first()
        evaluated_users = positive_summary["evaluated_users"]
        summary = {
            "target_users": population["target_users"],
            "evaluated_users": evaluated_users,
            "users_without_positives": population["target_users"] - evaluated_users,
            "positive_items": positive_summary["positive_items"],
            "recall_at_10": averages["recall_at_10"],
            "ndcg_at_10": averages["ndcg_at_10"],
            "duplicate_recommendations_removed": recommendation_count - deduplicated_count,
        }
        return RankingEvaluation(per_user=scored, summary=summary)
    except Exception:
        if materialize_per_user:
            scored.unpersist()
        raise
