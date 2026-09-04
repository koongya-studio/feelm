"""Minimal Parquet I/O job used to verify the recommendation runtime."""

from __future__ import annotations

from pyspark.sql import SparkSession

from common.settings import JobSettings
from common.spark import create_spark_session


def copy_parquet(
    spark: SparkSession,
    input_uri: str,
    output_uri: str,
) -> int:
    """Read a Parquet dataset and write an equivalent dataset to another URI."""
    frame = spark.read.parquet(input_uri)
    row_count = frame.count()
    frame.write.mode("overwrite").parquet(output_uri)
    return row_count


def main() -> None:
    settings = JobSettings.from_sources()
    input_uri, output_uri = settings.require_io_uris()
    spark = create_spark_session(settings.spark_master, settings.spark_app_name)
    try:
        row_count = copy_parquet(spark, input_uri, output_uri)
        print(f"Parquet round-trip completed: rows={row_count}")
    finally:
        spark.stop()


if __name__ == "__main__":
    main()
