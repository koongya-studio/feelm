"""Synthetic GBT benchmark safety and local Spark fixture tests."""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from unittest.mock import Mock

import pytest
from py4j.protocol import Py4JJavaError
from pyspark.ml.feature import VectorAssembler
from pyspark.sql import SparkSession

from batch.benchmark import gbt
from batch.benchmark.gbt import (
    compare_results,
    content_fingerprint,
    prepare_input,
    read_report,
    synthetic_frame,
)
from jobs.batch import gbt_benchmark as job


@pytest.fixture(scope="module")
def spark():
    os.environ["PYSPARK_PYTHON"] = sys.executable
    session = SparkSession.builder.master("local[2]").appName("gbt-benchmark-test").getOrCreate()
    yield session
    session.stop()


def test_synthetic_features_are_deterministic(spark):
    first = synthetic_frame(spark, 20, 4, 2, 42).orderBy("id").collect()
    second = synthetic_frame(spark, 20, 4, 4, 42).orderBy("id").collect()
    assert first == second
    assert len(first) == 20
    assert all(row.label is not None for row in first)
    assert content_fingerprint(synthetic_frame(spark, 20, 4, 2, 42), 4) == (
        content_fingerprint(synthetic_frame(spark, 20, 4, 4, 42), 4)
    )


def test_small_local_xgboost_fit(spark):
    xgb_spark = pytest.importorskip("xgboost.spark")
    columns = gbt.feature_names(4)
    frame = VectorAssembler(inputCols=columns, outputCol="features").transform(
        synthetic_frame(spark, 100, 4, 2, 42)
    )
    estimator = xgb_spark.SparkXGBRegressor(
        features_col="features",
        label_col="label",
        num_workers=1,
        n_estimators=2,
        max_depth=2,
        objective="reg:squarederror",
    )
    assert estimator.fit(frame).__class__.__name__ == "SparkXGBRegressorModel"


@pytest.mark.skipif(sys.platform == "win32", reason="Windows Hadoop local writes require winutils")
def test_prepare_rejects_existing_output_and_preserves_it(spark, tmp_path):
    output = (tmp_path / "prepared").as_uri()
    original = prepare_input(
        spark, output_uri=output, input_id="fixture", rows=20, features=4, partitions=2, seed=42
    )
    with pytest.raises(FileExistsError):
        prepare_input(
            spark,
            output_uri=output,
            input_id="fixture",
            rows=20,
            features=4,
            partitions=2,
            seed=42,
        )
    assert json.loads((tmp_path / "prepared" / "metadata.json").read_text())["rows"] == 20
    assert original["input_snapshot"].startswith("sha256:")


def _report(workers: int, seconds: float) -> dict:
    return {
        "version": "gbt-distributed-benchmark-v1",
        "status": "SUCCEEDED",
        "input": {"id": "fixture", "snapshot": "sha256:" + "b" * 64, "rows": 20, "features": 4},
        "model": {"n_estimators": 10, "seed": 42},
        "resources": {
            "requested_worker_count": workers,
            "observed_probe_hosts": [f"host-{index}" for index in range(workers)],
            "executor_cores": "2",
            "task_cpus": "2",
            "total_executor_cores": str(workers * 2),
        },
        "runtime": {
            "application_id": f"app-{workers}",
            "implementation_revision": "a" * 40,
            "spark_version": "4.1.3",
            "python_version": "3.10.12",
            "xgboost_version": "2.1.4",
        },
        "timing": {"training_seconds": seconds},
    }


def test_compare_accepts_same_input_and_rejects_differences():
    one, two = _report(1, 20.0), _report(2, 10.0)
    assert compare_results(one, two)["speedup"] == 2.0
    two["model"]["seed"] = 43
    with pytest.raises(ValueError, match="model"):
        compare_results(one, two)
    two["model"]["seed"] = 42
    two["input"]["snapshot"] = "sha256:" + "c" * 64
    with pytest.raises(ValueError, match="input"):
        compare_results(one, two)


def test_compare_rejects_runtime_or_resource_mismatch():
    one, two = _report(1, 20.0), _report(2, 10.0)
    two["resources"]["task_cpus"] = "1"
    with pytest.raises(ValueError, match="core settings"):
        compare_results(one, two)
    two = _report(2, 10.0)
    two["runtime"]["xgboost_version"] = "3.0.0"
    with pytest.raises(ValueError, match="xgboost_version"):
        compare_results(one, two)


def test_incomplete_report_is_not_read(spark, tmp_path):
    root = tmp_path / "incomplete"
    root.mkdir()
    (root / "report.json").write_text(json.dumps(_report(1, 1.0)))
    with pytest.raises((FileNotFoundError, Py4JJavaError)):
        read_report(spark, root.as_uri())


