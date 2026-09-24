"""Presentation-only, synthetic Spark XGBoost scale-out benchmark."""

from __future__ import annotations

import hashlib
import json
import math
import platform
import re
import socket
import time
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

from pyspark.ml.feature import VectorAssembler
from pyspark.sql import functions as F

from batch.benchmark.storage import (
    delete_owned_path,
    hadoop_path,
    publish_directory,
    read_bytes,
    read_json,
    stage_uri,
    visible_files,
    write_json,
)

INPUT_VERSION = "gbt-synthetic-input-v1"
REPORT_VERSION = "gbt-distributed-benchmark-v1"
MAX_ROWS = 10_000_000
MAX_FEATURES = 71


def feature_names(count: int) -> list[str]:
    return [f"feature_{index:02d}" for index in range(count)]


def validate_shape(rows: int, features: int, partitions: int) -> None:
    if not 1 <= rows <= MAX_ROWS:
        raise ValueError(f"rows must be between 1 and {MAX_ROWS}")
    if not 1 <= features <= MAX_FEATURES:
        raise ValueError(f"features must be between 1 and {MAX_FEATURES}")
    if not 1 <= partitions <= 128:
        raise ValueError("partitions must be between 1 and 128")


def synthetic_frame(spark, rows: int, features: int, partitions: int, seed: int):
    validate_shape(rows, features, partitions)
    frame = spark.range(rows, numPartitions=partitions)
    for index, name in enumerate(feature_names(features)):
        # Integer arithmetic makes the same row independent of Spark task scheduling.
        value = F.pmod(F.col("id") * (index * 2 + 3) + seed * (index + 1), 1009)
        frame = frame.withColumn(name, (value / F.lit(1009.0)).cast("float"))
    first = F.col("feature_00")
    second = F.col("feature_01") if features > 1 else first
    label = (first * 2.0 + second * second + F.sin(first * 6.0)).cast("float")
    return frame.withColumn("label", label)


def content_fingerprint(frame, features: int) -> str:
    """Order/partition-independent logical data fingerprint for synthetic rows."""
    names = ["id", "label", *feature_names(features)]
    hashed = frame.select(F.xxhash64(*names).alias("row_hash"))
    summary = hashed.agg(
        F.count("*").alias("rows"),
        F.min("row_hash").alias("minimum"),
        F.max("row_hash").alias("maximum"),
        F.expr("bit_xor(row_hash)").alias("xor"),
    ).first()
    payload = f"{summary.rows}:{summary.minimum}:{summary.maximum}:{summary.xor}"
    return "sha256:" + hashlib.sha256(payload.encode("ascii")).hexdigest()


def _require_new_uri(spark, uri: str) -> None:
    path, filesystem, _ = hadoop_path(spark, uri)
    if filesystem.exists(path):
        raise FileExistsError(f"benchmark output already exists: {uri}")


def verify_deployed_revision(revision: str) -> None:
    code_revision = Path(__file__).resolve().parents[2] / ".source-revision"
    if not code_revision.is_file() or code_revision.read_text(encoding="utf-8").strip() != revision:
        raise ValueError("revision does not match the deployed Spark code image")


def prepare_input(
    spark, *, output_uri: str, input_id: str, rows: int, features: int, partitions: int, seed: int
):
    validate_shape(rows, features, partitions)
    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,127}", input_id):
        raise ValueError("input ID has an invalid format")
    _require_new_uri(spark, output_uri)
    columns = feature_names(features)
    generated = synthetic_frame(spark, rows, features, partitions, seed)
    fingerprint = content_fingerprint(generated, features)
    frame = VectorAssembler(inputCols=columns, outputCol="features").transform(
        generated
    ).select("id", "features", "label")
    temporary = stage_uri(output_uri)
    try:
        frame.write.mode("errorifexists").parquet(f"{temporary}/data")
        if spark.read.parquet(f"{temporary}/data").count() != rows:
            raise ValueError("prepared row count does not match request")
        metadata = {
            "version": INPUT_VERSION,
            "input_id": input_id,
            "rows": rows,
            "features": features,
            "partitions": partitions,
            "seed": seed,
            "generator": "deterministic-integer-v1",
            "content_fingerprint": fingerprint,
        }
        write_json(spark, f"{temporary}/metadata.json", metadata)
        files = visible_files(spark, f"{temporary}/data")
        manifest = metadata | {"files": {f"data/{name}": digest for name, digest in files.items()}}
        write_json(spark, f"{temporary}/manifest.json", manifest)
        snapshot = "sha256:" + hashlib.sha256(
            read_bytes(spark, f"{temporary}/manifest.json")
        ).hexdigest()
        publish_directory(spark, temporary, output_uri)
        return metadata | {"input_snapshot": snapshot, "output_uri": output_uri}
    except BaseException as error:
        _cleanup(spark, temporary, error)


