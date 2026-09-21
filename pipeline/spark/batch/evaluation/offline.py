"""Reusable orchestration for leakage-safe offline ALS evaluation."""

from __future__ import annotations

from dataclasses import dataclass

from pyspark.sql import DataFrame
from pyspark.sql import functions as F

from batch.evaluation.candidates import ITEM, MATCHED, USER, generate_candidates
from batch.evaluation.metrics import evaluate_ranking

POLICY_VERSION = "als-offline-v1"
K = 10


@dataclass(frozen=True)
class OfflineEvaluation:
    candidates: DataFrame
    per_user: DataFrame
    summary: dict[str, dict[str, int | float | None]]
    metadata: dict[str, str | int | float | None]


def _diagnostic_counts(
    holdout_pairs: DataFrame,
    positives: DataFrame,
    factor_users: DataFrame,
    factor_items: DataFrame,
    matched_items: DataFrame,
) -> dict[str, int]:
    """Collect all holdout population diagnostics in one Spark action."""
    diagnostics = (
        holdout_pairs.join(
            factor_users.withColumn("_warm_user", F.lit(True)), USER, "left"
        )
        .join(factor_items.withColumn("_warm_item", F.lit(True)), ITEM, "left")
        .join(positives.withColumn("_positive", F.lit(True)), [USER, ITEM], "left")
        .join(matched_items.withColumn("_matched", F.lit(True)), ITEM, "left")
    )
    cold = F.col("_warm_user").isNull() | F.col("_warm_item").isNull()
    row = diagnostics.agg(
        F.countDistinct(USER).alias("target_users"),
        F.countDistinct(F.when(F.col("_warm_user").isNull(), F.col(USER))).alias(
            "cold_users"
        ),
        F.countDistinct(F.when(F.col("_warm_item").isNull(), F.col(ITEM))).alias(
            "cold_items"
        ),
        F.sum(F.when(cold, 1).otherwise(0)).alias("cold_ratings"),
        F.sum(F.when(cold & F.col("_positive"), 1).otherwise(0)).alias(
            "cold_positive_items"
        ),
        F.sum(
            F.when(F.col("_positive") & F.col("_matched").isNull(), 1).otherwise(0)
        ).alias("mapping_excluded_positive_items"),
    ).first()
    return {name: int(row[name] or 0) for name in row.asDict()}


def _shortage_counts(
    candidates: DataFrame,
    users: DataFrame,
    eligible_users: DataFrame,
    catalog: str,
    candidate_size: int,
) -> dict[str, int]:
    counts = candidates.filter(F.col("catalog") == catalog).groupBy(USER).agg(
        F.count(F.lit(1)).alias("candidate_count")
    )
    all_counts = users.join(counts, USER, "left").fillna({"candidate_count": 0})
    evaluated_counts = eligible_users.join(counts, USER, "left").fillna({"candidate_count": 0})

    def shortages(frame: DataFrame, population: str) -> DataFrame:
        return frame.agg(
            F.sum(F.when(F.col("candidate_count") == 0, 1).otherwise(0)).alias("zero"),
            F.sum(F.when(F.col("candidate_count") < K, 1).otherwise(0)).alias("under_k"),
            F.sum(
                F.when(F.col("candidate_count") < candidate_size, 1).otherwise(0)
            ).alias("under_n"),
        ).withColumn("population", F.lit(population))

    rows = {
        row["population"]: row
        for row in shortages(all_counts, "all")
        .unionByName(shortages(evaluated_counts, "evaluated"))
        .collect()
    }
    zero, under_k, under_n = (
        int(rows["all"][name] or 0) for name in ("zero", "under_k", "under_n")
    )
    eval_zero, eval_under_k, eval_under_n = (
        int(rows["evaluated"][name] or 0) for name in ("zero", "under_k", "under_n")
    )
    return {
        "zero_candidate_users": zero,
        "fewer_than_k_users": under_k,
        "fewer_than_n_users": under_n,
        "evaluated_zero_candidate_users": eval_zero,
        "evaluated_fewer_than_k_users": eval_under_k,
        "evaluated_fewer_than_n_users": eval_under_n,
    }


def evaluate_offline(
    model,
    known_ratings: DataFrame,
    holdout_ratings: DataFrame,
    service_movie_map: DataFrame,
    *,
    stage: str,
    candidate_size: int = 500,
    positive_rating_threshold: float = 4.0,
    model_version: str | None = None,
) -> OfflineEvaluation:
    """Generate and evaluate validation or test candidates without holdout leakage."""
    if stage not in {"validation", "test"}:
        raise ValueError("stage must be either validation or test")

    overlap = (
        known_ratings.select(USER, ITEM)
        .distinct()
        .join(holdout_ratings.select(USER, ITEM).distinct(), [USER, ITEM], "inner")
        .limit(1)
        .count()
    )
    if overlap:
        raise ValueError("known and holdout ratings overlap; evaluation would leak")

    users = holdout_ratings.select(USER).distinct()
    candidates = generate_candidates(
        model, users, known_ratings, service_movie_map, candidate_size=candidate_size
    )
    matched_items = (
        service_movie_map.filter(F.col("mapping_status") == MATCHED).select(ITEM).distinct()
    )
    catalog_truth = {
        "movielens": holdout_ratings,
        "service": holdout_ratings.join(matched_items, ITEM, "inner"),
    }

    factor_users = model.userFactors.select(F.col("id").cast("int").alias(USER)).distinct()
    factor_items = model.itemFactors.select(F.col("id").cast("int").alias(ITEM)).distinct()
    holdout_pairs = holdout_ratings.select(USER, ITEM).distinct()
    positives = (
        holdout_ratings.filter(F.col("rating") >= positive_rating_threshold)
        .select(USER, ITEM)
        .distinct()
    )
    diagnostics = _diagnostic_counts(
        holdout_pairs, positives, factor_users, factor_items, matched_items
    )

    summaries = {}
    per_user_frames = []
    for catalog, truth in catalog_truth.items():
        evaluation = evaluate_ranking(
            candidates,
            truth,
            users,
            catalog=catalog,
            positive_rating_threshold=positive_rating_threshold,
            k=K,
        )
        eligible_users = (
            truth.filter(F.col("rating") >= positive_rating_threshold).select(USER).distinct()
        )
        mapping_excluded = 0
        if catalog == "service":
            mapping_excluded = diagnostics["mapping_excluded_positive_items"]
        summaries[catalog] = {
            **evaluation.summary,
            "cold_users": diagnostics["cold_users"],
            "cold_items": diagnostics["cold_items"],
            "cold_ratings": diagnostics["cold_ratings"],
            "cold_positive_items": diagnostics["cold_positive_items"],
            "mapping_excluded_positive_items": mapping_excluded,
            **_shortage_counts(candidates, users, eligible_users, catalog, candidate_size),
        }
        per_user_frames.append(evaluation.per_user.withColumn("catalog", F.lit(catalog)))

    per_user = per_user_frames[0].unionByName(per_user_frames[1])
    metadata = {
        "model_uid": model.uid,
        "model_version": model_version,
        "stage": stage,
        "positive_rating_threshold": positive_rating_threshold,
        "candidate_size": candidate_size,
        "k": K,
        "policy_version": POLICY_VERSION,
    }
    return OfflineEvaluation(candidates, per_user, summaries, metadata)
