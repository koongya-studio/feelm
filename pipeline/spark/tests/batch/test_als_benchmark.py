import json
import os
import sys
from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from pyspark.sql import SparkSession

from batch.benchmark import als, storage

REVISION = "a" * 40


@pytest.fixture(scope="module")
def spark() -> SparkSession:
    session = (
        SparkSession.builder.master("local[2]")
        .appName("als-benchmark-test")
        .config("spark.ui.enabled", "false")
        .config("spark.sql.shuffle.partitions", "2")
        .config("spark.pyspark.python", sys.executable)
        .config("spark.pyspark.driver.python", sys.executable)
        .getOrCreate()
    )
    session.sparkContext.setLogLevel("ERROR")
    yield session
    session.stop()


def compatible_report(
    workers: int, seconds: float, throughput: float = 10.0, *, rows=32_000_204, sequence=1
):
    return {
        "version": als.REPORT_VERSION,
        "status": "SUCCEEDED",
        "input": {
            "id": f"ml-{rows}",
            "snapshot": "sha256:fixture",
            "uri": "hdfs://namenode/input",
            "manifest_uri": "hdfs://namenode/manifest.json",
            "row_count": rows,
        },
        "als": {"rank": 64, "reg_param": 0.1, "max_iter": 10, "seed": 42},
        "resources": {
            "requested_worker_count": workers,
            "sequence": sequence,
            "observed_probe_hosts": [f"worker-{number}" for number in range(workers)],
        },
        "runtime": {
            "implementation_revision": REVISION,
            "spark_version": "4.1.3",
            "application_id": f"app-{rows}-{sequence}",
        },
        "counts": {"ratings": rows, "user_factors": 2, "item_factors": 3},
        "timing": {
            "validation_seconds": 1.0,
            "training_seconds": seconds,
            "total_seconds": seconds + 1,
            "training_rows_per_second": throughput,
        },
    }


def test_settings_reject_invalid_values():
    with pytest.raises(ValueError, match="expected count"):
        als.BenchmarkSettings("input", "snapshot", 0, 1, REVISION, 1).validate()
    with pytest.raises(ValueError, match="worker count"):
        als.BenchmarkSettings("input", "snapshot", 1, 0, REVISION, 1).validate()
    with pytest.raises(ValueError, match="ALS settings"):
        als.BenchmarkSettings("input", "snapshot", 1, 1, REVISION, 1, rank=0).validate()


def test_deterministic_sample_is_independent_of_input_order_and_partitions(spark):
    rows = [(user, movie) for user in range(1, 6) for movie in range(1, 5)]
    forward = spark.createDataFrame(rows, [als.USER, als.MOVIE]).repartition(1)
    reverse = spark.createDataFrame(list(reversed(rows)), [als.USER, als.MOVIE]).repartition(4)

    first = {
        (row[als.USER], row[als.MOVIE])
        for row in als.deterministic_sample(forward, 7, 42).collect()
    }
    second = {
        (row[als.USER], row[als.MOVIE])
        for row in als.deterministic_sample(reverse, 7, 42).collect()
    }

    assert len(first) == 7
    assert first == second


def test_registered_executors_reads_spark_runtime(spark):
    addresses = als._registered_executors(spark)

    assert addresses
    assert all(isinstance(address, str) and address for address in addresses)


def test_compare_calculates_speedup_and_throughput_change():
    result = als.compare_results(
        compatible_report(1, 20.0, 100.0),
        compatible_report(2, 8.0, 250.0),
    )

    assert result["speedup"] == 2.5
    assert result["throughput_change"] == 2.5
    assert result["single_workers"] == 1
    assert result["distributed_workers"] == 2


@pytest.mark.parametrize("field", ["input", "als"])
def test_compare_rejects_incompatible_results(field):
    single = compatible_report(1, 20.0)
    distributed = compatible_report(2, 10.0)
    distributed[field] = {"different": True}

    with pytest.raises(ValueError, match=field):
        als.compare_results(single, distributed)


def test_compare_rejects_swapped_worker_roles():
    with pytest.raises(ValueError, match="1 Worker"):
        als.compare_results(compatible_report(2, 10.0), compatible_report(1, 20.0))


