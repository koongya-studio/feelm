"""Normalize and validate each source before movie ID mapping."""

from __future__ import annotations

from collections.abc import Sequence

from pyspark.sql import DataFrame, Window
from pyspark.sql import functions as F

from batch.prepare.movie_id_mapping.contracts import PreparedFrame


def _normalized_name(name: str) -> str:
    return "".join(character for character in name.lower() if character.isalnum())


def _column_name(
    frame: DataFrame,
    candidates: Sequence[str],
) -> str:
    actual_by_normalized = {_normalized_name(name): name for name in frame.columns}
    for candidate in candidates:
        actual = actual_by_normalized.get(_normalized_name(candidate))
        if actual is not None:
            return actual
    raise ValueError(
        f"Required column not found. Expected one of {list(candidates)}, "
        f"actual columns are {frame.columns}."
    )


def _assert_no_conflicting_mapping(
    frame: DataFrame,
    key_columns: Sequence[str],
    value_column: str,
    label: str,
) -> None:
    conflicts = (
        frame.groupBy(*key_columns)
        .agg(F.countDistinct(value_column).alias("distinct_values"))
        .filter(F.col("distinct_values") > 1)
        .limit(1)
        .count()
    )
    if conflicts:
        keys = ", ".join(key_columns)
        raise ValueError(f"{label} has conflicting {value_column} values for {keys}.")


def prepare_ratings(source: DataFrame) -> PreparedFrame:
    user_column = _column_name(source, ["userId", "user_id"])
    movie_column = _column_name(source, ["movieId", "movie_id"])
    rating_column = _column_name(source, ["rating", "score"])
    timestamp_column = _column_name(source, ["timestamp", "rated_at_epoch"])

    raw_count = source.count()
    normalized = source.select(
        F.trim(F.col(user_column)).try_cast("int").alias("movielens_user_id"),
        F.trim(F.col(movie_column)).try_cast("int").alias("movielens_movie_id"),
        F.trim(F.col(rating_column)).try_cast("double").alias("rating"),
        F.trim(F.col(timestamp_column)).try_cast("long").alias("rated_at_epoch"),
    )
    is_valid = (
        (F.col("movielens_user_id") > 0)
        & (F.col("movielens_movie_id") > 0)
        & F.col("rating").between(0.5, 5.0)
        & (F.abs(F.col("rating") * 2 - F.round(F.col("rating") * 2)) < 0.000001)
        & (F.col("rated_at_epoch") >= 0)
    )
    valid = normalized.filter(is_valid)
    valid_count = valid.count()

    rating_keys = ["movielens_user_id", "movielens_movie_id"]
    latest_ratings = (
        valid.withColumn(
            "_latest_epoch", F.max("rated_at_epoch").over(Window.partitionBy(*rating_keys))
        )
        .filter(F.col("rated_at_epoch") == F.col("_latest_epoch"))
        .drop("_latest_epoch")
    )
    _assert_no_conflicting_mapping(
        latest_ratings, rating_keys, "rating", "MovieLens latest ratings"
    )
    canonical = latest_ratings.dropDuplicates(rating_keys).cache()
    canonical_count = canonical.count()
    return PreparedFrame(
        frame=canonical,
        raw_count=raw_count,
        valid_count=valid_count,
        invalid_count=raw_count - valid_count,
        duplicate_count=valid_count - canonical_count,
    )


