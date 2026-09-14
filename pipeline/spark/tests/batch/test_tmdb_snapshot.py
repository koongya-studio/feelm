from __future__ import annotations

import json
from pathlib import Path

import pytest
from pyspark.sql import SparkSession

from batch.prepare import tmdb_snapshot as tmdb_snapshot_module
from batch.prepare.tmdb_snapshot import (
    build_tmdb_snapshot,
    publish_tmdb_snapshot,
    read_raw_keywords,
    read_raw_movies,
)

REVISION = "sha256:" + "a" * 64
# Characters that Python ``str.strip()`` removes but Spark ``trim`` keeps.
WIDE_SPACE = "　"


@pytest.fixture(scope="module")
def spark():
    session = (
        SparkSession.builder.master("local[1]")
        .appName("tmdb-snapshot-test")
        .config("spark.ui.enabled", "false")
        .config("spark.sql.shuffle.partitions", "2")
        .getOrCreate()
    )
    session.sparkContext.setLogLevel("ERROR")
    yield session
    session.stop()


def _write_movie(root: Path, file_id: int, body: dict) -> None:
    root.mkdir(parents=True, exist_ok=True)
    (root / f"{file_id}.json").write_text(json.dumps(body, indent=2), encoding="utf-8")


def _write_keywords(root: Path, file_id: int, body: dict) -> None:
    root.mkdir(parents=True, exist_ok=True)
    (root / f"{file_id}.json").write_text(json.dumps(body), encoding="utf-8")


def _raw_fixture(tmp_path: Path) -> tuple[Path, Path]:
    movies, keywords = tmp_path / "raw-tmdb", tmp_path / "raw-keywords"
    _write_movie(
        movies,
        100,
        {
            "details": {
                "id": 100,
                "title": f"\t Movie A {WIDE_SPACE}",
                "original_title": "A",
                "original_language": f"{WIDE_SPACE}EN\t",
                "release_date": f"{WIDE_SPACE}1995-10-30 ",
                "runtime": 81,
                "genres": [
                    {"id": 35, "name": f"\tComedy{WIDE_SPACE}"},
                    {"id": 18, "name": "Drama"},
                    {"id": 35, "name": "Comedy"},
                ],
                "overview": "ignored",
            },
            "credits": {
                "cast": [
                    {"id": 7, "order": 2},
                    {"id": 5, "order": 0},
                    {"id": 9, "order": 1},
                    {"id": 5, "order": 3},
                    {"id": 11, "order": 4},
                    {"id": 12, "order": 5},
                    {"id": 13, "order": 6},
                    {"id": -1, "order": 0},
                ],
                "crew": [
                    {"id": 77, "job": "Director"},
                    {"id": 78, "job": "Writer"},
                    {"id": 77, "job": "Director"},
                ],
            },
            "providers": {"results": {}},
        },
    )
    _write_movie(
        movies,
        200,
        {
            "details": {
                "id": 200,
                "title": "B",
                "original_language": "",
                "release_date": "",
                "runtime": 0,
                "genres": [],
            }
        },
    )
    _write_movie(
        movies,
        300,
        {
            "details": {
                "id": 300,
                "title": "C",
                "original_language": "ko",
                "release_date": "2020-01-01",
                "runtime": None,
                "genres": [
                    {"id": 18, "name": "Drama"},
                    {"id": 0, "name": "Invalid"},
                    {"id": 99, "name": f"{WIDE_SPACE}\t"},
                ],
            },
            "credits": {"cast": [], "crew": [{"id": 5, "job": None}]},
        },
    )
    _write_movie(movies, 400, {"details": {"id": 400, "title": "Outside service catalog"}})
    _write_keywords(
        keywords,
        100,
        {"id": 100, "keywords": [{"id": 3, "name": "a"}, {"id": 1, "name": "b"}, {"id": 3}]},
    )
    _write_keywords(keywords, 300, {"id": 300, "results": [{"id": 8, "name": "c"}]})
    return movies, keywords


def _mapping(spark, tmdb_ids):
    return spark.createDataFrame(
        [(index * 10, tmdb_id, REVISION) for index, tmdb_id in enumerate(tmdb_ids, 1)],
        "service_movie_id long, tmdb_id int, mapping_revision string",
    )


