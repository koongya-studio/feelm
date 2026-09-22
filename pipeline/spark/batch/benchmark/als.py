"""Deterministic input preparation and single-model ALS timing."""

from __future__ import annotations

import json
import math
import platform
import posixpath
import re
import socket
import statistics
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import PurePosixPath
from urllib.parse import urlsplit

from pyspark.sql import functions as F

from batch.benchmark.storage import (
    delete_owned_path,
    publish_directory,
    read_bytes,
    read_json,
    sha256_bytes,
    sha256_uri,
    stage_uri,
    visible_files,
    write_json,
)
from batch.train.inputs import validate_ratings
from batch.train.settings import AlsConfig
from batch.train.training import train_als_model

REPORT_VERSION = "als-distributed-benchmark-v1"
DATASET_VERSION = "als-benchmark-input-v1"
AGGREGATE_VERSION = "als-distributed-benchmark-summary-v1"
USER = "movielens_user_id"
MOVIE = "movielens_movie_id"


@dataclass(frozen=True)
class BenchmarkSettings:
    input_id: str
    input_snapshot: str
    expected_count: int
    worker_count: int
    implementation_revision: str
    sequence: int
    rank: int = 64
    reg_param: float = 0.1
    max_iter: int = 10
    seed: int = 42

    def validate(self) -> None:
        if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,127}", self.input_id):
            raise ValueError("input ID has an invalid format")
        if not self.input_snapshot.strip():
            raise ValueError("input snapshot is required")
        if self.expected_count <= 0:
            raise ValueError("expected count must be positive")
        if self.worker_count <= 0:
            raise ValueError("worker count must be positive")
        if not re.fullmatch(r"[0-9a-f]{40}", self.implementation_revision):
            raise ValueError("implementation revision must be a 40-character Git SHA")
        if self.sequence <= 0:
            raise ValueError("sequence must be positive")
        if (
            self.rank <= 0
            or not math.isfinite(self.reg_param)
            or self.reg_param < 0
            or self.max_iter <= 0
        ):
            raise ValueError("ALS settings require rank/maxIter > 0 and regParam >= 0")
        if not -(2**63) <= self.seed < 2**63:
            raise ValueError("seed must be a signed 64-bit integer")


def deterministic_sample(ratings, size: int, seed: int):
    if size <= 0:
        raise ValueError("sample size must be positive")
    required = {USER, MOVIE}
    if not required.issubset(ratings.columns):
        missing = sorted(required - set(ratings.columns))
        raise ValueError(f"ratings is missing sample columns: {missing}")
    return (
        ratings.withColumn("_sample_hash", F.xxhash64(F.lit(seed), F.col(USER), F.col(MOVIE)))
        .orderBy("_sample_hash", USER, MOVIE)
        .limit(size)
        .drop("_sample_hash")
    )


def prepare_input(
    spark,
    *,
    source_uri: str,
    output_uri: str,
    input_id: str,
    source_snapshot: str,
    size: int,
    seed: int,
) -> dict[str, object]:
    if not input_id.strip() or not source_snapshot.strip():
        raise ValueError("input ID and source snapshot are required")
    source, source_count = validate_ratings(spark.read.parquet(source_uri))
    if size > source_count:
        raise ValueError(f"sample size {size} exceeds source row count {source_count}")
    selected = deterministic_sample(source, size, seed).persist()
    temporary_uri = stage_uri(output_uri)
    try:
        actual_count = selected.count()
        if actual_count != size:
            raise ValueError(f"sample row count mismatch: expected={size}, actual={actual_count}")
        selected.write.mode("errorifexists").parquet(f"{temporary_uri}/ratings")
        reloaded, reloaded_count = validate_ratings(
            spark.read.parquet(f"{temporary_uri}/ratings")
        )
        del reloaded
        if reloaded_count != size:
            raise ValueError("published sample validation count mismatch")
        metadata = {
            "version": DATASET_VERSION,
            "input_id": input_id,
            "source_snapshot": source_snapshot,
            "source_uri": source_uri,
            "source_count": source_count,
            "row_count": actual_count,
            "seed": seed,
        }
        write_json(spark, f"{temporary_uri}/metadata.json", metadata)
        files = {
            f"ratings/{relative}": digest
            for relative, digest in visible_files(spark, f"{temporary_uri}/ratings").items()
        }
        files["metadata.json"] = sha256_uri(spark, f"{temporary_uri}/metadata.json")
        manifest = metadata | {"files": files}
        write_json(spark, f"{temporary_uri}/manifest.json", manifest)
        manifest_bytes = read_bytes(spark, f"{temporary_uri}/manifest.json")
        metadata["input_snapshot"] = f"sha256:{sha256_bytes(manifest_bytes)}"
        publish_directory(spark, temporary_uri, output_uri)
        return metadata
    except BaseException as error:
        _cleanup_without_replacing(spark, temporary_uri, error)
    finally:
        selected.unpersist()