def test_preflight_rejects_bad_bounds_before_spark_session(monkeypatch):
    create = Mock()
    monkeypatch.setattr(job, "create_spark_session", create)
    with pytest.raises(ValueError, match="rows"):
        job.main(["prepare", "--output-uri", "file:///tmp/out", "--input-id", "x", "--rows", "0"])
    with pytest.raises(ValueError, match="overlap"):
        job.main(
            [
                "run",
                "--input-root", "file:///tmp/input",
                "--input-snapshot", "sha256:" + "a" * 64,
                "--result-uri", "file:///tmp/input/result",
                "--worker-count", "1",
                "--revision", "a" * 40,
                "--sequence", "1",
            ]
        )
    create.assert_not_called()


def test_worker_mismatch_does_not_fit_or_publish(monkeypatch):
    frame = Mock(columns=["id", "features", "label"])
    frame.count.return_value = 20
    session = Mock()
    session.read.parquet.return_value = frame
    session.conf.get.side_effect = lambda key, default=None: {
        "spark.executor.cores": "2",
        "spark.task.cpus": "2",
        "spark.cores.max": "4",
    }.get(key, default)
    monkeypatch.setattr(gbt, "verify_deployed_revision", Mock())
    monkeypatch.setattr(gbt, "_require_new_uri", Mock())
    monkeypatch.setattr(
        gbt,
        "verify_input",
        Mock(
            return_value={
                "rows": 20,
                "features": 4,
                "input_id": "fixture",
                "content_fingerprint": "x",
            }
        ),
    )
    monkeypatch.setattr(gbt, "_probe_hosts", Mock(return_value=["only-one-host"]))
    publish = Mock()
    monkeypatch.setattr(gbt, "publish_directory", publish)
    with pytest.raises(RuntimeError, match="requested 2 Workers"):
        gbt.run_benchmark(
            session,
            input_root="file:///tmp/input",
            input_snapshot="sha256:" + "a" * 64,
            result_uri="file:///tmp/result",
            worker_count=2,
            revision="a" * 40,
            sequence=1,
            estimators=2,
            max_depth=2,
            seed=42,
        )
    publish.assert_not_called()


def test_report_write_failure_cleans_staging_and_can_retry(monkeypatch):
    xgb_spark = pytest.importorskip("xgboost.spark")
    frame = Mock(columns=["id", "features", "label"])
    frame.count.return_value = 20
    session = Mock(version="4.1.3")
    session.read.parquet.return_value = frame
    session.sparkContext.applicationId = "app-fixture"
    session.conf.get.side_effect = lambda key, default=None: {
        "spark.executor.cores": "2",
        "spark.task.cpus": "2",
        "spark.cores.max": "2",
    }.get(key, default)
    monkeypatch.setattr(gbt, "verify_deployed_revision", Mock())
    monkeypatch.setattr(gbt, "_require_new_uri", Mock())
    monkeypatch.setattr(
        gbt,
        "verify_input",
        Mock(
            return_value={
                "rows": 20,
                "features": 4,
                "input_id": "fixture",
                "content_fingerprint": "sha256:" + "b" * 64,
            }
        ),
    )
    monkeypatch.setattr(gbt, "_probe_hosts", Mock(return_value=["one-host"]))
    monkeypatch.setattr(xgb_spark, "SparkXGBRegressor", Mock(return_value=Mock(fit=Mock())))
    monkeypatch.setattr(gbt, "stage_uri", Mock(return_value="file:///tmp/.staged"))
    filesystem = Mock(mkdirs=Mock(return_value=True))
    monkeypatch.setattr(gbt, "hadoop_path", Mock(return_value=(Mock(), filesystem, Mock())))
    write = Mock(side_effect=OSError("write failed"))
    monkeypatch.setattr(gbt, "write_json", write)
    cleanup = Mock()
    publish = Mock()
    monkeypatch.setattr(gbt, "delete_owned_path", cleanup)
    monkeypatch.setattr(gbt, "publish_directory", publish)
    kwargs = {
        "input_root": "file:///tmp/input",
        "input_snapshot": "sha256:" + "a" * 64,
        "result_uri": "file:///tmp/result",
        "worker_count": 1,
        "revision": "a" * 40,
        "sequence": 1,
        "estimators": 2,
        "max_depth": 2,
        "seed": 42,
    }
    with pytest.raises(OSError, match="write failed"):
        gbt.run_benchmark(session, **kwargs)
    cleanup.assert_called_once_with(session, "file:///tmp/.staged")
    publish.assert_not_called()
    write.side_effect = None
    assert gbt.run_benchmark(session, **kwargs)["status"] == "SUCCEEDED"
    publish.assert_called_once()


def test_runtime_requirements_have_xgboost_dependencies():
    root = Path(__file__).resolve().parents[4]
    requirements = (root / "infra/spark/requirements-ml.txt").read_text()
    dockerfile = (root / "infra/spark/Dockerfile").read_text()
    for name in ("xgboost-cpu", "scikit-learn", "pandas", "pyarrow", "scipy"):
        assert f"{name} @ " in requirements
    assert "from xgboost.spark import SparkXGBRegressor" in dockerfile
