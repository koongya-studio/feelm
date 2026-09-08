"""Command-line entry point for MovieLens/TMDb movie ID mapping."""

from __future__ import annotations

import argparse
import json
import os
import sys
from collections.abc import Iterable

from pyspark.errors import AnalysisException

from batch.prepare.movie_id_mapping.io import (
    create_spark_session,
    read_csv,
    write_pipeline,
)
from batch.prepare.movie_id_mapping.pipeline import build_pipeline


def parse_args(arguments: Iterable[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--spark-master", help="Spark master; overrides RECOMMENDATION_SPARK_MASTER"
    )
    parser.add_argument("--ratings", required=True, help="MovieLens ratings.csv path")
    parser.add_argument("--movies", required=True, help="MovieLens movies.csv path")
    parser.add_argument("--links", required=True, help="MovieLens links.csv path")
    parser.add_argument(
        "--service-movies",
        required=True,
        help="Backend export CSV containing service_movie_id and tmdb_id",
    )
    parser.add_argument("--output", required=True, help="Output directory or HDFS URI")
    parser.add_argument(
        "--write-mode",
        choices=["errorifexists", "overwrite"],
        default="errorifexists",
    )
    return parser.parse_args(arguments)


def main(arguments: Iterable[str] | None = None) -> int:
    args = parse_args(arguments)
    spark = create_spark_session(args.spark_master or os.environ.get("RECOMMENDATION_SPARK_MASTER"))
    try:
        service_movies_source = read_csv(spark, args.service_movies)
        result = build_pipeline(
            ratings_source=read_csv(spark, args.ratings),
            movies_source=read_csv(spark, args.movies),
            links_source=read_csv(spark, args.links),
            service_movies_source=service_movies_source,
        )
        print(json.dumps(result.validation_report, ensure_ascii=False, indent=2))
        if not result.validation_report["all_checks_passed"]:
            return 1
        write_pipeline(result, args.output, args.write_mode)
        return 0
    except (AnalysisException, ValueError) as error:
        print(f"Movie ID mapping precondition failed: {error}", file=sys.stderr)
        return 1
    finally:
        spark.stop()


if __name__ == "__main__":
    raise SystemExit(main())