def run_benchmark(
    spark,
    *,
    input_uri: str,
    input_manifest_uri: str,
    result_uri: str,
    settings: BenchmarkSettings,
):
    settings.validate()
    started = time.perf_counter()
    manifest = _validate_input_manifest(
        spark,
        input_uri=input_uri,
        manifest_uri=input_manifest_uri,
        expected_snapshot=settings.input_snapshot,
        expected_input_id=settings.input_id,
    )
    validation_started = time.perf_counter()
    ratings, row_count = validate_ratings(spark.read.parquet(input_uri))
    validation_seconds = time.perf_counter() - validation_started
    if row_count != settings.expected_count:
        raise ValueError(
            "benchmark input count mismatch: "
            f"expected={settings.expected_count}, actual={row_count}"
        )
    manifest_count = manifest.get("row_count")
    if manifest_count is not None and manifest_count != row_count:
        raise ValueError("benchmark manifest row count does not match loaded ratings")
    config = AlsConfig(settings.rank, settings.reg_param, settings.max_iter)
    training_started = time.perf_counter()
    model = train_als_model(ratings, config, settings.seed)
    user_factor_count = model.userFactors.count()
    item_factor_count = model.itemFactors.count()
    training_seconds = time.perf_counter() - training_started
    probe_hosts = _probe_hosts(ratings, settings.worker_count)
    if len(probe_hosts) != settings.worker_count:
        raise RuntimeError(
            "executor probe did not observe requested worker count: "
            f"requested={settings.worker_count}, observed={probe_hosts}"
        )
    total_seconds = time.perf_counter() - started
    result = {
        "version": REPORT_VERSION,
        "status": "SUCCEEDED",
        "recorded_at_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "input": {
            "id": settings.input_id,
            "snapshot": settings.input_snapshot,
            "uri": input_uri,
            "manifest_uri": input_manifest_uri,
            "row_count": row_count,
        },
        "als": asdict(config) | {"seed": settings.seed},
        "resources": {
            "requested_worker_count": settings.worker_count,
            "sequence": settings.sequence,
            "observed_probe_hosts": probe_hosts,
            "registered_executor_addresses": _registered_executors(spark),
            "default_parallelism": spark.sparkContext.defaultParallelism,
            "master": spark.sparkContext.master,
            "executor_cores": spark.conf.get("spark.executor.cores", None),
            "executor_memory": spark.conf.get("spark.executor.memory", None),
            "total_executor_cores": spark.conf.get("spark.cores.max", None),
        },
        "runtime": {
            "application_id": spark.sparkContext.applicationId,
            "application_name": spark.sparkContext.appName,
            "implementation_revision": settings.implementation_revision,
            "python_version": platform.python_version(),
            "spark_version": spark.version,
        },
        "counts": {
            "ratings": row_count,
            "user_factors": user_factor_count,
            "item_factors": item_factor_count,
        },
        "timing": {
            "validation_seconds": validation_seconds,
            "training_seconds": training_seconds,
            "total_seconds": total_seconds,
            "training_rows_per_second": row_count / training_seconds,
        },
    }
    temporary_uri = stage_uri(result_uri)
    try:
        _, file_system, _ = _path(spark, temporary_uri)
        temporary_path, _, _ = _path(spark, temporary_uri)
        if not file_system.mkdirs(temporary_path):
            raise OSError(f"failed to create benchmark result staging path: {temporary_uri}")
        write_json(spark, f"{temporary_uri}/report.json", result)
        write_json(spark, f"{temporary_uri}/_SUCCESS.json", {"status": "SUCCEEDED"})
        publish_directory(spark, temporary_uri, result_uri)
        return result
    except BaseException as error:
        _cleanup_without_replacing(spark, temporary_uri, error)


