"""Exact offline evaluation over deterministic, bounded user chunks."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

from pyspark.sql import DataFrame
from pyspark.sql import functions as F
from pyspark.sql.window import Window

from batch.evaluation.candidates import (
    ITEM,
    USER,
    _assert_unique_matched_movies,
    generate_materialized_candidates,
)
from batch.evaluation.metrics import evaluate_ranking
from batch.evaluation.offline import (
    POLICY_VERSION,
    K,
    _diagnostic_counts,
    _shortage_counts,
)


@dataclass(frozen=True)
class ChunkedOfflineEvaluation:
    summary: dict[str, dict[str, int | float | None]]
    metadata: dict[str, str | int | float | None]


EvidenceWriter = Callable[..., None]


def assign_user_chunks(
    users: DataFrame, *, user_chunk_size: int, seed: int
) -> DataFrame:
    """Assign each distinct user to a stable, contiguous, size-bounded chunk."""
    if user_chunk_size <= 0:
        raise ValueError("user_chunk_size must be positive")

    order = Window.orderBy(F.xxhash64(F.lit(seed), F.col(USER)), F.col(USER))
    return (
        users.select(USER)
        .distinct()
        .withColumn("_user_row", F.row_number().over(order) - F.lit(1))
        .withColumn(
            "chunk_id",
            F.floor(F.col("_user_row") / F.lit(user_chunk_size)).cast("long"),
        )
        .drop("_user_row")
    )


def evaluate_offline_chunked(
    model,
    known_ratings: DataFrame,
    holdout_ratings: DataFrame,
    service_movie_map: DataFrame,
    *,
    stage: str,
    user_chunk_size: int,
    seed: int,
    evidence_writer: EvidenceWriter,
    candidate_size: int = 500,
    positive_rating_threshold: float = 4.0,
    model_version: str | None = None,
) -> ChunkedOfflineEvaluation:
    """Evaluate exact candidates one user chunk at a time without a lazy union."""
    if stage not in {"validation", "test"}:
        raise ValueError("stage must be either validation or test")
    if user_chunk_size <= 0:
        raise ValueError("user_chunk_size must be positive")
    if candidate_size <= 0:
        raise ValueError("candidate_size must be positive")
    if model.getUserCol() != USER or model.getItemCol() != ITEM:
        raise ValueError("ALS model must use MovieLens user and movie ID columns")

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
    assignments = assign_user_chunks(users, user_chunk_size=user_chunk_size, seed=seed)

    matched = _assert_unique_matched_movies(service_movie_map)
    matched_items = matched.select(ITEM).distinct()
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
    common = {
        name: diagnostics[name]
        for name in (
            "target_users",
            "cold_users",
            "cold_items",
            "cold_ratings",
            "cold_positive_items",
        )
    }
    mapping_excluded = diagnostics["mapping_excluded_positive_items"]
    totals = {catalog: _empty_totals() for catalog in ("movielens", "service")}

    assignments.persist()
    try:
        chunk_count_row = assignments.agg(
            F.max("chunk_id").alias("last_chunk_id")
        ).first()
        last_chunk_id = chunk_count_row["last_chunk_id"]
        if last_chunk_id is None:
            empty_candidates = holdout_ratings.sparkSession.createDataFrame(
                [],
                f"{USER} int, {ITEM} int, score float, rank int, "
                "catalog string, service_movie_id long",
            )
            empty_per_user = holdout_ratings.sparkSession.createDataFrame(
                [],
                f"{USER} int, recall_at_10 double, ndcg_at_10 double, catalog string",
            )
            try:
                evidence_writer(
                    chunk_id=0,
                    candidates=empty_candidates,
                    per_user=empty_per_user,
                )
            except Exception as error:
                error.stage = "evidence"
                error.chunk_id = 0
                raise
        for chunk_id in range(0 if last_chunk_id is None else int(last_chunk_id) + 1):
            chunk_users = (
                assignments.filter(F.col("chunk_id") == chunk_id).select(USER).persist()
            )
            warm_chunk_users = None
            candidates = None
            per_user = None
            per_user_caches = []
            chunk_stage = "candidates"
            try:
                chunk_holdout = holdout_ratings.join(chunk_users, USER, "inner")
                warm_chunk_users = chunk_users.join(factor_users, USER, "inner").persist()
                warm_user_count = warm_chunk_users.count()
                if warm_user_count:
                    candidates = generate_materialized_candidates(
                        model,
                        warm_chunk_users,
                        known_ratings,
                        service_movie_map,
                        candidate_size=candidate_size,
                        matched_movies=matched,
                    )
                else:
                    candidates = chunk_users.sparkSession.createDataFrame(
                        [],
                        f"{USER} int, {ITEM} int, score float, rank int, "
                        "catalog string, service_movie_id long",
                    ).persist()

                chunk_stage = "metrics"
                per_user_frames = []
                for catalog, truth in (
                    ("movielens", chunk_holdout),
                    ("service", chunk_holdout.join(matched_items, ITEM, "inner")),
                ):
                    evaluation = evaluate_ranking(
                        candidates,
                        truth,
                        chunk_users,
                        catalog=catalog,
                        positive_rating_threshold=positive_rating_threshold,
                        k=K,
                        materialize_per_user=True,
                    )
                    per_user_caches.append(evaluation.per_user)
                    eligible_users = (
                        truth.filter(F.col("rating") >= positive_rating_threshold)
                        .select(USER)
                        .distinct()
                    )
                    summary = {
                        **evaluation.summary,
                        **_shortage_counts(
                            candidates,
                            chunk_users,
                            eligible_users,
                            catalog,
                            candidate_size,
                        ),
                    }
                    _add_summary(totals[catalog], summary)
                    per_user_frames.append(
                        evaluation.per_user.withColumn("catalog", F.lit(catalog))
                    )

                per_user = per_user_frames[0].unionByName(per_user_frames[1])
                chunk_stage = "evidence"
                evidence_writer(
                    chunk_id=chunk_id,
                    candidates=candidates,
                    per_user=per_user,
                )
            except Exception as error:
                error.stage = chunk_stage
                error.chunk_id = chunk_id
                raise
            finally:
                for cached_per_user in per_user_caches:
                    cached_per_user.unpersist()
                if candidates is not None:
                    candidates.unpersist()
                if warm_chunk_users is not None:
                    warm_chunk_users.unpersist()
                chunk_users.unpersist()
    finally:
        assignments.unpersist()

    summaries = {}
    for catalog, catalog_totals in totals.items():
        summaries[catalog] = _finalize_summary(
            catalog_totals,
            common,
            mapping_excluded if catalog == "service" else 0,
        )
    metadata = {
        "model_uid": model.uid,
        "model_version": model_version,
        "stage": stage,
        "positive_rating_threshold": positive_rating_threshold,
        "candidate_size": candidate_size,
        "k": K,
        "policy_version": POLICY_VERSION,
        "user_chunk_size": user_chunk_size,
        "chunk_seed": seed,
    }
    return ChunkedOfflineEvaluation(summaries, metadata)


def _empty_totals() -> dict[str, int | float]:
    return {
        "evaluated_users": 0,
        "positive_items": 0,
        "weighted_recall_at_10": 0.0,
        "weighted_ndcg_at_10": 0.0,
        "duplicate_recommendations_removed": 0,
        "zero_candidate_users": 0,
        "fewer_than_k_users": 0,
        "fewer_than_n_users": 0,
        "evaluated_zero_candidate_users": 0,
        "evaluated_fewer_than_k_users": 0,
        "evaluated_fewer_than_n_users": 0,
    }


def _add_summary(totals: dict[str, int | float], summary: dict[str, object]) -> None:
    evaluated_users = int(summary["evaluated_users"])
    totals["evaluated_users"] += evaluated_users
    totals["positive_items"] += int(summary["positive_items"])
    for metric in ("recall_at_10", "ndcg_at_10"):
        value = summary[metric]
        if value is not None:
            totals[f"weighted_{metric}"] += float(value) * evaluated_users
    for field in (
        "duplicate_recommendations_removed",
        "zero_candidate_users",
        "fewer_than_k_users",
        "fewer_than_n_users",
        "evaluated_zero_candidate_users",
        "evaluated_fewer_than_k_users",
        "evaluated_fewer_than_n_users",
    ):
        totals[field] += int(summary[field])


def _finalize_summary(
    totals: dict[str, int | float],
    common: dict[str, int],
    mapping_excluded: int,
) -> dict[str, int | float | None]:
    evaluated_users = int(totals["evaluated_users"])
    summary = {
        "target_users": common["target_users"],
        "evaluated_users": evaluated_users,
        "users_without_positives": common["target_users"] - evaluated_users,
        "positive_items": int(totals["positive_items"]),
        "recall_at_10": None,
        "ndcg_at_10": None,
        "duplicate_recommendations_removed": int(
            totals["duplicate_recommendations_removed"]
        ),
        "cold_users": common["cold_users"],
        "cold_items": common["cold_items"],
        "cold_ratings": common["cold_ratings"],
        "cold_positive_items": common["cold_positive_items"],
        "mapping_excluded_positive_items": mapping_excluded,
    }
    if evaluated_users:
        summary["recall_at_10"] = totals["weighted_recall_at_10"] / evaluated_users
        summary["ndcg_at_10"] = totals["weighted_ndcg_at_10"] / evaluated_users
    for field in (
        "zero_candidate_users",
        "fewer_than_k_users",
        "fewer_than_n_users",
        "evaluated_zero_candidate_users",
        "evaluated_fewer_than_k_users",
        "evaluated_fewer_than_n_users",
    ):
        summary[field] = int(totals[field])
    return summary
