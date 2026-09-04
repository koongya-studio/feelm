"""Spark session construction shared by recommendation jobs."""

from pyspark.sql import SparkSession


def create_spark_session(master: str | None, app_name: str) -> SparkSession:
    """Create a Spark session without embedding environment-specific resource settings."""
    builder = SparkSession.builder.appName(app_name)
    if master:
        builder = builder.master(master)
    return builder.getOrCreate()
