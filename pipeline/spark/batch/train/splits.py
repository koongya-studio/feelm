"""Reproducible per-user chronological splits of validated MovieLens ratings."""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from math import isfinite

from pyspark.sql import Column, DataFrame, Window
from pyspark.sql import functions as F

USER = "movielens_user_id"
MOVIE = "movielens_movie_id"
TIME = "rated_at_epoch"
SPLIT = "split"
SPLIT_NAMES = ("train", "validation", "test")
_MAX_SPLIT_SIZE = 2**63 - 1


def _floor_split_ratio(ratio: Decimal) -> Column:
    """Calculate floor(_split_size * ratio) without Decimal scale reduction."""
    numerator, denominator = ratio.as_integer_ratio()
    if numerator * _MAX_SPLIT_SIZE < denominator:
        return F.lit(0)
    decimal_integer = "decimal(38, 0)"
    return F.expr(
        f"(CAST(_split_size AS {decimal_integer}) * "
        f"CAST({numerator} AS {decimal_integer})) DIV "
        f"CAST({denominator} AS {decimal_integer})"
    )


@dataclass(frozen=True)
class RatingSplits:
    """Original columns plus a split label; filtered views retain the input schema."""

    assignments: DataFrame

    def _select(self, name: str) -> DataFrame:
        return self.assignments.filter(F.col(SPLIT) == name).drop(SPLIT)

    @property
    def train(self) -> DataFrame:
        return self._select("train")

    @property
    def validation(self) -> DataFrame:
        return self._select("validation")

    @property
    def test(self) -> DataFrame:
        return self._select("test")


def split_ratings(
    ratings: DataFrame,
    *,
    train_ratio: float = 0.8,
    validation_ratio: float = 0.1,
    seed: int = 42,
    min_eval_ratings: int = 3,
) -> RatingSplits:
    """Split already validated, unique user-movie ratings without dropping rows.

    Ratios set per-user counts, adjusted to leave at least one row in each group.
    Histories shorter than min_eval_ratings stay entirely in train. The seed only
    breaks timestamp ties; ratings and service visibility never affect assignment.
    """
    if not all(isfinite(r) and 0 < r < 1 for r in (train_ratio, validation_ratio)):
        raise ValueError("split ratios must be finite and between 0 and 1")
    train_fraction = Decimal(str(train_ratio))
    validation_fraction = Decimal(str(validation_ratio))
    if train_fraction + validation_fraction >= 1:
        raise ValueError("split ratios must leave a positive test ratio")
    if type(min_eval_ratings) is not int or min_eval_ratings < 3:
        raise ValueError("min_eval_ratings must be an integer >= 3")
    if type(seed) is not int or not -(2**63) <= seed < 2**63:
        raise ValueError("seed must be a signed 64-bit integer")
    required = {USER, MOVIE, TIME}
    if not required.issubset(ratings.columns):
        raise ValueError(f"missing split columns: {sorted(required - set(ratings.columns))}")
    internal = {SPLIT, "_split_size", "_split_position", "_split_train", "_split_validation"}
    if internal.intersection(ratings.columns):
        raise ValueError("input contains reserved split columns")

    by_user = Window.partitionBy(USER)
    chronological = by_user.orderBy(
        F.col(TIME), F.xxhash64(F.lit(seed), F.col(USER), F.col(MOVIE)), F.col(MOVIE)
    )
    ranked = ratings.withColumn("_split_size", F.count("*").over(by_user)).withColumn(
        "_split_position", F.row_number().over(chronological)
    )
    size = F.col("_split_size")
    train_count = F.least(
        F.greatest(_floor_split_ratio(train_fraction), F.lit(1)),
        size - 2,
    )
    ranked = ranked.withColumn("_split_train", train_count).withColumn(
        "_split_validation",
        F.least(
            F.greatest(_floor_split_ratio(validation_fraction), F.lit(1)),
            size - F.col("_split_train") - 1,
        ),
    )
    position = F.col("_split_position")
    label = (
        F.when(size < min_eval_ratings, "train")
        .when(position <= F.col("_split_train"), "train")
        .when(position <= F.col("_split_train") + F.col("_split_validation"), "validation")
        .otherwise("test")
    )
    return RatingSplits(ranked.select(*ratings.columns, label.alias(SPLIT)))


@dataclass(frozen=True)
class SplitCounts:
    ratings: int = 0
    users: int = 0
    movies: int = 0
    users_not_in_train: int = 0
    movies_not_in_train: int = 0
    cold_start_ratings: int = 0


@dataclass(frozen=True)
class SplitReport:
    total_ratings: int
    train_only_users: int
    by_split: dict[str, SplitCounts]


def summarize_splits(splits: RatingSplits) -> SplitReport:
    """Collect only aggregate counts; keep cold-start rows for downstream policy.

    Both validation and test are compared with train, not train + validation.
    Callers can persist assignments when reusing them for reporting and training.
    """
    train_users = splits.train.select(USER).distinct().withColumn("_split_known_user", F.lit(1))
    train_movies = splits.train.select(MOVIE).distinct().withColumn("_split_known_movie", F.lit(1))
    known = splits.assignments.join(train_users, USER, "left").join(train_movies, MOVIE, "left")
    unknown_user = F.col("_split_known_user").isNull()
    unknown_movie = F.col("_split_known_movie").isNull()
    rows = (
        known.groupBy(SPLIT)
        .agg(
            F.count("*").alias("ratings"),
            F.countDistinct(USER).alias("users"),
            F.countDistinct(MOVIE).alias("movies"),
            F.countDistinct(F.when(unknown_user, F.col(USER))).alias("users_not_in_train"),
            F.countDistinct(F.when(unknown_movie, F.col(MOVIE))).alias("movies_not_in_train"),
            F.sum(F.when(unknown_user | unknown_movie, 1).otherwise(0)).alias("cold_start_ratings"),
        )
        .collect()
    )
    counts = {name: SplitCounts() for name in SPLIT_NAMES}
    for row in rows:
        values = row.asDict()
        name = values.pop(SPLIT)
        counts[name] = SplitCounts(**values)
    train_only_users = (
        splits.assignments.groupBy(USER)
        .agg(F.max(F.when(F.col(SPLIT) != "train", 1).otherwise(0)).alias("has_holdout"))
        .filter("has_holdout = 0")
        .count()
    )
    return SplitReport(sum(c.ratings for c in counts.values()), train_only_users, counts)
