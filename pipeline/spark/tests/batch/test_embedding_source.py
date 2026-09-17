from __future__ import annotations

import json
from pathlib import Path

import pytest
from pyspark.sql import SparkSession

from batch.embedding.source import (
    build_embedding_source,
    read_raw_embedding_keywords,
    read_raw_embedding_movies,
)

REVISION = "sha256:" + "a" * 64


@pytest.fixture(scope="module")
def spark():
    session = (
        SparkSession.builder.master("local[1]")
        .appName("embedding-source-test")
        .config("spark.ui.enabled", "false")
        .config("spark.sql.shuffle.partitions", "1")
        .getOrCreate()
    )
    session.sparkContext.setLogLevel("ERROR")
    yield session
    session.stop()


def _write(root: Path, name: str, payload: dict) -> None:
    root.mkdir(parents=True, exist_ok=True)
    (root / name).write_text(json.dumps(payload), encoding="utf-8")


def _inputs(tmp_path: Path) -> tuple[Path, Path]:
    movies, keywords = tmp_path / "movies", tmp_path / "keywords"
    _write(
        movies,
        "100.json",
        {
            "details": {
                "id": 100,
                "title": "  영화 A ",
                "original_title": "Movie A",
                "overview": "  첫 줄\n둘째 줄  ",
                "genres": [{"id": 1, "name": "드라마"}, {"id": 2, "name": "스릴러"}],
            },
            "credits": {
                "cast": [
                    {"id": 2, "name": "둘", "order": 1},
                    {"id": 1, "name": "하나", "order": 0},
                ],
                "crew": [{"id": 3, "name": "감독", "job": "Director"}],
            },
        },
    )
    _write(
        movies,
        "200.json",
        {"details": {"id": 200, "title": "Only title", "genres": []}, "credits": {}},
    )
    _write(keywords, "100.json", {"id": 100, "keywords": [{"id": 4, "name": "복수"}]})
    return movies, keywords


def _mapping(spark, rows):
    return spark.createDataFrame(
        rows, "service_movie_id long, tmdb_id int, mapping_revision string"
    )


def test_build_embedding_source_uses_backend_movie_ids_and_canonical_text(spark, tmp_path) -> None:
    movies, keywords = _inputs(tmp_path)
    source = build_embedding_source(
        read_raw_embedding_movies(spark, str(movies)),
        read_raw_embedding_keywords(spark, str(keywords)),
        _mapping(spark, [(11, 100, REVISION), (22, 200, REVISION)]),
    )
    rows = {row["movie_id"]: row.asDict() for row in source.collect()}

    assert sorted(rows) == [11, 22]
    assert rows[11]["tmdb_id"] == 100
    assert rows[11]["source_text"] == (
        "title: 영화 A\noriginal title: Movie A\ngenres: 드라마, 스릴러\n"
        "overview: 첫 줄 둘째 줄\ndirector: 감독\ncast: 하나, 둘\nkeywords: 복수"
    )
    assert len(rows[11]["input_text_sha256"]) == 64
    assert rows[22]["source_text"] == "title: Only title"


def test_build_embedding_source_rejects_missing_or_mismatched_raw_documents(
    spark, tmp_path
) -> None:
    movies, keywords = _inputs(tmp_path)
    mapping = _mapping(spark, [(11, 100, REVISION), (22, 300, REVISION)])
    with pytest.raises(ValueError, match="must have a raw TMDb movie document"):
        build_embedding_source(
            read_raw_embedding_movies(spark, str(movies)),
            read_raw_embedding_keywords(spark, str(keywords)),
            mapping,
        )

    _write(movies, "300.json", {"details": {"id": 301, "title": "wrong"}})
    with pytest.raises(ValueError, match="file name and body id must match"):
        build_embedding_source(
            read_raw_embedding_movies(spark, str(movies)),
            read_raw_embedding_keywords(spark, str(keywords)),
            mapping,
        )


def test_build_embedding_source_rejects_non_unique_mapping(spark, tmp_path) -> None:
    movies, keywords = _inputs(tmp_path)
    with pytest.raises(ValueError, match="service mapping must be unique"):
        build_embedding_source(
            read_raw_embedding_movies(spark, str(movies)),
            read_raw_embedding_keywords(spark, str(keywords)),
            _mapping(spark, [(11, 100, REVISION), (11, 200, REVISION)]),
        )
