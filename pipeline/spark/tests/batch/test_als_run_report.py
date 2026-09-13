"""Run-level attribution, validation and completion semantics for ALS baseline."""

import importlib
import json
import math
from copy import deepcopy
from pathlib import Path

import pytest
from pyspark.sql import SparkSession


@pytest.fixture(scope="module")
def spark():
    session = (
        SparkSession.builder.master("local[1]")
        .appName("als-run-report-test")
        .config("spark.ui.enabled", "false")
        .config("spark.sql.shuffle.partitions", "1")
        .getOrCreate()
    )
    session.sparkContext.setLogLevel("ERROR")
    yield session
    session.stop()


def report_module():
    return importlib.import_module("batch.train.run_report")


def successful_report():
    return {
        "report_version": "als-run-v1",
        "run_id": "run-20260909-a1",
        "status": "SUCCEEDED",
        "input_snapshot": "sha256:fixture-v1",
        "implementation_revision": "abc123",
        "settings": {"user_chunk_size": 2000},
        "selection_policy": {
            "catalog": "movielens",
            "primary_metric": "ndcg_at_10",
            "secondary_metric": "recall_at_10",
            "tie_breakers": ["rank", "max_iter", "reg_param"],
        },
        "candidates": [
            {
                "artifact_stage": "MODEL_SELECTION",
                "model_version": "selection-a",
                "artifact_uri": "/models/als/selection-a",
                "config": {"rank": 16, "reg_param": 0.1, "max_iter": 2},
                "validation": {
                    "movielens": {"ndcg_at_10": 0.2, "recall_at_10": 0.3},
                    "service": {"ndcg_at_10": None, "recall_at_10": None},
                },
            },
            {
                "artifact_stage": "MODEL_SELECTION",
                "model_version": "selection-b",
                "artifact_uri": "/models/als/selection-b",
                "config": {"rank": 32, "reg_param": 0.1, "max_iter": 2},
                "validation": {
                    "movielens": {"ndcg_at_10": 0.4, "recall_at_10": 0.5},
                    "service": {"ndcg_at_10": 0.3, "recall_at_10": 0.4},
                },
                "evidence": {
                    "stage": "validation",
                    "model_version": "selection-b",
                    "candidates_uri": "/models/als-runs/run-20260909-a1/validation/candidates",
                    "per_user_uri": "/models/als-runs/run-20260909-a1/validation/per-user",
                    "candidate_rows": 20,
                    "per_user_rows": 8,
                },
            },
        ],
        "selected_model_version": "selection-b",
        "final_evaluation": {
            "artifact_stage": "FINAL_EVALUATION",
            "model_version": "final-b",
            "artifact_uri": "/models/als/final-b",
            "test": {
                "movielens": {"ndcg_at_10": 0.35, "recall_at_10": 0.45},
                "service": {"ndcg_at_10": None, "recall_at_10": None},
            },
            "evidence": {
                "stage": "test",
                "model_version": "final-b",
                "candidates_uri": "/models/als-runs/run-20260909-a1/test/candidates",
                "per_user_uri": "/models/als-runs/run-20260909-a1/test/per-user",
                "candidate_rows": 20,
                "per_user_rows": 8,
            },
        },
        "serving": {
            "artifact_stage": "SERVING",
            "model_version": "serving-b",
            "artifact_uri": "/models/als/serving-b",
        },
        "failure": None,
    }


def test_json_round_trip_preserves_none_and_model_attribution():
    module = report_module()
    report = successful_report()
    serialized = module.serialize_run_report(report)
    assert json.loads(serialized) == report
    assert module.deserialize_run_report(serialized) == report
    assert '"NaN"' not in serialized
    assert module.run_report_uri("/models/", report["run_id"]) == (
        "/models/als-runs/run-20260909-a1"
    )


@pytest.mark.parametrize("value", [math.nan, math.inf, -math.inf])
def test_non_finite_numbers_are_rejected_at_any_depth(value):
    report = successful_report()
    report["candidates"][0]["validation"]["service"]["ndcg_at_10"] = value
    with pytest.raises(ValueError, match="finite"):
        report_module().serialize_run_report(report)


def test_test_metrics_belong_to_final_model_and_not_serving_model():
    report = successful_report()
    report["final_evaluation"]["model_version"] = report["serving"]["model_version"]
    with pytest.raises(ValueError, match="(?i)(version|serving|final)"):
        report_module().validate_run_report(report, require_complete=True)