def test_tmdb_snapshot_normalizes_details_credits_keywords(spark, tmp_path) -> None:
    movies, keywords = _raw_fixture(tmp_path)
    snapshot = build_tmdb_snapshot(
        read_raw_movies(spark, str(movies)),
        read_raw_keywords(spark, str(keywords)),
        _mapping(spark, [100, 200, 300]),
    )
    rows = {row["id"]: row.asDict(recursive=True) for row in snapshot.collect()}

    assert sorted(rows) == [100, 200, 300]
    first = rows[100]
    assert first["title"] == "Movie A"
    assert first["original_language"] == "en"
    assert (first["release_year"], first["runtime_minutes"]) == (1995, 81)
    assert first["genre_ids"] == [18, 35]
    assert [genre["name"] for genre in first["genres"]] == ["Comedy", "Drama", "Comedy"]
    assert first["director_ids"] == [77]
    assert first["top5_cast_ids"] == [5, 9, 7, 11, 12]
    assert first["top5_cast_source_order"] == [0, 1, 2, 4, 5]
    assert first["keyword_ids"] == [1, 3]
    assert first["keywords_present"] is True
    assert first["feature_eligible"] is True

    missing = rows[200]
    assert missing["original_language"] is None
    assert missing["release_year"] is None
    assert missing["runtime_minutes"] is None
    assert missing["genres"] == []
    assert missing["director_ids"] == [] and missing["top5_cast_ids"] == []
    assert missing["top5_cast_source_order"] == []
    assert missing["keyword_ids"] == [] and missing["keywords_present"] is False
    assert missing["feature_eligible"] is False

    third = rows[300]
    assert third["genre_ids"] == [18]
    # A name of only non-ASCII whitespace is blank for Python ``str.strip()`` too.
    assert [genre["id"] for genre in third["genres"]] == [18]
    assert third["director_ids"] == []
    assert third["keyword_ids"] == [8]
    assert third["runtime_minutes"] is None
    assert third["feature_eligible"] is True
    revisions = {row["tmdb_snapshot_revision"] for row in rows.values()}
    assert len(revisions) == 1 and next(iter(revisions)).startswith("sha256:")

    again = build_tmdb_snapshot(
        read_raw_movies(spark, str(movies)),
        read_raw_keywords(spark, str(keywords)),
        _mapping(spark, [300, 200, 100]),
    )
    assert {row["tmdb_snapshot_revision"] for row in again.collect()} == revisions


@pytest.mark.parametrize("case", ["missing", "mismatch", "keyword_mismatch", "duplicate"])
def test_tmdb_snapshot_rejects_missing_duplicate_or_mismatched_movies(
    spark, tmp_path, case
) -> None:
    movies, keywords = _raw_fixture(tmp_path)
    tmdb_ids = [100, 200, 300]
    raw_movies = None
    if case == "missing":
        tmdb_ids.append(500)
        message = "every service movie must have a raw TMDb movie document"
    elif case == "mismatch":
        _write_movie(movies, 600, {"details": {"id": 601, "title": "Wrong"}})
        message = "raw TMDb movie file name and body id must match"
    elif case == "keyword_mismatch":
        _write_keywords(keywords, 200, {"id": 201, "keywords": []})
        message = "raw TMDb keyword file name and body id must match"
    else:
        copy_root = tmp_path / "raw-tmdb-copy"
        _write_movie(copy_root, 100, json.loads((movies / "100.json").read_text(encoding="utf-8")))
        raw_movies = read_raw_movies(spark, str(movies)).unionByName(
            read_raw_movies(spark, str(copy_root))
        )
        message = "raw TMDb documents must be unique per tmdb id"

    with pytest.raises(ValueError, match=message):
        build_tmdb_snapshot(
            raw_movies if raw_movies is not None else read_raw_movies(spark, str(movies)),
            read_raw_keywords(spark, str(keywords)),
            _mapping(spark, tmdb_ids),
        )


def test_tmdb_snapshot_publication_is_immutable(spark, tmp_path) -> None:
    movies, keywords = _raw_fixture(tmp_path)
    snapshot = build_tmdb_snapshot(
        read_raw_movies(spark, str(movies)),
        read_raw_keywords(spark, str(keywords)),
        _mapping(spark, [100, 200, 300]),
    )
    output_uri = (tmp_path / "snapshots" / "tmdb-20260907").as_uri()

    assert publish_tmdb_snapshot(snapshot, output_uri) == output_uri
    published = spark.read.parquet(output_uri)
    assert published.count() == 3
    with pytest.raises(FileExistsError, match="already exists"):
        publish_tmdb_snapshot(snapshot, output_uri)
    assert spark.read.parquet(output_uri).count() == 3
    assert [child.name for child in (tmp_path / "snapshots").iterdir()] == ["tmdb-20260907"]
    with pytest.raises(ValueError, match="immutable"):
        publish_tmdb_snapshot(snapshot, (tmp_path / "snapshots" / "latest").as_uri())


def test_tmdb_snapshot_publication_failure_leaves_no_output(spark, tmp_path, monkeypatch) -> None:
    movies, keywords = _raw_fixture(tmp_path)
    snapshot = build_tmdb_snapshot(
        read_raw_movies(spark, str(movies)),
        read_raw_keywords(spark, str(keywords)),
        _mapping(spark, [100, 200, 300]),
    )
    snapshots = tmp_path / "snapshots"
    output_uri = (snapshots / "tmdb-20260907").as_uri()

    def reject(*_args, **_kwargs):
        raise OSError("injected rename failure")

    with monkeypatch.context() as patch:
        patch.setattr(tmdb_snapshot_module, "_publish_directory", reject)
        with pytest.raises(OSError, match="injected rename failure"):
            publish_tmdb_snapshot(snapshot, output_uri)

    # Nothing at all is left behind, whatever the temporary path is named.
    assert list(snapshots.iterdir()) == []
    # The version is still publishable once the transient failure is gone.
    assert publish_tmdb_snapshot(snapshot, output_uri) == output_uri
    assert spark.read.parquet(output_uri).count() == 3
