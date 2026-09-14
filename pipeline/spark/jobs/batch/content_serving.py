"""Publish one immutable content-artifacts-v2 serving bundle.

Publication order: mapping snapshot, TMDb snapshot and movie-metadata snapshot
first; each upstream revision is an explicit input of this content version.
Ratings and the service mapping are read from the same mapping snapshot root.
"""

from __future__ import annotations

import argparse
import json

from batch.content.serving import build_content_artifacts, save_content_artifacts
from common.spark import create_spark_session
from jobs.batch.als_serving import validate_immutable_artifact_uri


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mapping-snapshot-uri", required=True)
    parser.add_argument("--mapping-revision", required=True)
    parser.add_argument("--tmdb-snapshot-uri", required=True)
    parser.add_argument("--tmdb-snapshot-revision", required=True)
    parser.add_argument("--movie-metadata-uri", required=True)
    parser.add_argument("--movie-metadata-revision", required=True)
    parser.add_argument("--input-snapshot", required=True)
    parser.add_argument("--output-uri", required=True)
    parser.add_argument("--content-version", required=True)
    parser.add_argument("--implementation-revision")
    parser.add_argument("--spark-master")
    arguments = parser.parse_args()
    for name in ("mapping_snapshot_uri", "tmdb_snapshot_uri", "movie_metadata_uri", "output_uri"):
        validate_immutable_artifact_uri(getattr(arguments, name), f"--{name.replace('_', '-')}")
    mapping_root = arguments.mapping_snapshot_uri.rstrip("/")

    spark = create_spark_session(arguments.spark_master, "feelm-content-artifacts-v2")
    try:
        build = build_content_artifacts(
            ratings=spark.read.parquet(f"{mapping_root}/ratings"),
            service_mapping=spark.read.parquet(f"{mapping_root}/service-movie-id-map"),
            tmdb_snapshot=spark.read.parquet(arguments.tmdb_snapshot_uri),
            movie_metadata=spark.read.parquet(arguments.movie_metadata_uri),
            input_uri=mapping_root,
            mapping_revision=arguments.mapping_revision,
            tmdb_snapshot_revision=arguments.tmdb_snapshot_revision,
            movie_metadata_revision=arguments.movie_metadata_revision,
            input_snapshot=arguments.input_snapshot,
            content_version=arguments.content_version,
            implementation_revision=arguments.implementation_revision,
        )
        published = save_content_artifacts(spark, build, arguments.output_uri)
        metadata = build.metadata.asDict()
        print(
            json.dumps(
                {
                    "version_uri": published,
                    **{
                        key: metadata[key]
                        for key in (
                            "content_version",
                            "service_movie_count",
                            "supported_movie_count",
                            "vocabulary_size",
                            "prior_user_count",
                            "prior_rating_count",
                            "content_revision",
                        )
                    },
                }
            )
        )
    finally:
        spark.stop()


if __name__ == "__main__":
    main()