@pytest.mark.parametrize(
    "mutation",
    [
        lambda report: report.update(selected_model_version="not-a-candidate"),
        lambda report: report["candidates"][1].update(model_version="selection-a"),
        lambda report: report["candidates"][0].update(artifact_stage="SERVING"),
        lambda report: report["final_evaluation"].update(artifact_stage="MODEL_SELECTION"),
        lambda report: report["serving"].update(artifact_stage="FINAL_EVALUATION"),
    ],
)
def test_invalid_or_ambiguous_model_references_are_rejected(mutation):
    report = successful_report()
    mutation(report)
    with pytest.raises(ValueError):
        report_module().validate_run_report(report, require_complete=True)


def test_failed_report_records_stage_but_cannot_be_marked_complete():
    report = successful_report()
    report.update(
        status="FAILED",
        final_evaluation=None,
        serving=None,
        failure={"stage": "validation", "error_type": "RuntimeError"},
    )
    report_module().validate_run_report(report, require_complete=False)
    with pytest.raises(ValueError, match="complete"):
        report_module().validate_run_report(report, require_complete=True)


@pytest.mark.parametrize("value", [None, 0, -1, True])
def test_complete_report_requires_positive_explicit_user_chunk_size(value):
    report = successful_report()
    if value is None:
        report["settings"].pop("user_chunk_size")
    else:
        report["settings"]["user_chunk_size"] = value

    with pytest.raises(ValueError, match="(?i)user.*chunk.*size"):
        report_module().validate_run_report(report, require_complete=True)


def test_completed_report_requires_all_references_and_no_failure():
    for field in ("candidates", "selected_model_version", "final_evaluation", "serving"):
        report = successful_report()
        report[field] = None
        with pytest.raises(ValueError, match="complete"):
            report_module().validate_run_report(report, require_complete=True)
    report = successful_report()
    report["failure"] = {"stage": "report", "error_type": "RuntimeError"}
    with pytest.raises(ValueError, match="complete"):
        report_module().validate_run_report(report, require_complete=True)


def test_existing_run_is_never_overwritten(spark, tmp_path):
    module = report_module()
    output_uri = tmp_path.as_uri()
    report = successful_report()
    first_uri = module.write_run_report(spark, output_uri, report, complete=True)
    before = deepcopy(module.load_run_report(spark, first_uri, require_complete=False))
    changed = successful_report()
    changed["implementation_revision"] = "changed"
    with pytest.raises(FileExistsError):
        module.write_run_report(spark, output_uri, changed, complete=True)
    assert module.load_run_report(spark, first_uri, require_complete=False) == before


def test_incomplete_write_has_no_complete_marker(spark, tmp_path):
    module = report_module()
    report = successful_report()
    module.write_run_report(spark, tmp_path.as_uri(), report, complete=False)
    run_uri = module.run_report_uri(tmp_path.as_uri(), report["run_id"])
    assert module.load_run_report(spark, run_uri, require_complete=False) == report
    with pytest.raises(ValueError, match="complete"):
        module.load_run_report(spark, run_uri, require_complete=True)


def test_complete_load_revalidates_every_referenced_model(spark, tmp_path, monkeypatch):
    module = report_module()
    report = successful_report()
    report_uri = module.write_run_report(spark, tmp_path.as_uri(), report, complete=True)
    missing = FileNotFoundError("referenced ALS artifact is missing")
    load_artifact = pytest.fail

    def reject_missing(*_args, **_kwargs):
        raise missing

    load_artifact = reject_missing
    monkeypatch.setattr(module, "load_als_artifacts", load_artifact, raising=False)

    with pytest.raises(FileNotFoundError) as caught:
        module.load_run_report(spark, report_uri, require_complete=True)

    assert caught.value is missing


@pytest.mark.parametrize(
    ("section", "expected_stage"),
    [("selected", "validation"), ("final", "test")],
)
def test_complete_report_requires_attributed_evaluation_evidence(section, expected_stage):
    module = report_module()
    report = successful_report()
    if section == "selected":
        selected = next(
            item
            for item in report["candidates"]
            if item["model_version"] == report["selected_model_version"]
        )
        selected.pop("evidence")
    else:
        report["final_evaluation"].pop("evidence")

    with pytest.raises(ValueError, match=f"(?i){expected_stage}.*evidence"):
        module.validate_run_report(report, require_complete=True)