def compare_results(single: dict[str, object], distributed: dict[str, object]):
    validate_report(single)
    validate_report(distributed)
    for field in ("version", "input", "als"):
        if single.get(field) != distributed.get(field):
            raise ValueError(f"benchmark results differ in {field}")
    single_runtime = single.get("runtime") or {}
    distributed_runtime = distributed.get("runtime") or {}
    if single_runtime.get("implementation_revision") != distributed_runtime.get(
        "implementation_revision"
    ):
        raise ValueError("benchmark results use different implementation revisions")
    single_workers = (single.get("resources") or {}).get("requested_worker_count")
    distributed_workers = (distributed.get("resources") or {}).get("requested_worker_count")
    if single_workers != 1 or not isinstance(distributed_workers, int) or distributed_workers <= 1:
        raise ValueError("comparison requires a 1 Worker result and a multi-Worker result")
    single_timing = single.get("timing") or {}
    distributed_timing = distributed.get("timing") or {}
    one = float(single_timing["training_seconds"])
    many = float(distributed_timing["training_seconds"])
    if one <= 0 or many <= 0:
        raise ValueError("benchmark training durations must be positive")
    return {
        "version": REPORT_VERSION,
        "input": single["input"],
        "als": single["als"],
        "single_workers": single_workers,
        "distributed_workers": distributed_workers,
        "single_training_seconds": one,
        "distributed_training_seconds": many,
        "speedup": one / many,
        "throughput_change": float(distributed_timing["training_rows_per_second"])
        / float(single_timing["training_rows_per_second"]),
    }


def read_report(spark, result_uri: str) -> dict[str, object]:
    marker = read_json(spark, f"{result_uri.rstrip('/')}/_SUCCESS.json")
    if marker != {"status": "SUCCEEDED"}:
        raise ValueError("benchmark completion marker is invalid")
    report = read_json(spark, f"{result_uri.rstrip('/')}/report.json")
    validate_report(report)
    return report


def validate_report(report: dict[str, object]) -> None:
    if report.get("version") != REPORT_VERSION or report.get("status") != "SUCCEEDED":
        raise ValueError("benchmark report is not a successful supported result")
    input_value = _object(report, "input")
    als_value = _object(report, "als")
    resources = _object(report, "resources")
    runtime = _object(report, "runtime")
    counts = _object(report, "counts")
    timing = _object(report, "timing")
    if not input_value.get("snapshot") or not input_value.get("manifest_uri"):
        raise ValueError("benchmark report input provenance is incomplete")
    if not als_value or not runtime.get("implementation_revision"):
        raise ValueError("benchmark report configuration is incomplete")
    workers = resources.get("requested_worker_count")
    hosts = resources.get("observed_probe_hosts")
    if not isinstance(workers, int) or workers <= 0 or not isinstance(hosts, list):
        raise ValueError("benchmark report resources are invalid")
    if len(hosts) != workers:
        raise ValueError("benchmark report worker evidence does not match request")
    _sequence(report)
    for name in ("ratings", "user_factors", "item_factors"):
        if not isinstance(counts.get(name), int) or counts[name] <= 0:
            raise ValueError(f"benchmark report count is invalid: {name}")
    for name in ("validation_seconds", "training_seconds", "total_seconds"):
        _positive_finite(timing.get(name), name)
    _positive_finite(timing.get("training_rows_per_second"), "training_rows_per_second")


