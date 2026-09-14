"""Publish an immutable service-movie metadata snapshot for Fold-in Consumer."""

from __future__ import annotations

import argparse

from batch.prepare.movie_metadata import build_movie_metadata, publish_movie_metadata
from common.spark import create_spark_session


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mapping-uri", required=True)
    parser.add_argument("--tmdb-movies-uri", required=True)
    parser.add_argument("--output-uri", required=True)
    parser.add_argument("--input-snapshot", required=True)
    parser.add_argument("--flavor-uri")
    parser.add_argument("--spark-master")
    arguments = parser.parse_args()

    spark = create_spark_session(arguments.spark_master, "feelm-movie-metadata")
    try:
        flavor = spark.read.parquet(arguments.flavor_uri) if arguments.flavor_uri else None
        metadata = build_movie_metadata(
            spark.read.parquet(arguments.mapping_uri),
            spark.read.parquet(arguments.tmdb_movies_uri),
            arguments.input_snapshot,
            flavor,
        )
        print(publish_movie_metadata(metadata, arguments.output_uri))
    finally:
        spark.stop()


if __name__ == "__main__":
    main()
