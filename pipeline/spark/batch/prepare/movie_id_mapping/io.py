"""Spark input and output helpers for the movie ID mapping pipeline."""

from __future__ import annotations

import json
from uuid import uuid4

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F

from batch.prepare.movie_id_mapping.contracts import PipelineResult
from batch.train.artifacts import _delete_path, _path_exists, _publish_directory
from common.spark import create_spark_session as create_common_spark_session


def read_csv(spark: SparkSession, path: str) -> DataFrame:
    return spark.read.option("header", True).option("mode", "PERMISSIVE").csv(path)


def _child_path(base: str, name: str) -> str:
    return base.rstrip("/\\") + "/" + name


def write_pipeline(result: PipelineResult, output_path: str, mode: str) -> None:
    """Atomically publish one immutable mapping snapshot root."""
    if mode != "errorifexists":
        raise ValueError("immutable mapping snapshots only support errorifexists mode")
    spark = result.ratings.sparkSession
    if _path_exists(spark, output_path):
        raise FileExistsError(f"movie ID mapping snapshot already exists: {output_path}")
    temporary_path = f"{output_path.rstrip('/')}.tmp-{uuid4().hex}"
    mapping_columns = ["service_movie_id", "tmdb_id", "movielens_movie_id", "mapping_status"]
    values = [
        F.coalesce(F.col(column).cast("string"), F.lit("<null>"))
        for column in mapping_columns
    ]
    canonical = F.concat_ws(
        "\x1f", *values
    )
    digest = result.service_movie_id_map.select(canonical.alias("row")).agg(
        F.sha2(F.concat_ws("\n", F.sort_array(F.collect_list("row"))), 256).alias("revision")
    ).first()["revision"]
    if digest is None:
        raise ValueError("service movie mapping must not be empty")
    mapping = result.service_movie_id_map.withColumn("mapping_revision", F.lit(f"sha256:{digest}"))
    report_json = json.dumps(result.validation_report, ensure_ascii=False, sort_keys=True)
    report_rows = spark.sparkContext.parallelize([report_json], 1)
    try:
        result.ratings.write.mode("errorifexists").parquet(_child_path(temporary_path, "ratings"))
        result.movies.write.mode("errorifexists").parquet(_child_path(temporary_path, "movies"))
        result.movie_id_map.write.mode("errorifexists").parquet(
            _child_path(temporary_path, "movie-id-map")
        )
        mapping.write.mode("errorifexists").parquet(
            _child_path(temporary_path, "service-movie-id-map")
        )
        spark.read.json(report_rows).coalesce(1).write.mode("errorifexists").json(
            _child_path(temporary_path, "validation-report")
        )
        revisions = [
            row["mapping_revision"]
            for row in spark.read.parquet(_child_path(temporary_path, "service-movie-id-map"))
            .select("mapping_revision")
            .distinct()
            .collect()
        ]
        if revisions != [f"sha256:{digest}"]:
            raise ValueError("mapping revision changed before publication")
        _publish_directory(spark, temporary_path, output_path)
    except Exception:
        _delete_path(spark, temporary_path)
        raise


def create_spark_session(master: str | None = None) -> SparkSession:
    spark = create_common_spark_session(master, "feelm-movie-id-mapping")
    spark.conf.set("spark.sql.session.timeZone", "UTC")
    spark.sparkContext.setLogLevel("WARN")
    return spark