def aggregate_results(reports: list[dict[str, object]]) -> dict[str, object]:
    for report in reports:
        validate_report(report)
    groups: dict[int, list[dict[str, object]]] = {}
    for report in reports:
        row_count = _object(report, "input").get("row_count")
        if not isinstance(row_count, int):
            raise ValueError("benchmark report input count is invalid")
        groups.setdefault(row_count, []).append(report)
    expected_sizes = {1_000_000, 10_000_000, 32_000_204}
    if set(groups) != expected_sizes:
        raise ValueError("aggregate requires 1M, 10M, and 32M benchmark inputs")
    rows = []
    revision = None
    als_config = None
    for row_count in sorted(groups):
        ordered = sorted(groups[row_count], key=lambda item: _sequence(item))
        if [_sequence(item) for item in ordered] != list(range(1, 7)):
            raise ValueError("each input requires unique benchmark sequences 1 through 6")
        workers = [_workers(item) for item in ordered]
        if workers != [1, 2, 2, 1, 1, 2]:
            raise ValueError("benchmark sequence must follow 1,2,2,1,1,2 workers")
        identities = {
            json.dumps(_object(item, "input"), sort_keys=True) for item in ordered
        }
        if len(identities) != 1:
            raise ValueError("aggregate input repetitions do not use one immutable input")
        revisions = {
            _object(item, "runtime").get("implementation_revision") for item in ordered
        }
        configs = {json.dumps(_object(item, "als"), sort_keys=True) for item in ordered}
        if len(revisions) != 1 or len(configs) != 1:
            raise ValueError("aggregate repetitions use incompatible code or ALS settings")
        current_revision = revisions.pop()
        current_config = configs.pop()
        if revision is None:
            revision, als_config = current_revision, current_config
        elif revision != current_revision or als_config != current_config:
            raise ValueError("aggregate inputs use incompatible code or ALS settings")
        single_times = [_training_seconds(item) for item in ordered if _workers(item) == 1]
        distributed_times = [_training_seconds(item) for item in ordered if _workers(item) == 2]
        single_median = statistics.median(single_times)
        distributed_median = statistics.median(distributed_times)
        rows.append(
            {
                "row_count": row_count,
                "single_training_seconds": single_times,
                "distributed_training_seconds": distributed_times,
                "single_median_seconds": single_median,
                "distributed_median_seconds": distributed_median,
                "speedup": single_median / distributed_median,
                "application_ids": [
                    _object(item, "runtime").get("application_id") for item in ordered
                ],
            }
        )
    return {
        "version": AGGREGATE_VERSION,
        "status": "SUCCEEDED",
        "implementation_revision": revision,
        "als": json.loads(als_config),
        "sequence_policy": [1, 2, 2, 1, 1, 2],
        "results": rows,
    }


def publish_aggregate(spark, reports: list[dict[str, object]], output_uri: str):
    summary = aggregate_results(reports)
    temporary_uri = stage_uri(output_uri)
    try:
        temporary_path, file_system, _ = _path(spark, temporary_uri)
        if not file_system.mkdirs(temporary_path):
            raise OSError(f"failed to create aggregate staging path: {temporary_uri}")
        write_json(spark, f"{temporary_uri}/summary.json", summary)
        write_json(spark, f"{temporary_uri}/_SUCCESS.json", {"status": "SUCCEEDED"})
        publish_directory(spark, temporary_uri, output_uri)
        return summary
    except BaseException as error:
        _cleanup_without_replacing(spark, temporary_uri, error)