class TrackingFileSystem:
    def __init__(self, delegate, events, *, delete_failure=None, delete_result=None):
        self.delegate = delegate
        self.events = events
        self.delete_failure = delete_failure
        self.delete_result = delete_result

    def __getattr__(self, name):
        return getattr(self.delegate, name)

    def delete(self, path, recursive):
        if str(path).endswith("/_RESERVED"):
            self.events.append("delete:_RESERVED")
            if self.delete_failure is not None:
                raise self.delete_failure
            if self.delete_result is not None:
                return self.delete_result
        return self.delegate.delete(path, recursive)


def track_reserved_delete(module, monkeypatch, events, **options):
    original_hadoop_path = module._hadoop_path

    def tracked_hadoop_path(spark, uri):
        path, file_system = original_hadoop_path(spark, uri)
        return path, TrackingFileSystem(file_system, events, **options)

    monkeypatch.setattr(module, "_hadoop_path", tracked_hadoop_path)


def test_reserved_run_publishes_success_marker_as_the_last_mutation(
    spark, tmp_path, monkeypatch
):
    module = report_module()
    report = successful_report()
    output_uri = tmp_path.as_uri()
    module.reserve_run_report(spark, output_uri, report["run_id"])
    events = []
    original_write_text = module._write_text

    def tracked_write_text(spark, uri, value):
        events.append(f"write:{Path(uri).name}")
        return original_write_text(spark, uri, value)

    monkeypatch.setattr(module, "_write_text", tracked_write_text)
    track_reserved_delete(module, monkeypatch, events)

    module.write_run_report(spark, output_uri, report, complete=True)

    assert events == ["write:report.json", "delete:_RESERVED", "write:_SUCCESS"]


def protected_artifacts(tmp_path):
    files = {
        tmp_path / "als-runs" / "existing-success" / "report.json": b"success-report",
        tmp_path / "als-runs" / "existing-success" / "_SUCCESS": b"",
        tmp_path / "als-runs" / "existing-failed" / "report.json": b"failed-report",
        tmp_path / "als" / "existing-model" / "metadata" / "part-00000": b"model",
    }
    for path, value in files.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(value)
    return files


@pytest.mark.parametrize("failure_point", ["report", "reservation-delete", "success"])
def test_failed_completion_never_leaves_a_loadable_complete_run_and_preserves_artifacts(
    spark, tmp_path, monkeypatch, failure_point
):
    module = report_module()
    report = successful_report()
    output_uri = tmp_path.as_uri()
    run_uri = module.reserve_run_report(spark, output_uri, report["run_id"])
    protected = protected_artifacts(tmp_path)
    injected = OSError(f"injected {failure_point} failure")
    original_write_text = module._write_text

    def failing_write_text(spark, uri, value):
        if failure_point == "report" and uri.endswith("/report.json"):
            raise injected
        if failure_point == "success" and uri.endswith("/_SUCCESS"):
            raise injected
        return original_write_text(spark, uri, value)

    monkeypatch.setattr(module, "_write_text", failing_write_text)
    if failure_point == "reservation-delete":
        track_reserved_delete(module, monkeypatch, [], delete_failure=injected)

    with pytest.raises(OSError) as caught:
        module.write_run_report(spark, output_uri, report, complete=True)

    assert caught.value is injected
    assert not (Path(run_uri.removeprefix("file:")) / "_SUCCESS").exists()
    with pytest.raises((FileNotFoundError, ValueError)):
        module.load_run_report(spark, run_uri, require_complete=True)
    assert {path: path.read_bytes() for path in protected} == protected


def test_false_reservation_delete_result_is_a_failed_incomplete_publication(
    spark, tmp_path, monkeypatch
):
    module = report_module()
    report = successful_report()
    output_uri = tmp_path.as_uri()
    run_uri = module.reserve_run_report(spark, output_uri, report["run_id"])
    track_reserved_delete(module, monkeypatch, [], delete_result=False)

    with pytest.raises(OSError, match="(?i)reservation|delete|remove"):
        module.write_run_report(spark, output_uri, report, complete=True)

    assert not (Path(run_uri.removeprefix("file:")) / "_SUCCESS").exists()
