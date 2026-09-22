"""Prepare deterministic ratings and run/compare a single ALS benchmark."""

from __future__ import annotations

import argparse
import json
import logging
import posixpath
from urllib.parse import urlsplit

from batch.benchmark.als import (
    BenchmarkSettings,
    compare_results,
    prepare_input,
    publish_aggregate,
    read_report,
    run_benchmark,
)
from batch.train.settings import validate_storage_uri
from common.spark import create_spark_session

LOGGER = logging.getLogger(__name__)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spark-master")
    parser.add_argument("--spark-app-name", default="feelm-als-distributed-benchmark")
    subparsers = parser.add_subparsers(dest="command", required=True)

    prepare = subparsers.add_parser("prepare", help="Publish one deterministic ratings input")
    prepare.add_argument("--source-uri", required=True)
    prepare.add_argument("--output-uri", required=True)
    prepare.add_argument("--input-id", required=True)
    prepare.add_argument("--source-snapshot", required=True)
    prepare.add_argument("--size", required=True, type=int)
    prepare.add_argument("--seed", type=int, default=42)

    run = subparsers.add_parser("run", help="Train and time one ALS configuration")
    run.add_argument("--input-uri", required=True)
    run.add_argument("--input-manifest-uri", required=True)
    run.add_argument("--result-uri", required=True)
    run.add_argument("--input-id", required=True)
    run.add_argument("--input-snapshot", required=True)
    run.add_argument("--expected-count", required=True, type=int)
    run.add_argument("--worker-count", required=True, type=int)
    run.add_argument("--implementation-revision", required=True)
    run.add_argument("--sequence", required=True, type=int)
    run.add_argument("--rank", type=int, default=64)
    run.add_argument("--reg-param", type=float, default=0.1)
    run.add_argument("--max-iter", type=int, default=10)
    run.add_argument("--seed", type=int, default=42)

    compare = subparsers.add_parser("compare", help="Compare compatible benchmark reports")
    compare.add_argument("--single-result-uri", required=True)
    compare.add_argument("--distributed-result-uri", required=True)

    aggregate = subparsers.add_parser("aggregate", help="Publish the complete median report")
    aggregate.add_argument("--report-uri", required=True, action="append")
    aggregate.add_argument("--output-uri", required=True)
    return parser


def main(argv: list[str] | None = None) -> None:
    arguments = build_parser().parse_args(argv)
    _preflight(arguments)
    spark = create_spark_session(arguments.spark_master, arguments.spark_app_name)
    active_error = None
    try:
        spark.conf.set("spark.sql.session.timeZone", "UTC")
        if arguments.command == "prepare":
            result = prepare_input(
                spark,
                source_uri=arguments.source_uri,
                output_uri=arguments.output_uri,
                input_id=arguments.input_id,
                source_snapshot=arguments.source_snapshot,
                size=arguments.size,
                seed=arguments.seed,
            )
        elif arguments.command == "run":
            settings = BenchmarkSettings(
                input_id=arguments.input_id,
                input_snapshot=arguments.input_snapshot,
                expected_count=arguments.expected_count,
                worker_count=arguments.worker_count,
                implementation_revision=arguments.implementation_revision,
                sequence=arguments.sequence,
                rank=arguments.rank,
                reg_param=arguments.reg_param,
                max_iter=arguments.max_iter,
                seed=arguments.seed,
            )
            result = run_benchmark(
                spark,
                input_uri=arguments.input_uri,
                input_manifest_uri=arguments.input_manifest_uri,
                result_uri=arguments.result_uri,
                settings=settings,
            )
        elif arguments.command == "compare":
            result = compare_results(
                read_report(spark, arguments.single_result_uri),
                read_report(spark, arguments.distributed_result_uri),
            )
        else:
            reports = [read_report(spark, uri) for uri in arguments.report_uri]
            result = publish_aggregate(spark, reports, arguments.output_uri)
        print(json.dumps(result, ensure_ascii=False, sort_keys=True, allow_nan=False))
    except BaseException as error:
        active_error = error
        raise
    finally:
        try:
            spark.stop()
        except Exception:
            if active_error is None:
                raise
            LOGGER.exception("Spark cleanup failed while handling the benchmark error")


def _preflight(arguments: argparse.Namespace) -> None:
    if arguments.command == "prepare":
        validate_storage_uri(arguments.source_uri, "benchmark source")
        validate_storage_uri(arguments.output_uri, "benchmark output")
        _require_separate_paths(arguments.source_uri, arguments.output_uri)
        if arguments.size <= 0:
            raise ValueError("sample size must be positive")
        if not arguments.input_id.strip() or not arguments.source_snapshot.strip():
            raise ValueError("input ID and source snapshot are required")
    elif arguments.command == "run":
        validate_storage_uri(arguments.input_uri, "benchmark input")
        validate_storage_uri(arguments.input_manifest_uri, "benchmark input manifest")
        validate_storage_uri(arguments.result_uri, "benchmark result")
        _require_separate_paths(arguments.input_uri, arguments.result_uri)
        BenchmarkSettings(
            input_id=arguments.input_id,
            input_snapshot=arguments.input_snapshot,
            expected_count=arguments.expected_count,
            worker_count=arguments.worker_count,
            implementation_revision=arguments.implementation_revision,
            sequence=arguments.sequence,
            rank=arguments.rank,
            reg_param=arguments.reg_param,
            max_iter=arguments.max_iter,
            seed=arguments.seed,
        ).validate()
    elif arguments.command == "compare":
        validate_storage_uri(arguments.single_result_uri, "single benchmark result")
        validate_storage_uri(arguments.distributed_result_uri, "distributed benchmark result")
        if _location(arguments.single_result_uri) == _location(arguments.distributed_result_uri):
            raise ValueError("benchmark comparison requires two different result paths")
    else:
        validate_storage_uri(arguments.output_uri, "benchmark aggregate output")
        if len(arguments.report_uri) != 18:
            raise ValueError("aggregate requires exactly 18 benchmark reports")
        for uri in arguments.report_uri:
            validate_storage_uri(uri, "benchmark report")
            _require_separate_paths(uri, arguments.output_uri)


def _require_separate_paths(left: str, right: str) -> None:
    left_location = _location(left)
    right_location = _location(right)
    if left_location[:2] != right_location[:2]:
        return
    left_path = left_location[2]
    right_path = right_location[2]
    if (
        left_path == right_path
        or left_path.startswith(right_path.rstrip("/") + "/")
        or right_path.startswith(left_path.rstrip("/") + "/")
    ):
        raise ValueError("benchmark input and output paths must not overlap")


def _location(uri: str) -> tuple[str, str, str]:
    parsed = urlsplit(uri)
    return parsed.scheme.casefold(), parsed.netloc.casefold(), posixpath.normpath(parsed.path)


if __name__ == "__main__":
    main()