def _validate_input_manifest(
    spark,
    *,
    input_uri: str,
    manifest_uri: str,
    expected_snapshot: str,
    expected_input_id: str,
) -> dict[str, object]:
    manifest_bytes = read_bytes(spark, manifest_uri)
    actual_snapshot = f"sha256:{sha256_bytes(manifest_bytes)}"
    if actual_snapshot != expected_snapshot:
        raise ValueError("benchmark input manifest snapshot does not match")
    try:
        manifest = json.loads(manifest_bytes.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("benchmark input manifest is invalid JSON") from error
    if not isinstance(manifest, dict):
        raise ValueError("benchmark input manifest must contain an object")
    manifest_input_id = manifest.get("input_id")
    if manifest_input_id is not None and manifest_input_id != expected_input_id:
        raise ValueError("benchmark input ID does not match manifest")
    files = manifest.get("files")
    if not isinstance(files, dict) or not files:
        raise ValueError("benchmark input manifest has no file hashes")
    relative_input = _relative_uri(posixpath.dirname(manifest_uri), input_uri)
    prefix = relative_input.rstrip("/") + "/" if relative_input else ""
    expected_files = {}
    for name, digest in files.items():
        if not isinstance(name, str) or not isinstance(digest, str):
            raise ValueError("benchmark input manifest file entry is invalid")
        path = PurePosixPath(name)
        if path.is_absolute() or ".." in path.parts or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError("benchmark input manifest file entry is unsafe")
        if prefix and not name.startswith(prefix):
            continue
        relative = name[len(prefix) :] if prefix else name
        if relative and not any(part.startswith((".", "_")) for part in relative.split("/")):
            expected_files[relative] = digest.casefold()
    if not expected_files:
        raise ValueError("benchmark manifest does not describe the ratings input")
    actual_files = visible_files(spark, input_uri)
    if actual_files != expected_files:
        raise ValueError("benchmark ratings files do not match the input manifest")
    return manifest


def _relative_uri(parent_uri: str, child_uri: str) -> str:
    parent = urlsplit(parent_uri)
    child = urlsplit(child_uri)
    if (parent.scheme.casefold(), parent.netloc.casefold()) != (
        child.scheme.casefold(),
        child.netloc.casefold(),
    ):
        raise ValueError("benchmark manifest and input must use the same filesystem")
    parent_path = posixpath.normpath(parent.path)
    child_path = posixpath.normpath(child.path)
    relative = posixpath.relpath(child_path, parent_path)
    if relative == ".." or relative.startswith("../"):
        raise ValueError("benchmark input must be inside the manifest root")
    return "" if relative == "." else relative


def _object(value: dict[str, object], name: str) -> dict[str, object]:
    item = value.get(name)
    if not isinstance(item, dict):
        raise ValueError(f"benchmark report field is invalid: {name}")
    return item


def _positive_finite(value: object, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, int | float):
        raise ValueError(f"benchmark report timing is invalid: {name}")
    converted = float(value)
    if not math.isfinite(converted) or converted <= 0:
        raise ValueError(f"benchmark report timing is invalid: {name}")
    return converted


def _sequence(report: dict[str, object]) -> int:
    value = _object(report, "resources").get("sequence")
    if not isinstance(value, int) or isinstance(value, bool):
        raise ValueError("benchmark sequence is invalid")
    return value


def _workers(report: dict[str, object]) -> int:
    value = _object(report, "resources").get("requested_worker_count")
    if not isinstance(value, int) or isinstance(value, bool):
        raise ValueError("benchmark worker count is invalid")
    return value


def _training_seconds(report: dict[str, object]) -> float:
    return _positive_finite(
        _object(report, "timing").get("training_seconds"), "training_seconds"
    )


def _probe_hosts(ratings, worker_count: int) -> list[str]:
    partitions = max(ratings.sparkSession.sparkContext.defaultParallelism * 4, worker_count * 8)
    return sorted(
        ratings.select(USER, MOVIE)
        .repartition(partitions)
        .rdd.mapPartitions(lambda _: [socket.gethostname()])
        .distinct()
        .collect()
    )


def _registered_executors(spark) -> list[str]:
    iterator = (  # noqa: SLF001
        spark.sparkContext._jsc.sc().getExecutorMemoryStatus().keysIterator()
    )
    addresses = []
    while iterator.hasNext():
        addresses.append(str(iterator.next()))
    return sorted(addresses)


def _cleanup_without_replacing(spark, temporary_uri: str, original: BaseException):
    try:
        delete_owned_path(spark, temporary_uri)
    except BaseException as cleanup_error:
        raise original from cleanup_error
    raise original


def _path(spark, uri: str):
    from batch.benchmark.storage import hadoop_path

    return hadoop_path(spark, uri)
