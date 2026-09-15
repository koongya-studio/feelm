from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest
from pyspark.sql import SparkSession

from batch.content.serving import load_content_artifacts
from batch.prepare.movie_id_mapping.io import write_pipeline
from batch.prepare.movie_id_mapping.pipeline import build_pipeline
from batch.train.artifacts import load_als_artifacts
from jobs.batch import als_serving, content_serving, movie_metadata, tmdb_snapshot

INPUT_SNAPSHOT = "e2e-fixture-snapshot"
CONTENT_VERSION = "content-e2e-fixture-v1"
MODEL_VERSION = "als-e2e-fixture-v1"

# 10 MATCHED, 20 and 30 SERVICE_ONLY_CONTENT_BASED, 40 TMDB_ID_AMBIGUOUS
# (TMDb 300 is linked from two MovieLens movies).  TMDb 400 has no genre, so it
# is the one service movie without a content vector.
SERVICE_MOVIES_CSV = """service_movie_id,tmdb_id
10,100
20,200
30,400
40,300
"""
LINKS_CSV = """movieId,imdbId,tmdbId
1,tt1,100
2,tt2,
3,tt3,300
4,tt4,300
"""
MOVIES_CSV = """movieId,title,genres
1,Mapped Movie (2020),Drama|Thriller
2,No TMDb Link (2021),Comedy
3,Ambiguous A (2022),Drama
4,Ambiguous B (2022),Drama
"""
RATINGS_CSV = """userId,movieId,rating,timestamp
1,1,4.0,100
1,3,3.5,200
2,1,3.0,300
2,4,5.0,400
3,1,2.5,500
3,3,4.5,600
"""
RAW_TMDB = {
    100: ("Mapped Movie", "en", "2020-05-01", 110, [{"id": 18, "name": "Drama"}]),
    200: ("Content Only", "ko", "2021-01-01", 95, [{"id": 35, "name": "Comedy"}]),
    300: ("Ambiguous Movie", "en", "2022-03-01", 105, [{"id": 18, "name": "Drama"}]),
    400: ("No Genres", "en", "2019-01-01", 100, []),
}


@pytest.fixture(scope="module")
def spark():
    session = (
        SparkSession.builder.master("local[1]")
        .appName("content-serving-local-e2e-test")
        .config("spark.ui.enabled", "false")
        .config("spark.sql.shuffle.partitions", "1")
        .config("spark.sql.session.timeZone", "UTC")
        .config("spark.sql.ansi.enabled", "true")
        .getOrCreate()
    )
    session.sparkContext.setLogLevel("ERROR")
    yield session
    session.stop()


def _write_inputs(root: Path) -> tuple[Path, Path, Path]:
    csv_root, movies, keywords = root / "csv", root / "raw-tmdb", root / "raw-keywords"
    csv_root.mkdir(parents=True)
    movies.mkdir(parents=True)
    keywords.mkdir(parents=True)
    for name, text in (
        ("service-movies.csv", SERVICE_MOVIES_CSV),
        ("links.csv", LINKS_CSV),
        ("movies.csv", MOVIES_CSV),
        ("ratings.csv", RATINGS_CSV),
    ):
        (csv_root / name).write_text(text, encoding="utf-8")
    for tmdb_id, (title, language, release, runtime, genres) in RAW_TMDB.items():
        body = {
            "details": {
                "id": tmdb_id,
                "title": title,
                "original_language": language,
                "release_date": release,
                "runtime": runtime,
                "genres": genres,
            },
            "credits": {"cast": [{"id": 5, "order": 0}], "crew": [{"id": 7, "job": "Director"}]},
        }
        (movies / f"{tmdb_id}.json").write_text(json.dumps(body, indent=2), encoding="utf-8")
    (keywords / "100.json").write_text(
        json.dumps({"id": 100, "keywords": [{"id": 9, "name": "k"}]}), encoding="utf-8"
    )
    return csv_root, movies, keywords


def _run_job(module, arguments: list[str], spark, monkeypatch, capsys) -> str:
    """Run one job through its command line against the test session."""
    monkeypatch.setattr(module, "create_spark_session", lambda *_args, **_kwargs: spark)
    monkeypatch.setattr(sys, "argv", [module.__name__, *arguments])
    module.main()
    return capsys.readouterr().out.strip().splitlines()[-1]