def verify_input(spark, input_root: str, input_snapshot: str) -> dict:
    manifest_uri = f"{input_root.rstrip('/')}/manifest.json"
    raw = read_bytes(spark, manifest_uri)
    if "sha256:" + hashlib.sha256(raw).hexdigest() != input_snapshot:
        raise ValueError("input snapshot does not match manifest")
    manifest = json.loads(raw)
    if manifest.get("version") != INPUT_VERSION:
        raise ValueError("unsupported input manifest")
    files = manifest.get("files")
    if not isinstance(files, dict) or not files:
        raise ValueError("input manifest has no data files")
    expected = {}
    for name, digest in files.items():
        parts = PurePosixPath(name).parts if isinstance(name, str) else ()
        if (
            not isinstance(name, str)
            or not name.startswith("data/")
            or ".." in parts
            or any(part.startswith((".", "_")) for part in parts[1:])
            or not re.fullmatch(r"[0-9a-f]{64}", digest)
        ):
            raise ValueError("unsafe input manifest entry")
        expected[name[5:]] = digest
    if visible_files(spark, f"{input_root.rstrip('/')}/data") != expected:
        raise ValueError("input files do not match manifest")
    return manifest


def run_benchmark(
    spark,
    *,
    input_root: str,
    input_snapshot: str,
    result_uri: str,
    worker_count: int,
    revision: str,
    sequence: int,
    estimators: int,
    max_depth: int,
    seed: int,
):
    if worker_count not in (1, 2) or sequence <= 0:
        raise ValueError("worker count must be 1 or 2 and sequence must be positive")
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("revision must be a 40-character Git SHA")
    if not 1 <= estimators <= 200 or not 1 <= max_depth <= 10:
        raise ValueError("estimator settings exceed safe benchmark bounds")
    verify_deployed_revision(revision)
    _require_new_uri(spark, result_uri)
    executor_cores = spark.conf.get("spark.executor.cores", None)
    task_cpus = spark.conf.get("spark.task.cpus", None)
    total_cores = spark.conf.get("spark.cores.max", None)
    if (str(executor_cores), str(task_cpus), str(total_cores)) != (
        "2",
        "2",
        str(worker_count * 2),
    ):
        raise ValueError("benchmark requires 2 executor cores and task CPUs per Worker")
    started = time.perf_counter()
    manifest = verify_input(spark, input_root, input_snapshot)
    frame = spark.read.parquet(f"{input_root.rstrip('/')}/data")
    if set(frame.columns) != {"id", "label", "features"}:
        raise ValueError("input feature schema is invalid")
    validated = frame.count()
    if validated != manifest["rows"]:
        raise ValueError("input row count does not match manifest")
    hosts = _probe_hosts(frame, worker_count)
    if len(hosts) != worker_count:
        raise RuntimeError(f"requested {worker_count} Workers but observed {hosts}")
    import xgboost
    from xgboost.spark import SparkXGBRegressor

    estimator = SparkXGBRegressor(
        features_col="features",
        label_col="label",
        num_workers=worker_count,
        n_estimators=estimators,
        max_depth=max_depth,
        learning_rate=0.1,
        objective="reg:squarederror",
        seed=seed,
    )
    training_started = time.perf_counter()
    estimator.fit(frame)
    training_seconds = time.perf_counter() - training_started
    if not math.isfinite(training_seconds) or training_seconds <= 0:
        raise ValueError("invalid training duration")
    result = {
        "version": REPORT_VERSION,
        "status": "SUCCEEDED",
        "recorded_at_utc": datetime.now(timezone.utc).isoformat(),
        "input": {
            "id": manifest["input_id"],
            "uri": input_root,
            "snapshot": input_snapshot,
            "rows": validated,
            "features": manifest["features"],
            "content_fingerprint": manifest["content_fingerprint"],
        },
        "model": {
            "library": "SparkXGBRegressor",
            "n_estimators": estimators,
            "max_depth": max_depth,
            "learning_rate": 0.1,
            "objective": "reg:squarederror",
            "seed": seed,
        },
        "resources": {
            "requested_worker_count": worker_count,
            "observed_probe_hosts": hosts,
            "sequence": sequence,
            "executor_cores": executor_cores,
            "task_cpus": task_cpus,
            "total_executor_cores": total_cores,
            "fit_stage_workers_verified": False,
        },
        "runtime": {
            "application_id": spark.sparkContext.applicationId,
            "implementation_revision": revision,
            "spark_version": spark.version,
            "python_version": platform.python_version(),
            "xgboost_version": xgboost.__version__,
        },
        "timing": {
            "training_seconds": training_seconds,
            "total_seconds": time.perf_counter() - started,
            "training_rows_per_second": validated / training_seconds,
        },
    }
    temporary = stage_uri(result_uri)
    try:
        path, filesystem, _ = hadoop_path(spark, temporary)
        if not filesystem.mkdirs(path):
            raise OSError(f"failed to stage benchmark report: {temporary}")
        write_json(spark, f"{temporary}/report.json", result)
        write_json(spark, f"{temporary}/_SUCCESS.json", {"status": "SUCCEEDED"})
        publish_directory(spark, temporary, result_uri)
        return result
    except BaseException as error:
        _cleanup(spark, temporary, error)