def test_prepare_publishes_exact_validated_sample(monkeypatch):
    spark = Mock()
    source = Mock()
    selected = Mock()
    reloaded = Mock()
    spark.read.parquet.side_effect = [source, reloaded]
    monkeypatch.setattr(als, "validate_ratings", Mock(side_effect=[(source, 100), (reloaded, 10)]))
    monkeypatch.setattr(als, "deterministic_sample", Mock(return_value=selected))
    selected.persist.return_value = selected
    selected.count.return_value = 10
    selected.write.mode.return_value = selected.write
    monkeypatch.setattr(als, "stage_uri", Mock(return_value="file:///tmp/.input.tmp"))
    write = Mock()
    publish = Mock()
    monkeypatch.setattr(als, "write_json", write)
    monkeypatch.setattr(als, "visible_files", Mock(return_value={"part.parquet": "a" * 64}))
    monkeypatch.setattr(als, "sha256_uri", Mock(return_value="b" * 64))
    monkeypatch.setattr(als, "read_bytes", Mock(return_value=b"manifest"))
    monkeypatch.setattr(als, "publish_directory", publish)
    monkeypatch.setattr(als, "delete_owned_path", Mock())

    result = als.prepare_input(
        spark,
        source_uri="file:///tmp/source",
        output_uri="file:///tmp/input",
        input_id="ml-10",
        source_snapshot="snapshot",
        size=10,
        seed=42,
    )

    assert result["row_count"] == 10
    selected.write.mode.assert_called_once_with("errorifexists")
    selected.write.parquet.assert_called_once_with("file:///tmp/.input.tmp/ratings")
    assert write.call_count == 2
    publish.assert_called_once_with(spark, "file:///tmp/.input.tmp", "file:///tmp/input")
    selected.unpersist.assert_called_once()


def test_prepare_failure_removes_only_owned_staging_path(monkeypatch):
    spark = Mock()
    source = Mock()
    selected = Mock()
    spark.read.parquet.return_value = source
    monkeypatch.setattr(als, "validate_ratings", Mock(return_value=(source, 100)))
    monkeypatch.setattr(als, "deterministic_sample", Mock(return_value=selected))
    selected.persist.return_value = selected
    selected.count.side_effect = RuntimeError("injected")
    monkeypatch.setattr(als, "stage_uri", Mock(return_value="file:///tmp/.owned.tmp"))
    cleanup = Mock()
    monkeypatch.setattr(als, "delete_owned_path", cleanup)

    with pytest.raises(RuntimeError, match="injected"):
        als.prepare_input(
            spark,
            source_uri="file:///tmp/source",
            output_uri="file:///tmp/input",
            input_id="ml-10",
            source_snapshot="snapshot",
            size=10,
            seed=42,
        )

    cleanup.assert_called_once_with(spark, "file:///tmp/.owned.tmp")
    selected.unpersist.assert_called_once()


def test_prepare_preserves_original_failure_when_cleanup_also_fails(monkeypatch):
    spark = Mock()
    source = Mock()
    selected = Mock()
    spark.read.parquet.return_value = source
    monkeypatch.setattr(als, "validate_ratings", Mock(return_value=(source, 100)))
    monkeypatch.setattr(als, "deterministic_sample", Mock(return_value=selected))
    selected.persist.return_value = selected
    original = RuntimeError("original")
    selected.count.side_effect = original
    monkeypatch.setattr(als, "stage_uri", Mock(return_value="file:///tmp/.owned.tmp"))
    monkeypatch.setattr(
        als, "delete_owned_path", Mock(side_effect=OSError("cleanup failed"))
    )

    with pytest.raises(RuntimeError, match="original") as caught:
        als.prepare_input(
            spark,
            source_uri="file:///tmp/source",
            output_uri="file:///tmp/input",
            input_id="ml-10",
            source_snapshot="snapshot",
            size=10,
            seed=42,
        )

    assert isinstance(caught.value.__cause__, OSError)


def test_run_records_training_and_publishes_report(monkeypatch):
    spark = Mock()
    spark.version = "4.1.3"
    spark.sparkContext = SimpleNamespace(
        defaultParallelism=4,
        master="spark://master:7077",
        applicationId="app-1",
        appName="benchmark",
    )
    ratings = Mock()
    spark.read.parquet.return_value = ratings
    monkeypatch.setattr(als, "_validate_input_manifest", Mock(return_value={"row_count": 100}))
    monkeypatch.setattr(als, "validate_ratings", Mock(return_value=(ratings, 100)))
    model = SimpleNamespace(
        userFactors=Mock(count=Mock(return_value=6)),
        itemFactors=Mock(count=Mock(return_value=14)),
    )
    monkeypatch.setattr(als, "train_als_model", Mock(return_value=model))
    monkeypatch.setattr(als, "_probe_hosts", Mock(return_value=["worker-1", "worker-2"]))
    monkeypatch.setattr(
        als,
        "_registered_executors",
        Mock(return_value=["worker-1:1", "worker-2:1"]),
    )
    ticks = iter([0.0, 1.0, 3.0, 4.0, 8.0, 10.0])
    monkeypatch.setattr(als.time, "perf_counter", lambda: next(ticks))
    monkeypatch.setattr(als, "stage_uri", Mock(return_value="hdfs://namenode/.result.tmp"))
    file_system = Mock()
    file_system.mkdirs.return_value = True
    monkeypatch.setattr(als, "_path", Mock(return_value=(Mock(), file_system, Mock())))
    write = Mock()
    publish = Mock()
    monkeypatch.setattr(als, "write_json", write)
    monkeypatch.setattr(als, "publish_directory", publish)
    monkeypatch.setattr(als, "delete_owned_path", Mock())

    result = als.run_benchmark(
        spark,
        input_uri="hdfs://namenode/input",
        input_manifest_uri="hdfs://namenode/manifest.json",
        result_uri="hdfs://namenode/result",
        settings=als.BenchmarkSettings("ml-100", "snapshot", 100, 2, REVISION, 1),
    )

    assert result["timing"] == {
        "validation_seconds": 2.0,
        "training_seconds": 4.0,
        "total_seconds": 10.0,
        "training_rows_per_second": 25.0,
    }
    assert result["counts"] == {"ratings": 100, "user_factors": 6, "item_factors": 14}
    assert write.call_count == 2
    publish.assert_called_once_with(
        spark, "hdfs://namenode/.result.tmp", "hdfs://namenode/result"
    )