def test_local_serving_bundle_end_to_end(spark, tmp_path, monkeypatch, capsys) -> None:
    # The jobs stop the session they are given; the module fixture owns this one.
    monkeypatch.setattr(spark, "stop", lambda: None)
    csv_root, raw_movies, raw_keywords = _write_inputs(tmp_path / "input")
    root_uri = (tmp_path / "artifacts").as_uri()
    csv = spark.read.option("header", True)
    mapping_root = f"{root_uri}/snapshots/movie-mapping/fixture-v1"
    write_pipeline(
        build_pipeline(
            csv.csv(str(csv_root / "ratings.csv")),
            csv.csv(str(csv_root / "movies.csv")),
            csv.csv(str(csv_root / "links.csv")),
            csv.csv(str(csv_root / "service-movies.csv")),
        ),
        mapping_root,
        "errorifexists",
    )
    ratings_uri = f"{mapping_root}/ratings"
    mapping_uri = f"{mapping_root}/service-movie-id-map"
    mapping = spark.read.parquet(mapping_uri)
    mapping_revision = mapping.first()["mapping_revision"]
    statuses = {row["service_movie_id"]: row["mapping_status"] for row in mapping.collect()}
    assert statuses == {
        10: "MATCHED",
        20: "SERVICE_ONLY_CONTENT_BASED",
        30: "SERVICE_ONLY_CONTENT_BASED",
        40: "TMDB_ID_AMBIGUOUS",
    }

    tmdb_uri = f"{root_uri}/snapshots/tmdb/fixture-v1"
    tmdb_summary = json.loads(
        _run_job(
            tmdb_snapshot,
            [
                "--movies-raw-uri", str(raw_movies),
                "--keywords-raw-uri", str(raw_keywords),
                "--mapping-uri", mapping_uri,
                "--mapping-revision", mapping_revision,
                "--output-uri", tmdb_uri,
            ],
            spark, monkeypatch, capsys,
        )
    )
    tmdb_snapshot_revision = tmdb_summary["tmdb_snapshot_revision"]
    assert tmdb_summary["rows"] == 4 and tmdb_summary["feature_eligible"] == 3

    metadata_uri = _run_job(
        movie_metadata,
        [
            "--mapping-uri", mapping_uri,
            "--tmdb-movies-uri", tmdb_uri,
            "--output-uri", f"{root_uri}/snapshots/movie-metadata/fixture-v1",
            "--input-snapshot", INPUT_SNAPSHOT,
        ],
        spark, monkeypatch, capsys,
    )
    published_metadata = spark.read.parquet(metadata_uri)
    movie_metadata_revision = published_metadata.first()["movie_metadata_revision"]

    _run_job(
        als_serving,
        [
            "--ratings-uri", ratings_uri,
            "--mapping-uri", mapping_uri,
            "--movie-metadata-uri", metadata_uri,
            "--mapping-revision", mapping_revision,
            "--movie-metadata-revision", movie_metadata_revision,
            "--input-snapshot", INPUT_SNAPSHOT,
            "--output-uri", root_uri,
            "--model-version", MODEL_VERSION,
            "--rank", "2",
            "--reg-param", "0.1",
            "--max-iter", "2",
        ],
        spark, monkeypatch, capsys,
    )
    als = load_als_artifacts(spark, root_uri, MODEL_VERSION)

    content_summary = json.loads(
        _run_job(
            content_serving,
            [
                "--mapping-snapshot-uri", mapping_root,
                "--mapping-revision", mapping_revision,
                "--tmdb-snapshot-uri", tmdb_uri,
                "--tmdb-snapshot-revision", tmdb_snapshot_revision,
                "--movie-metadata-uri", metadata_uri,
                "--movie-metadata-revision", movie_metadata_revision,
                "--input-snapshot", INPUT_SNAPSHOT,
                "--output-uri", root_uri,
                "--content-version", CONTENT_VERSION,
                "--implementation-revision", "e2e-fixture-revision",
            ],
            spark, monkeypatch, capsys,
        )
    )

    content = load_content_artifacts(spark, root_uri, CONTENT_VERSION)
    metadata = content.metadata
    assert content_summary["version_uri"] == content.version_uri
    assert content_summary["content_revision"] == metadata["content_revision"]
    assert content_summary["vocabulary_size"] == metadata["vocabulary_size"]

    assert metadata["mapping_revision"] == als.metadata["mapping_revision"] == mapping_revision
    assert (
        metadata["movie_metadata_revision"]
        == als.metadata["movie_metadata_revision"]
        == movie_metadata_revision
    )
    assert metadata["tmdb_snapshot_revision"] == tmdb_snapshot_revision
    assert als.metadata["input_snapshot"] == metadata["input_snapshot"] == INPUT_SNAPSHOT
    assert {row["input_snapshot"] for row in published_metadata.collect()} == {INPUT_SNAPSHOT}
    assert metadata["input_uri"] == mapping_root
    assert metadata["implementation_revision"] == "e2e-fixture-revision"
    assert published_metadata.count() == mapping.count() == 4
    assert metadata["service_movie_count"] == 4
    assert metadata["supported_movie_count"] == 3

    # Read the published features back from disk: the ambiguous movie is a
    # content candidate and only the genre-less movie has an empty vector.
    supported = {row["service_movie_id"] for row in content.features.collect() if row["indices"]}
    assert supported == {10, 20, 40}
    assert {row["service_movie_id"] for row in content.features.collect()} == set(statuses)