def read_report(spark, uri: str) -> dict:
    if read_json(spark, f"{uri.rstrip('/')}/_SUCCESS.json") != {"status": "SUCCEEDED"}:
        raise ValueError("benchmark result is incomplete")
    report = read_json(spark, f"{uri.rstrip('/')}/report.json")
    validate_report(report)
    return report


def validate_report(report: dict) -> None:
    if report.get("version") != REPORT_VERSION or report.get("status") != "SUCCEEDED":
        raise ValueError("unsupported benchmark result")
    for key in ("input", "model", "resources", "runtime", "timing"):
        if not isinstance(report.get(key), dict):
            raise ValueError(f"missing benchmark result field: {key}")
    if (
        not re.fullmatch(r"sha256:[0-9a-f]{64}", str(report["input"].get("snapshot", "")))
        or not report["runtime"].get("application_id")
        or not re.fullmatch(
            r"[0-9a-f]{40}", str(report["runtime"].get("implementation_revision", ""))
        )
    ):
        raise ValueError("benchmark result provenance is incomplete")
    if (
        isinstance(report["input"].get("rows"), bool)
        or not isinstance(report["input"].get("rows"), int)
        or report["input"]["rows"] <= 0
        or isinstance(report["input"].get("features"), bool)
        or not isinstance(report["input"].get("features"), int)
        or report["input"]["features"] <= 0
        or not report["model"]
    ):
        raise ValueError("benchmark result input or model is invalid")
    workers = report["resources"].get("requested_worker_count")
    hosts = report["resources"].get("observed_probe_hosts")
    if workers not in (1, 2) or not isinstance(hosts, list) or len(hosts) != workers:
        raise ValueError("benchmark result worker evidence is invalid")
    if len(set(hosts)) != workers or any(not isinstance(host, str) or not host for host in hosts):
        raise ValueError("benchmark result probe hosts are invalid")
    if (
        str(report["resources"].get("executor_cores")) != "2"
        or str(report["resources"].get("task_cpus")) != "2"
        or str(report["resources"].get("total_executor_cores")) != str(workers * 2)
    ):
        raise ValueError("benchmark result core settings are invalid")
    if not all(
        isinstance(report["runtime"].get(name), str) and report["runtime"][name]
        for name in ("spark_version", "python_version", "xgboost_version")
    ):
        raise ValueError("benchmark result runtime versions are invalid")
    duration = report["timing"].get("training_seconds")
    if (
        isinstance(duration, bool)
        or not isinstance(duration, int | float)
        or not math.isfinite(duration)
        or duration <= 0
    ):
        raise ValueError("benchmark result training duration is invalid")


def compare_results(single: dict, distributed: dict) -> dict:
    validate_report(single)
    validate_report(distributed)
    for key in ("input", "model"):
        if single[key] != distributed[key]:
            raise ValueError(f"benchmark results differ in {key}")
    if single["runtime"].get("implementation_revision") != distributed["runtime"].get(
        "implementation_revision"
    ):
        raise ValueError("benchmark results use different revisions")
    for name in ("spark_version", "python_version", "xgboost_version"):
        if single["runtime"][name] != distributed["runtime"][name]:
            raise ValueError(f"benchmark results differ in {name}")
    for name in ("executor_cores", "task_cpus"):
        if single["resources"][name] != distributed["resources"][name]:
            raise ValueError(f"benchmark results differ in {name}")
    if single["runtime"]["application_id"] == distributed["runtime"]["application_id"]:
        raise ValueError("benchmark results reuse one Spark application")
    if (
        single["resources"]["requested_worker_count"] != 1
        or distributed["resources"]["requested_worker_count"] != 2
    ):
        raise ValueError("comparison requires 1 Worker and 2 Worker results")
    one = single["timing"]["training_seconds"]
    two = distributed["timing"]["training_seconds"]
    return {
        "version": REPORT_VERSION,
        "input": single["input"],
        "model": single["model"],
        "single_training_seconds": one,
        "distributed_training_seconds": two,
        "speedup": one / two,
        "application_ids": [
            single["runtime"]["application_id"],
            distributed["runtime"]["application_id"],
        ],
        "fit_stage_workers_verified": False,
        "verification_note": "Confirm fit-stage executor hosts in Spark History Server",
    }


def _probe_hosts(frame, workers: int) -> list[str]:
    partitions = max(frame.sparkSession.sparkContext.defaultParallelism * 4, workers * 8)
    return sorted(
        frame.select("id").repartition(partitions).rdd.mapPartitions(
            lambda _: [socket.gethostname()]
        ).distinct().collect()
    )


def _cleanup(spark, temporary: str, original: BaseException):
    try:
        delete_owned_path(spark, temporary)
    except BaseException as cleanup_error:
        raise original from cleanup_error
    raise original
