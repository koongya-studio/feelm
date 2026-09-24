"""Prepare synthetic data and time Spark XGBoost training on 1 or 2 Workers."""

from __future__ import annotations

import argparse
import json
import logging
import posixpath
import re
from urllib.parse import urlsplit

from batch.benchmark.gbt import (
    compare_results,
    prepare_input,
    read_report,
    run_benchmark,
    validate_shape,
)
from batch.train.settings import validate_storage_uri
from common.spark import create_spark_session

LOGGER = logging.getLogger(__name__)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spark-master")
    subparsers = parser.add_subparsers(dest="command", required=True)
    prepare = subparsers.add_parser("prepare")
    prepare.add_argument("--output-uri", required=True)
    prepare.add_argument("--input-id", required=True)
    prepare.add_argument("--rows", required=True, type=int)
    prepare.add_argument("--features", type=int, default=71)
    prepare.add_argument("--partitions", type=int, default=8)
    prepare.add_argument("--seed", type=int, default=42)
    run = subparsers.add_parser("run")
    run.add_argument("--input-root", required=True)
    run.add_argument("--input-snapshot", required=True)
    run.add_argument("--result-uri", required=True)
    run.add_argument("--worker-count", required=True, type=int)
    run.add_argument("--revision", required=True)
    run.add_argument("--sequence", required=True, type=int)
    run.add_argument("--estimators", type=int, default=30)
    run.add_argument("--max-depth", type=int, default=4)
    run.add_argument("--seed", type=int, default=42)
    compare = subparsers.add_parser("compare")
    compare.add_argument("--single-result-uri", required=True)
    compare.add_argument("--distributed-result-uri", required=True)
    return parser


def main(argv: list[str] | None = None) -> None:
    args = build_parser().parse_args(argv)
    _preflight(args)
    spark = create_spark_session(args.spark_master, "feelm-gbt-distributed-benchmark")
    active_error = None
    try:
        if args.command == "prepare":
            result = prepare_input(
                spark,
                output_uri=args.output_uri,
                input_id=args.input_id,
                rows=args.rows,
                features=args.features,
                partitions=args.partitions,
                seed=args.seed,
            )
        elif args.command == "run":
            result = run_benchmark(
                spark,
                input_root=args.input_root,
                input_snapshot=args.input_snapshot,
                result_uri=args.result_uri,
                worker_count=args.worker_count,
                revision=args.revision,
                sequence=args.sequence,
                estimators=args.estimators,
                max_depth=args.max_depth,
                seed=args.seed,
            )
        else:
            result = compare_results(
                read_report(spark, args.single_result_uri),
                read_report(spark, args.distributed_result_uri),
            )
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
            LOGGER.exception("Spark cleanup failed after benchmark error")


def _preflight(args: argparse.Namespace) -> None:
    if args.command == "prepare":
        validate_storage_uri(args.output_uri, "benchmark output")
        validate_shape(args.rows, args.features, args.partitions)
        if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,127}", args.input_id):
            raise ValueError("input ID has an invalid format")
    elif args.command == "run":
        validate_storage_uri(args.input_root, "benchmark input")
        validate_storage_uri(args.result_uri, "benchmark result")
        if _overlap(args.input_root, args.result_uri):
            raise ValueError("benchmark input and output paths must not overlap")
        if args.worker_count not in (1, 2) or args.sequence <= 0:
            raise ValueError("worker count must be 1 or 2 and sequence positive")
        if not 1 <= args.estimators <= 200 or not 1 <= args.max_depth <= 10:
            raise ValueError("estimator settings exceed safe benchmark bounds")
        if not re.fullmatch(r"[0-9a-f]{40}", args.revision):
            raise ValueError("revision must be a 40-character Git SHA")
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", args.input_snapshot):
            raise ValueError("input snapshot must be a SHA-256 digest")
    else:
        validate_storage_uri(args.single_result_uri, "single result")
        validate_storage_uri(args.distributed_result_uri, "distributed result")
        if _overlap(args.single_result_uri, args.distributed_result_uri):
            raise ValueError("comparison requires separate result paths")


def _overlap(left: str, right: str) -> bool:
    a, b = urlsplit(left), urlsplit(right)
    if (a.scheme.casefold(), a.netloc.casefold()) != (b.scheme.casefold(), b.netloc.casefold()):
        return False
    ap, bp = posixpath.normpath(a.path), posixpath.normpath(b.path)
    return ap == bp or ap.startswith(bp.rstrip("/") + "/") or bp.startswith(ap.rstrip("/") + "/")


if __name__ == "__main__":
    main()
