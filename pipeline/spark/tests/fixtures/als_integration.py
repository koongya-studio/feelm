"""Synthetic 118 CSV inputs and an independent chronological split oracle."""

import csv
from datetime import datetime, timezone
from pathlib import Path

from batch.prepare.movie_id_mapping.io import read_csv, write_pipeline
from batch.prepare.movie_id_mapping.pipeline import build_pipeline
from batch.train.input_snapshot import create_input_manifest
from batch.train.inputs import load_training_inputs
from tests.fixtures.als_baseline import RATING_ROWS

USER = "movielens_user_id"
ITEM = "movielens_movie_id"
SERVICE_ID_BASE = 2**40


def integration_rows():
    rows = []
    for user, movie, rating, epoch, stamp in RATING_ROWS:
        # One holdout user has no positives; low ratings still belong in known.
        if user == 6 and epoch % 1000 >= 8:
            rating = 2.0
        rows.append((user, movie, rating, epoch, stamp))
    for user, movie, rating, epoch in [
        (7, 15, 1.0, 1_700_010_000),
        (7, 16, 5.0, 1_700_010_001),  # MATCHED cold validation item.
        (7, 17, 5.0, 1_700_010_002),  # Unmapped cold test item.
        (8, 18, 2.0, 1_700_011_000),  # Sparse train-only user.
    ]:
        rows.append((user, movie, rating, epoch, datetime.fromtimestamp(epoch, timezone.utc)))
    return rows


INTEGRATION_ROWS = integration_rows()
MATCHED_ITEMS = {*range(1, 13), 16}


def expected_splits():
    """No production split/metric code: fixture timestamps are unique per user."""
    result = {name: set() for name in ("train", "validation", "test")}
    for user in sorted({row[0] for row in INTEGRATION_ROWS}):
        rows = sorted((row for row in INTEGRATION_ROWS if row[0] == user), key=lambda r: r[3])
        for index, row in enumerate(rows):
            stage = (
                "train"
                if index < len(rows) - 2 or len(rows) < 3
                else ("validation" if index == len(rows) - 2 else "test")
            )
            result[stage].add(tuple(row[:4]))
    return result


def frame_rows(frame):
    return {tuple(row) for row in frame.select(USER, ITEM, "rating", "rated_at_epoch").collect()}


def write_prepared_fixture(spark, root: Path, monkeypatch):
    """Exercise real CSV parsing, mapping, five outputs and ALS input validation."""
    source = root / "source"
    source.mkdir(parents=True)
    csv_inputs = {
        "ratings": (
            ["userId", "movieId", "rating", "timestamp"],
            [row[:4] for row in INTEGRATION_ROWS],
        ),
        "movies": (
            ["movieId", "title", "genres"],
            [(i, f"Synthetic {i}", "Drama") for i in range(1, 19)],
        ),
        "links": (["movieId", "tmdbId"], [(i, 20000 + i) for i in range(1, 19)]),
        "service-movies": (
            ["service_movie_id", "tmdb_id"],
            [(SERVICE_ID_BASE + i, 20000 + i) for i in sorted(MATCHED_ITEMS)]
            + [(SERVICE_ID_BASE + 99, 29999)],
        ),
    }
    for name, (header, rows) in csv_inputs.items():
        with (source / f"{name}.csv").open("w", newline="", encoding="utf-8") as stream:
            writer = csv.writer(stream)
            writer.writerow(header)
            writer.writerows(rows)

    cached = []
    frame_type = type(spark.range(0))
    original_cache = frame_type.cache

    def cache(frame):
        cached.append(frame)
        return original_cache(frame)

    prepared_root = root / "prepared"
    # The 118 builder owns intermediate caches inaccessible from PipelineResult.
    # Track exactly this fixture's allocations without clearing a shared session.
    with monkeypatch.context() as patch:
        patch.setattr(frame_type, "cache", cache)
        try:
            result = build_pipeline(
                **{
                    f"{name.replace('-', '_')}_source": read_csv(spark, str(source / f"{name}.csv"))
                    for name in csv_inputs
                }
            )
            assert result.validation_report["all_checks_passed"]
            write_pipeline(result, str(prepared_root), "errorifexists")
        finally:
            for frame in cached:
                frame.unpersist()
    assert cached, "fixture cache ownership tracking must observe the 118 builder"

    snapshot = create_input_manifest(
        spark,
        input_uri=prepared_root.as_uri(),
        manifest_uri=(root / "manifest.json").as_uri(),
        source_uris={
            "ratings": (source / "ratings.csv").as_uri(),
            "movies": (source / "movies.csv").as_uri(),
            "links": (source / "links.csv").as_uri(),
            "service_movie_export": (source / "service-movies.csv").as_uri(),
        },
        preprocessing_revision="synthetic-test-fixture",
        preprocessing_settings={
            "rating_min": 0.5,
            "rating_max": 5.0,
            "rating_step": 0.5,
            "latest_rating_per_user_movie": True,
        },
    )
    inputs = load_training_inputs(
        spark,
        str(prepared_root / "ratings"),
        str(prepared_root / "service-movie-id-map"),
    )
    return prepared_root, snapshot, inputs
