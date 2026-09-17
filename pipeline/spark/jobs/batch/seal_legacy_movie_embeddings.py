"""Copy a legacy E5 artifact to a checksummed, immutable version without inference."""

from __future__ import annotations

import argparse

from batch.embedding.e5 import seal_legacy_e5_embeddings
from common.spark import create_spark_session
from jobs.batch.als_serving import validate_immutable_artifact_uri


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-uri", required=True)
    parser.add_argument("--legacy-version", required=True)
    parser.add_argument("--new-version", required=True)
    parser.add_argument("--spark-master")
    arguments = parser.parse_args()
    validate_immutable_artifact_uri(arguments.output_uri, "--output-uri")
    spark = create_spark_session(arguments.spark_master, "feelm-seal-legacy-movie-embeddings")
    try:
        artifact_uri = seal_legacy_e5_embeddings(
            spark,
            arguments.output_uri,
            arguments.legacy_version,
            arguments.new_version,
        )
        print({"version_uri": artifact_uri, "legacy_version": arguments.legacy_version})
    finally:
        spark.stop()


if __name__ == "__main__":
    main()
