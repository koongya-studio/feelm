"""Catalog eligibility boundaries for Content/ALS hybrid evaluation."""

from __future__ import annotations

from batch.evaluation.content_hybrid import matched_quality_catalog, service_coverage_catalog


def test_quality_excludes_all_known_ratings_and_nonmatched_movies():
    movies = [
        {"service_movie_id": 10, "movielens_movie_id": 1, "mapping_status": "MATCHED"},
        {"service_movie_id": 11, "movielens_movie_id": 2, "mapping_status": "MATCHED"},
        {"service_movie_id": 12, "movielens_movie_id": 3, "mapping_status": "MATCHED"},
        {
            "service_movie_id": 13,
            "movielens_movie_id": None,
            "mapping_status": "SERVICE_ONLY_CONTENT_BASED",
        },
        {"service_movie_id": 14, "movielens_movie_id": None, "mapping_status": "TMDB_ID_AMBIGUOUS"},
    ]
    assert matched_quality_catalog(movies, known_movielens_ids={1}) == movies[1:3]


def test_coverage_includes_service_only_but_not_ambiguous_and_reports_counts():
    movies = [
        {"service_movie_id": 10, "movielens_movie_id": 1, "mapping_status": "MATCHED"},
        {"service_movie_id": 11, "movielens_movie_id": 2, "mapping_status": "MATCHED"},
        {
            "service_movie_id": 13,
            "movielens_movie_id": None,
            "mapping_status": "SERVICE_ONLY_CONTENT_BASED",
        },
        {"service_movie_id": 14, "movielens_movie_id": None, "mapping_status": "TMDB_ID_AMBIGUOUS"},
    ]
    catalog, excluded = service_coverage_catalog(movies, known_movielens_ids={1})
    assert catalog == [movies[1], movies[2]]
    assert excluded == {"known_matched": 1, "ambiguous": 1}
