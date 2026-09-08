"""Shared mapping statuses and pipeline result types."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from pyspark.sql import DataFrame

MATCHED = "MATCHED"
MOVIELENS_TMDB_ID_MISSING = "MOVIELENS_TMDB_ID_MISSING"
SERVICE_ONLY_CONTENT_BASED = "SERVICE_ONLY_CONTENT_BASED"
TMDB_ID_AMBIGUOUS = "TMDB_ID_AMBIGUOUS"


@dataclass(frozen=True)
class PreparedFrame:
    frame: DataFrame
    raw_count: int
    valid_count: int
    invalid_count: int
    duplicate_count: int


@dataclass(frozen=True)
class PipelineResult:
    ratings: DataFrame
    movies: DataFrame
    movie_id_map: DataFrame
    service_movie_id_map: DataFrame
    validation_report: dict[str, Any]
