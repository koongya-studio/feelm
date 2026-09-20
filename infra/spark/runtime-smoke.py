"""Offline smoke test of the built runtime, before publishing it."""

import math
import os

import numpy
import psycopg
import redis
import torch
import transformers
from pyspark.ml.recommendation import ALS
from pyspark.sql import SparkSession


def main():
    assert os.getuid() != 0, "runtime must run as the spark user"
    assert numpy.__version__ == "2.2.6"
    assert redis.__version__ == "5.3.1"
    assert psycopg.__version__
    assert transformers.__version__
    assert torch.__version__.endswith("+cpu")
    assert torch.ones(2).sum().item() == 2
    os.environ["SPARK_LOCAL_IP"] = "127.0.0.1"
    spark = (
        SparkSession.builder.master("local[2]")
        .appName("runtime-image-smoke")
        .config("spark.ui.enabled", "false")
        .config("spark.driver.host", "127.0.0.1")
        .config("spark.driver.bindAddress", "127.0.0.1")
        .config("spark.sql.shuffle.partitions", "2")
        .getOrCreate()
    )
    try:
        assert spark.version == "4.1.3"
        versions = spark.sparkContext.parallelize([1, 2], 2).map(
            lambda _: __import__("numpy").__version__
        ).collect()
        assert versions == [numpy.__version__] * 2
        ratings = spark.createDataFrame(
            [(u, i, float((u + i) % 5 + 1)) for u in range(3) for i in range(4)],
            "user INT, item INT, rating FLOAT",
        )
        predictions = ALS(rank=2, maxIter=1, seed=42).fit(ratings).transform(ratings).collect()
        assert len(predictions) == 12
        assert all(math.isfinite(row.prediction) for row in predictions)
    finally:
        spark.stop()
    print("SPARK RUNTIME SMOKE: SUCCESS")


if __name__ == "__main__":
    main()
