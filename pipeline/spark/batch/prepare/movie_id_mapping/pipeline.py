"""Build normalized MovieLens datasets and service movie ID mappings."""

from __future__ import annotations

from typing import Any

from pyspark.sql import DataFrame
from pyspark.sql import functions as F

from batch.prepare.movie_id_mapping.contracts import (
    MATCHED,
    MOVIELENS_TMDB_ID_MISSING,
    SERVICE_ONLY_CONTENT_BASED,
    TMDB_ID_AMBIGUOUS,
    PipelineResult,
)
from batch.prepare.movie_id_mapping.preparation import (
    prepare_links,
    prepare_movielens_movies,
    prepare_ratings,
    prepare_service_movies,
    prepared_metrics,
)


def build_pipeline(
    ratings_source: DataFrame,
    movies_source: DataFrame,
    links_source: DataFrame,
    service_movies_source: DataFrame,
) -> PipelineResult:
    """Normalize MovieLens data and attach backend-generated service movie IDs."""

    service_movies = prepare_service_movies(service_movies_source)
    ratings = prepare_ratings(ratings_source)
    movielens_movies = prepare_movielens_movies(movies_source)
    links = prepare_links(links_source)

    movie_ids = movielens_movies.frame.select("movielens_movie_id")
    link_movie_ids = links.frame.select("movielens_movie_id")
    ratings_missing_movie_count = ratings.frame.join(
        movie_ids, "movielens_movie_id", "left_anti"
    ).count()
    links_missing_movie_count = links.frame.join(
        movie_ids, "movielens_movie_id", "left_anti"
    ).count()
    movies_missing_link_count = movie_ids.join(
        link_movie_ids, "movielens_movie_id", "left_anti"
    ).count()

    catalog = movielens_movies.frame.join(links.frame, "movielens_movie_id", "left")
    tmdb_cardinality = (
        catalog.filter(F.col("tmdb_id").isNotNull())
        .groupBy("tmdb_id")
        .agg(F.countDistinct("movielens_movie_id").alias("movielens_id_count"))
    )
    catalog = (
        catalog.join(tmdb_cardinality, "tmdb_id", "left")
        .withColumn(
            "mapping_status",
            F.when(F.col("tmdb_id").isNull(), MOVIELENS_TMDB_ID_MISSING)
            .when(F.col("movielens_id_count") > 1, TMDB_ID_AMBIGUOUS)
            .otherwise(MATCHED),
        )
        .cache()
    )

    movies_output = catalog.select(
        "movielens_movie_id",
        F.col("tmdb_id").cast("int"),
        F.col("movielens_title").alias("title"),
        F.when(
            F.col("movielens_genres_raw").isNull()
            | (F.col("movielens_genres_raw") == "(no genres listed)"),
            F.array().cast("array<string>"),
        )
        .otherwise(F.split("movielens_genres_raw", r"\|"))
        .alias("genres"),
        "mapping_status",
    ).cache()
    movie_id_map_output = (
        catalog.filter(F.col("tmdb_id").isNotNull())
        .select(
            "movielens_movie_id",
            F.col("tmdb_id").cast("int"),
            "mapping_status",
        )
        .cache()
    )
    unambiguous_movie_id_map = movie_id_map_output.filter(
        F.col("mapping_status") == MATCHED
    ).select("tmdb_id", "movielens_movie_id")
    ambiguous_tmdb_ids = (
        movie_id_map_output.filter(F.col("mapping_status") == TMDB_ID_AMBIGUOUS)
        .select("tmdb_id")
        .distinct()
        .withColumn("is_tmdb_id_ambiguous", F.lit(True))
    )
    service_movie_id_map_output = (
        service_movies.frame.join(unambiguous_movie_id_map, "tmdb_id", "left")
        .join(ambiguous_tmdb_ids, "tmdb_id", "left")
        .select(
            "service_movie_id",
            "tmdb_id",
            "movielens_movie_id",
            F.when(F.col("is_tmdb_id_ambiguous"), TMDB_ID_AMBIGUOUS)
            .when(F.col("movielens_movie_id").isNull(), SERVICE_ONLY_CONTENT_BASED)
            .otherwise(MATCHED)
            .alias("mapping_status"),
        )
        .cache()
    )
    ratings_output = (
        ratings.frame.join(movie_ids, "movielens_movie_id", "inner")
        .select(
            "movielens_user_id",
            "movielens_movie_id",
            F.col("rating").cast("float").alias("rating"),
            "rated_at_epoch",
            F.to_timestamp(F.from_unixtime("rated_at_epoch")).alias("rated_at_utc"),
        )
        .cache()
    )

    ratings_output_count = ratings_output.count()
    movies_output_count = movies_output.count()
    mapping_output_count = movie_id_map_output.count()
    service_mapping_output_count = service_movie_id_map_output.count()
    rating_expected_count = (
        ratings.valid_count - ratings.duplicate_count - ratings_missing_movie_count
    )
    movie_expected_count = movielens_movies.valid_count - movielens_movies.duplicate_count
    mapping_counts = {
        row["mapping_status"]: row["count"]
        for row in movies_output.groupBy("mapping_status").count().collect()
    }
    missing_tmdb_count = mapping_counts.get(MOVIELENS_TMDB_ID_MISSING, 0)
    service_mapping_counts = {
        row["mapping_status"]: row["count"]
        for row in service_movie_id_map_output.groupBy("mapping_status").count().collect()
    }

    checks = [
        {
            "name": "ratings_output_row_count_matches",
            "passed": ratings_output_count == rating_expected_count,
            "expected": rating_expected_count,
            "actual": ratings_output_count,
        },
        {
            "name": "movies_output_row_count_matches",
            "passed": movies_output_count == movie_expected_count,
            "expected": movie_expected_count,
            "actual": movies_output_count,
        },
        {
            "name": "movie_id_map_excludes_only_missing_tmdb_ids",
            "passed": mapping_output_count + missing_tmdb_count == movies_output_count,
            "expected": movies_output_count - missing_tmdb_count,
            "actual": mapping_output_count,
        },
        {
            "name": "links_reference_known_movies",
            "passed": links_missing_movie_count == 0,
            "expected": 0,
            "actual": links_missing_movie_count,
        },
        {
            "name": "every_movie_has_a_links_row",
            "passed": movies_missing_link_count == 0,
            "expected": 0,
            "actual": movies_missing_link_count,
        },
        {
            "name": "service_movie_id_map_row_count_matches",
            "passed": service_mapping_output_count == service_movies.valid_count,
            "expected": service_movies.valid_count,
            "actual": service_mapping_output_count,
        },
    ]
    all_checks_passed = all(check["passed"] for check in checks)

    report: dict[str, Any] = {
        "contract_version": 2,
        "inputs": {
            "ratings": prepared_metrics(ratings),
            "movielens_movies": prepared_metrics(movielens_movies),
            "movielens_links": prepared_metrics(links),
            "service_movies": prepared_metrics(service_movies),
        },
        "quality": {
            "ratings_missing_movie_reference": ratings_missing_movie_count,
            "links_missing_movie_reference": links_missing_movie_count,
            "movies_missing_links_row": movies_missing_link_count,
        },
        "mapping": {
            "matched": mapping_counts.get(MATCHED, 0),
            "ambiguous_tmdb_id": mapping_counts.get(TMDB_ID_AMBIGUOUS, 0),
            "missing_tmdb_id": missing_tmdb_count,
            "by_status": mapping_counts,
        },
        "service_mapping": {
            "matched": service_mapping_counts.get(MATCHED, 0),
            "content_based": service_mapping_counts.get(SERVICE_ONLY_CONTENT_BASED, 0),
            "ambiguous_tmdb_id": service_mapping_counts.get(TMDB_ID_AMBIGUOUS, 0),
            "by_status": service_mapping_counts,
        },
        "outputs": {
            "ratings": ratings_output_count,
            "movies": movies_output_count,
            "movie_id_map": mapping_output_count,
            "service_movie_id_map": service_mapping_output_count,
        },
        "checks": checks,
        "all_checks_passed": all_checks_passed,
    }
    return PipelineResult(
        ratings=ratings_output,
        movies=movies_output,
        movie_id_map=movie_id_map_output,
        service_movie_id_map=service_movie_id_map_output,
        validation_report=report,
    )
