"""GREEN prerequisite: the 119 fixture must work before a feature RED counts."""

import hashlib
import importlib
import json
from pathlib import Path

import pytest
from pyspark.sql import SparkSession
from pyspark.sql.types import LongType

from batch.train.input_snapshot import validate_input_snapshot
from batch.train.settings import AlsJobSettings
from batch.train.splits import split_ratings
from tests.fixtures.als_integration import (
    INTEGRATION_ROWS,
    MATCHED_ITEMS,
    SERVICE_ID_BASE,
    expected_splits,
    frame_rows,
    write_prepared_fixture,
)


@pytest.fixture(scope="module")
def spark():
    session = (
        SparkSession.builder.master("local[1]")
        .appName("als-119-fixture-test")
        .config("spark.ui.enabled", "false")
        .config("spark.sql.shuffle.partitions", "1")
        .config("spark.sql.session.timeZone", "UTC")
        .getOrCreate()
    )
    session.sparkContext.setLogLevel("ERROR")
    yield session
    session.stop()


@pytest.fixture(scope="module")
def prepared_fixture(spark, tmp_path_factory):
    root = tmp_path_factory.mktemp("als-integration")
    with pytest.MonkeyPatch.context() as patch:
        return write_prepared_fixture(spark, root, patch)


def test_csv_outputs_manifest_and_split_oracle_are_valid(spark, prepared_fixture):
    prepared, snapshot, inputs = prepared_fixture
    assert {path.name for path in prepared.iterdir()} == {
        "ratings",
        "movies",
        "movie-id-map",
        "service-movie-id-map",
        "validation-report",
    }
    assert inputs.ratings_count == len(INTEGRATION_ROWS) == 64
    assert inputs.matched_service_movie_count == len(MATCHED_ITEMS) == 13
    assert inputs.service_movie_count == 14
    assert isinstance(inputs.service_movie_map.schema["service_movie_id"].dataType, LongType)
    matched = inputs.service_movie_map.filter("mapping_status = 'MATCHED'").collect()
    assert {row.movielens_movie_id for row in matched} == MATCHED_ITEMS
    assert all(row.service_movie_id == SERVICE_ID_BASE + row.movielens_movie_id for row in matched)
    report = spark.read.json(str(prepared / "validation-report")).first()
    assert report.all_checks_passed
    splits = split_ratings(inputs.ratings)
    expected = expected_splits()
    for name, rows in expected.items():
        assert frame_rows(getattr(splits, name)) == rows
    assert {name: len(rows) for name, rows in expected.items()} == {
        "train": 50,
        "validation": 7,
        "test": 7,
    }
    manifest_bytes = (prepared.parent / "manifest.json").read_bytes()
    assert snapshot == "sha256:" + hashlib.sha256(manifest_bytes).hexdigest()
    for relative_path, digest in json.loads(manifest_bytes)["files"].items():
        assert hashlib.sha256((prepared.parent / relative_path).read_bytes()).hexdigest() == digest
    manifest = validate_input_snapshot(
        spark,
        input_uri=prepared.as_uri(),
        manifest_uri=(prepared.parent / "manifest.json").as_uri(),
        expected_snapshot=snapshot,
        inputs=inputs,
    )
    assert set(manifest["sources"]) == {
        "ratings",
        "movies",
        "links",
        "service_movie_export",
    }


def test_small_real_als_run_saves_reloads_and_attributes_versions(
    spark, prepared_fixture
):
    prepared, snapshot, inputs = prepared_fixture
    output = prepared.parent / "output"
    settings = AlsJobSettings.from_sources(
        environ={},
        argv=[
            "--mode",
            "run",
            "--input-uri",
            prepared.as_uri(),
            "--output-uri",
            output.as_uri(),
            "--input-manifest-uri",
            (prepared.parent / "manifest.json").as_uri(),
            "--input-snapshot",
            snapshot,
            "--run-id",
            "fixture-run",
            "--implementation-revision",
            "synthetic-test-fixture",
            "--movie-metadata-revision",
            "sha256:" + "b" * 64,
            "--candidate-size",
            "10",
            "--als-config",
            "2:0.1:1",
            "--als-config",
            "3:0.1:1",
        ],
    )
    baseline = importlib.import_module("batch.train.baseline")
    run_report = importlib.import_module("batch.train.run_report")

    validate_input_snapshot(
        spark,
        input_uri=prepared.as_uri(),
        manifest_uri=(prepared.parent / "manifest.json").as_uri(),
        expected_snapshot=snapshot,
        inputs=inputs,
    )

    result = baseline.run_baseline(spark, inputs, settings)
    report_uri = run_report.run_report_uri(output.as_uri(), "fixture-run")
    report = run_report.load_run_report(spark, report_uri, require_complete=True)

    assert result.serving_model_version == report["serving"]["model_version"]
    assert len(report["candidates"]) == 2
    assert {item["artifact_stage"] for item in report["candidates"]} == {"MODEL_SELECTION"}
    assert report["final_evaluation"]["artifact_stage"] == "FINAL_EVALUATION"
    assert report["serving"]["artifact_stage"] == "SERVING"
    assert report["final_evaluation"]["model_version"] != result.serving_model_version
    assert report["final_evaluation"]["test"]["movielens"]["evaluated_users"] > 0
    assert report["status"] == "SUCCEEDED"
    serving = baseline.load_als_artifacts(
        spark, output.as_uri(), result.serving_model_version
    ).metadata
    assert serving.mapping_revision == inputs.mapping_revision
    assert serving.movie_metadata_revision == "sha256:" + "b" * 64


def test_cluster_submit_wrapper_exists_at_spark_project_root():
    assert (Path(__file__).parents[2] / "als_baseline.py").is_file()