def prepare_movielens_movies(source: DataFrame) -> PreparedFrame:
    movie_column = _column_name(source, ["movieId", "movie_id"])
    title_column = _column_name(source, ["title"])
    genres_column = _column_name(source, ["genres"])

    raw_count = source.count()
    genre_tokens = F.filter(
        F.transform(
            F.split(F.col(genres_column).cast("string"), r"\|"),
            lambda value: F.trim(value),
        ),
        lambda value: (value != "") & (value != "(no genres listed)"),
    )
    genres = F.concat_ws("|", F.sort_array(F.array_distinct(genre_tokens)))
    normalized = source.select(
        F.trim(F.col(movie_column)).try_cast("int").alias("movielens_movie_id"),
        F.trim(F.col(title_column).cast("string")).alias("movielens_title"),
        F.when(F.length(genres) > 0, genres).alias("movielens_genres_raw"),
    )
    valid = normalized.filter((F.col("movielens_movie_id") > 0) & (F.length("movielens_title") > 0))
    valid_count = valid.count()
    _assert_no_conflicting_mapping(
        valid, ["movielens_movie_id"], "movielens_title", "MovieLens movies"
    )
    _assert_no_conflicting_mapping(
        valid, ["movielens_movie_id"], "movielens_genres_raw", "MovieLens movies"
    )
    prefer_known_genres = Window.partitionBy("movielens_movie_id").orderBy(
        F.col("movielens_genres_raw").desc_nulls_last()
    )
    canonical = (
        valid.withColumn("_row_number", F.row_number().over(prefer_known_genres))
        .filter(F.col("_row_number") == 1)
        .drop("_row_number")
        .cache()
    )
    canonical_count = canonical.count()
    return PreparedFrame(
        frame=canonical,
        raw_count=raw_count,
        valid_count=valid_count,
        invalid_count=raw_count - valid_count,
        duplicate_count=valid_count - canonical_count,
    )


def prepare_links(source: DataFrame) -> PreparedFrame:
    movie_column = _column_name(source, ["movieId", "movie_id"])
    tmdb_column = _column_name(source, ["tmdbId", "tmdb_id"])

    raw_count = source.count()
    normalized = source.select(
        F.trim(F.col(movie_column)).try_cast("int").alias("movielens_movie_id"),
        F.trim(F.col(tmdb_column)).try_cast("int").alias("tmdb_id"),
    ).withColumn("tmdb_id", F.when(F.col("tmdb_id") > 0, F.col("tmdb_id")))
    valid = normalized.filter(F.col("movielens_movie_id") > 0)
    valid_count = valid.count()
    _assert_no_conflicting_mapping(
        valid.filter(F.col("tmdb_id").isNotNull()),
        ["movielens_movie_id"],
        "tmdb_id",
        "MovieLens links",
    )
    prefer_tmdb_id = Window.partitionBy("movielens_movie_id").orderBy(
        F.col("tmdb_id").desc_nulls_last()
    )
    canonical = (
        valid.withColumn("_row_number", F.row_number().over(prefer_tmdb_id))
        .filter(F.col("_row_number") == 1)
        .drop("_row_number")
        .cache()
    )
    canonical_count = canonical.count()
    return PreparedFrame(
        frame=canonical,
        raw_count=raw_count,
        valid_count=valid_count,
        invalid_count=raw_count - valid_count,
        duplicate_count=valid_count - canonical_count,
    )


def prepare_service_movies(source: DataFrame) -> PreparedFrame:
    service_column = _column_name(source, ["service_movie_id"])
    tmdb_column = _column_name(source, ["tmdb_id"])

    raw_count = source.count()
    if raw_count == 0:
        raise ValueError("Backend service movie export is empty. Build the movies table first.")

    normalized = source.select(
        F.trim(F.col(service_column).cast("string")).try_cast("long").alias("service_movie_id"),
        F.trim(F.col(tmdb_column).cast("string")).try_cast("int").alias("tmdb_id"),
    )
    valid = normalized.filter((F.col("service_movie_id") > 0) & (F.col("tmdb_id") > 0))
    valid_count = valid.count()
    if valid_count != raw_count:
        raise ValueError(
            "Backend service movie export contains null, non-numeric, or non-positive IDs."
        )

    _assert_no_conflicting_mapping(
        valid, ["service_movie_id"], "tmdb_id", "Backend service movie export"
    )
    _assert_no_conflicting_mapping(
        valid, ["tmdb_id"], "service_movie_id", "Backend service movie export"
    )
    canonical = valid.dropDuplicates(["service_movie_id", "tmdb_id"]).cache()
    canonical_count = canonical.count()
    if canonical_count != raw_count:
        raise ValueError("Backend service movie export contains duplicate rows.")

    return PreparedFrame(
        frame=canonical,
        raw_count=raw_count,
        valid_count=valid_count,
        invalid_count=0,
        duplicate_count=0,
    )


def prepared_metrics(prepared: PreparedFrame) -> dict[str, int]:
    return {
        "raw": prepared.raw_count,
        "valid": prepared.valid_count,
        "invalid_or_missing": prepared.invalid_count,
        "duplicates_removed": prepared.duplicate_count,
    }
