"""Create and verify an immutable input manifest for the ALS baseline run."""

from __future__ import annotations

import argparse
import json
import logging
import os
import sys
from collections.abc import Sequence

from batch.train.input_snapshot import (
    ManifestCleanupError,
    create_input_manifest,
    validate_input_snapshot,
)
from batch.train.inputs import load_training_inputs
from batch.train.settings import validate_storage_uri
from common.spark import create_spark_session

LOGGER = logging.getLogger(__name__)


def _manifest_cleanup_handle(spark, manifest_uri: str):
    path = spark._jvm.org.apache.hadoop.fs.Path(manifest_uri)  # noqa: SLF001
    configuration = spark.sparkContext._jsc.hadoopConfiguration()  # noqa: SLF001
    return path, path.getFileSystem(configuration)


def _delete_owned_manifest(cleanup_handle) -> None:
    path, file_system = cleanup_handle
    if not file_system.delete(path, False):
        raise OSError("manifest cleanup delete returned false")


def parse_args(arguments: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spark-master")
    parser.add_argument("--input-uri", required=True)
    parser.add_argument("--manifest-uri", required=True)
    parser.add_argument("--ratings-source", required=True)
    parser.add_argument("--movies-source", required=True)
    parser.add_argument("--links-source", required=True)
    parser.add_argument("--service-movie-export", required=True)
    parser.add_argument("--preprocessing-revision", required=True)
    parser.add_argument("--preprocessing-settings-json", required=True)
    return parser.parse_args(arguments)


def main(arguments: Sequence[str] | None = None) -> int:
    args = parse_args(arguments)
    try:
        preprocessing_settings = json.loads(args.preprocessing_settings_json)
    except json.JSONDecodeError as error:
        print(
            f"ALS input manifest precondition failed: invalid settings JSON: {error}",
            file=sys.stderr,
        )
        return 1
    if not isinstance(preprocessing_settings, dict):
        print(
            "ALS input manifest precondition failed: settings JSON must be an object",
            file=sys.stderr,
        )
        return 1

    storage_uris = {
        "input": args.input_uri,
        "input manifest": args.manifest_uri,
        "ratings source": args.ratings_source,
        "movies source": args.movies_source,
        "links source": args.links_source,
        "service movie export": args.service_movie_export,
    }
    try:
        for label, uri in storage_uris.items():
            validate_storage_uri(uri, label)
    except ValueError as error:
        print(f"ALS input manifest precondition failed: {error}", file=sys.stderr)
        return 1

    spark = create_spark_session(
        args.spark_master or os.environ.get("RECOMMENDATION_SPARK_MASTER"),
        "feelm-als-input-manifest",
    )
    manifest_created = False
    snapshot = None
    failure = None
    cleanup_failure = None
    cleanup_attempted = False
    cleanup_handle = None
    try:
        cleanup_handle = _manifest_cleanup_handle(spark, args.manifest_uri)
        ratings_uri = f"{args.input_uri.rstrip('/')}/ratings"
        service_map_uri = f"{args.input_uri.rstrip('/')}/service-movie-id-map"
        inputs = load_training_inputs(spark, ratings_uri, service_map_uri)
        snapshot = create_input_manifest(
            spark,
            input_uri=args.input_uri,
            manifest_uri=args.manifest_uri,
            source_uris={
                "ratings": args.ratings_source,
                "movies": args.movies_source,
                "links": args.links_source,
                "service_movie_export": args.service_movie_export,
            },
            preprocessing_revision=args.preprocessing_revision,
            preprocessing_settings=preprocessing_settings,
        )
        manifest_created = True
        validate_input_snapshot(
            spark,
            input_uri=args.input_uri,
            manifest_uri=args.manifest_uri,
            expected_snapshot=snapshot,
            inputs=inputs,
        )
    except Exception as error:
        failure = error
        if isinstance(error.__cause__, ManifestCleanupError):
            cleanup_failure = error.__cause__
        if manifest_created:
            cleanup_attempted = True
            try:
                _delete_owned_manifest(cleanup_handle)
            except Exception as error:
                cleanup_failure = error
    finally:
        try:
            spark.stop()
        except Exception as error:
            if failure is None:
                failure = error
                LOGGER.exception("ALS input manifest Spark stop failed")
            else:
                LOGGER.exception(
                    "ALS input manifest Spark stop failed while handling: %s",
                    failure,
                )

    if failure is not None and manifest_created and not cleanup_attempted:
        try:
            _delete_owned_manifest(cleanup_handle)
        except Exception as error:
            cleanup_failure = error

    if failure is not None:
        print(f"ALS input manifest precondition failed: {failure}", file=sys.stderr)
        if cleanup_failure is not None:
            print(f"ALS input manifest cleanup failed: {cleanup_failure}", file=sys.stderr)
        return 1
    print(json.dumps({"input_snapshot": snapshot}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
