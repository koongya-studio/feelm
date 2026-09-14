"""Publish an immutable normalized TMDb snapshot for the service movie catalog.

The mapping snapshot must already be published; its revision is an explicit
input so the catalog scope of the TMDb snapshot is traceable.
"""

from __future__ import annotations

import argparse
import json

from pyspark.sql import functions as F

from batch.prepare.tmdb_snapshot import (
    build_tmdb_snapshot,
    publish_tmdb_snapshot,
    read_raw_keywords,
    read_raw_movies,
)
from common.spark import create_spark_session
from jobs.batch.als_serving import validate_immutable_artifact_uri


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--movies-raw-uri", required=True)
    parser.add_argument("--keywords-raw-uri", required=True)
    parser.add_argument("--mapping-uri", required=True)
    parser.add_argument("--mapping-revision", required=True)
    parser.add_argument("--output-uri", required=True)
    parser.add_argument("--spark-master")
    arguments = parser.parse_args()
    for name in ("mapping_uri", "output_uri"):
        validate_immutable_artifact_uri(getattr(arguments, name), f"--{name.replace('_', '-')}")

    spark = create_spark_session(arguments.spark_master, "feelm-tmdb-snapshot")
    try:
        mapping = spark.read.parquet(arguments.mapping_uri)
        revisions = [
            row["mapping_revision"]
            for row in mapping.select("mapping_revision").distinct().collect()
        ]
        if revisions != [arguments.mapping_revision]:
            raise ValueError("mapping revision does not match the published mapping snapshot")
        snapshot = build_tmdb_snapshot(
            read_raw_movies(spark, arguments.movies_raw_uri),
            read_raw_keywords(spark, arguments.keywords_raw_uri),
            mapping,
        )
        published = publish_tmdb_snapshot(snapshot, arguments.output_uri)
        summary = (
            spark.read.parquet(published)
            .agg(
                F.count(F.lit(1)).alias("rows"),
                F.sum(F.col("feature_eligible").cast("int")).alias("feature_eligible"),
                F.sum((~F.col("keywords_present")).cast("int")).alias("keywords_missing"),
                F.sum(F.col("title").isNull().cast("int")).alias("title_missing"),
                F.first("tmdb_snapshot_revision").alias("tmdb_snapshot_revision"),
            )
            .first()
            .asDict()
        )
        print(json.dumps({"output_uri": published, **summary}, ensure_ascii=False))
    finally:
        spark.stop()


if __name__ == "__main__":
    main()
