from pathlib import Path

from pyspark.sql import SparkSession

from jobs.batch.parquet_roundtrip import copy_parquet


def test_copy_parquet_roundtrip(tmp_path: Path) -> None:
    spark = (
        SparkSession.builder.master("local[1]")
        .appName("feelm-recommendation-test")
        .config("spark.ui.enabled", "false")
        .config("spark.sql.shuffle.partitions", "1")
        .getOrCreate()
    )
    try:
        source = tmp_path / "input"
        target = tmp_path / "output"
        source_rows = [(1, "movie-a"), (2, "movie-b")]
        spark.createDataFrame(source_rows, ["movie_id", "title"]).write.parquet(str(source))

        copied_rows = copy_parquet(spark, str(source), str(target))

        assert copied_rows == 2
        rows = spark.read.parquet(str(target)).orderBy("movie_id").collect()
        assert [(row.movie_id, row.title) for row in rows] == [
            (1, "movie-a"),
            (2, "movie-b"),
        ]
    finally:
        spark.stop()
