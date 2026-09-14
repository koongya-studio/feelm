from __future__ import annotations

import pytest
from pyspark.sql import SparkSession
from pyspark.sql import functions as F

from batch.prepare.movie_metadata import build_movie_metadata, publish_movie_metadata


def test_builds_deterministic_service_metadata_snapshot() -> None:
    spark = (
        SparkSession.builder.master("local[1]")
        .appName("movie-metadata-test")
        .config("spark.ui.enabled", "false")
        .getOrCreate()
    )
    try:
        mapping = spark.createDataFrame(
            [(10, 100, 1, "MATCHED"), (20, 200, None, "SERVICE_ONLY_CONTENT_BASED")],
            "service_movie_id long, tmdb_id int, movielens_movie_id int, mapping_status string",
        )
        tmdb = spark.createDataFrame(
            [(100, "Movie", [{"name": "DRAMA"}])],
            "id int, title string, genres array<struct<name:string>>",
        )
        result = build_movie_metadata(mapping, tmdb, "snapshot-1")
        row = result.first()
        assert row.service_movie_id == 10
        assert row.genre_codes == ["DRAMA"]
        assert row.representative_flavor_code is None
        assert row.movie_metadata_revision.startswith("sha256:")
        assert result.count() == 1
    finally:
        spark.stop()


def test_rejects_matched_mapping_without_tmdb_metadata() -> None:
    spark = (
        SparkSession.builder.master("local[1]")
        .appName("movie-metadata-missing-test")
        .config("spark.ui.enabled", "false")
        .getOrCreate()
    )
    try:
        mapping = spark.createDataFrame(
            [(10, 100, 1, "MATCHED"), (20, 200, 2, "MATCHED")],
            "service_movie_id long, tmdb_id int, movielens_movie_id int, mapping_status string",
        )
        tmdb = spark.createDataFrame(
            [(100, "Movie", [{"name": "DRAMA"}])],
            "id int, title string, genres array<struct<name:string>>",
        )

        with pytest.raises(ValueError, match="every MATCHED mapping must have TMDb metadata"):
            build_movie_metadata(mapping, tmdb, "snapshot-1")
    finally:
        spark.stop()


def test_publishes_complete_immutable_metadata_snapshot(tmp_path) -> None:
    spark = (
        SparkSession.builder.master("local[1]")
        .appName("movie-metadata-publish-test")
        .config("spark.ui.enabled", "false")
        .getOrCreate()
    )
    try:
        mapping = spark.createDataFrame(
            [(10, 100, 1, "MATCHED")],
            "service_movie_id long, tmdb_id int, movielens_movie_id int, mapping_status string",
        )
        tmdb = spark.createDataFrame(
            [(100, "Movie", [{"name": "DRAMA"}])],
            "id int, title string, genres array<struct<name:string>>",
        )
        output_uri = (tmp_path / "movie-metadata-snapshot-1").as_uri()
        metadata = build_movie_metadata(mapping, tmdb, "snapshot-1")

        assert publish_movie_metadata(metadata, output_uri) == output_uri
        assert spark.read.parquet(output_uri).count() == 1
        with pytest.raises(FileExistsError, match="already exists"):
            publish_movie_metadata(metadata, output_uri)
    finally:
        spark.stop()


@pytest.mark.parametrize(
    ("column", "value"),
    [("title", ""), ("genre_codes", ["DRAMA", ""])],
)
def test_invalid_metadata_is_not_published_to_final_uri(tmp_path, column, value) -> None:
    spark = (
        SparkSession.builder.master("local[1]")
        .appName("movie-metadata-invalid-publish-test")
        .config("spark.ui.enabled", "false")
        .getOrCreate()
    )
    try:
        mapping = spark.createDataFrame(
            [(10, 100, 1, "MATCHED")],
            "service_movie_id long, tmdb_id int, movielens_movie_id int, mapping_status string",
        )
        tmdb = spark.createDataFrame(
            [(100, "Movie", [{"name": "DRAMA"}])],
            "id int, title string, genres array<struct<name:string>>",
        )
        invalid = build_movie_metadata(mapping, tmdb, "snapshot-1").withColumn(column, F.lit(value))
        output = tmp_path / "movie-metadata-invalid"

        with pytest.raises(ValueError, match="title and genre codes"):
            publish_movie_metadata(invalid, output.as_uri())

        assert not output.exists()
        assert not list(tmp_path.glob("movie-metadata-invalid.tmp-*"))
    finally:
        spark.stop()