def test_run_rejects_worker_probe_mismatch_without_publishing(monkeypatch):
    spark = Mock()
    spark.read.parquet.return_value = Mock()
    monkeypatch.setattr(als, "_validate_input_manifest", Mock(return_value={"row_count": 10}))
    monkeypatch.setattr(als, "validate_ratings", Mock(return_value=(Mock(), 10)))
    model = SimpleNamespace(
        userFactors=Mock(count=Mock(return_value=2)),
        itemFactors=Mock(count=Mock(return_value=3)),
    )
    monkeypatch.setattr(als, "train_als_model", Mock(return_value=model))
    monkeypatch.setattr(als, "_probe_hosts", Mock(return_value=["worker-1"]))
    publish = Mock()
    monkeypatch.setattr(als, "publish_directory", publish)

    with pytest.raises(RuntimeError, match="requested worker count"):
        als.run_benchmark(
            spark,
            input_uri="hdfs://namenode/input",
            input_manifest_uri="hdfs://namenode/manifest.json",
            result_uri="hdfs://namenode/result",
            settings=als.BenchmarkSettings("ml-10", "snapshot", 10, 2, REVISION, 1),
        )

    publish.assert_not_called()


def test_manifest_binds_input_file_content(monkeypatch):
    payload = json.dumps(
        {"input_id": "ml-10", "row_count": 10, "files": {"ratings/part": "a" * 64}},
        sort_keys=True,
    ).encode()
    monkeypatch.setattr(als, "read_bytes", Mock(return_value=payload))
    monkeypatch.setattr(als, "visible_files", Mock(return_value={"part": "b" * 64}))

    with pytest.raises(ValueError, match="do not match"):
        als._validate_input_manifest(
            Mock(),
            input_uri="hdfs://namenode/root/ratings",
            manifest_uri="hdfs://namenode/root/manifest.json",
            expected_snapshot=f"sha256:{als.sha256_bytes(payload)}",
            expected_input_id="ml-10",
        )


def test_read_report_requires_valid_success_marker(monkeypatch):
    monkeypatch.setattr(als, "read_json", Mock(return_value={"status": "FAILED"}))

    with pytest.raises(ValueError, match="completion marker"):
        als.read_report(Mock(), "hdfs://namenode/result")


def test_aggregate_requires_complete_order_and_uses_medians():
    reports = []
    worker_order = [1, 2, 2, 1, 1, 2]
    for rows in (1_000_000, 10_000_000, 32_000_204):
        for sequence, workers in enumerate(worker_order, 1):
            reports.append(
                compatible_report(
                    workers,
                    float(sequence if workers == 1 else sequence / 2),
                    rows=rows,
                    sequence=sequence,
                )
            )

    result = als.aggregate_results(reports)

    assert len(result["results"]) == 3
    assert result["results"][0]["single_median_seconds"] == 4.0
    assert result["results"][0]["distributed_median_seconds"] == 1.5


@pytest.mark.skipif(os.name == "nt", reason="local publication uses the Linux Spark runtime")
def test_local_publish_is_no_overwrite_and_preserves_existing(tmp_path):
    source = tmp_path / "source"
    source.mkdir()
    (source / "value").write_text("new", encoding="utf-8")
    destination = tmp_path / "destination"
    storage._publish_local(source, destination)
    retry = tmp_path / "retry"
    retry.mkdir()
    (retry / "value").write_text("replacement", encoding="utf-8")

    with pytest.raises(FileExistsError):
        storage._publish_local(retry, destination)

    assert (destination / "value").read_text(encoding="utf-8") == "new"
