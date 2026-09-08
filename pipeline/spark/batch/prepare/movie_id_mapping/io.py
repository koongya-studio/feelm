"""Spark input and output helpers for the movie ID mapping pipeline."""

from __future__ import annotations

import json

from pyspark.sql import DataFrame, SparkSession

from batch.prepare.movie_id_mapping.contracts import PipelineResult
from common.spark import create_spark_session as create_common_spark_session


def read_csv(spark: SparkSession, path: str) -> DataFrame:
    return spark.read.option("header", True).option("mode", "PERMISSIVE").csv(path)


def _child_path(base: str, name: str) -> str:
    return base.rstrip("/\\") + "/" + name


def write_pipeline(result: PipelineResult, output_path: str, mode: str) -> None:
    result.ratings.write.mode(mode).parquet(_child_path(output_path, "ratings"))
    result.movies.write.mode(mode).parquet(_child_path(output_path, "movies"))
    result.movie_id_map.write.mode(mode).parquet(_child_path(output_path, "movie-id-map"))
    result.service_movie_id_map.write.mode(mode).parquet(
        _child_path(output_path, "service-movie-id-map")
    )
    spark = result.ratings.sparkSession
    report_json = json.dumps(result.validation_report, ensure_ascii=False, sort_keys=True)
    report_rows = spark.sparkContext.parallelize([report_json], 1)
    spark.read.json(report_rows).coalesce(1).write.mode(mode).json(
        _child_path(output_path, "validation-report")
    )


def create_spark_session(master: str | None = None) -> SparkSession:
    spark = create_common_spark_session(master, "feelm-movie-id-mapping")
    spark.conf.set("spark.sql.session.timeZone", "UTC")
    spark.sparkContext.setLogLevel("WARN")
    return spark
