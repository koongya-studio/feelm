import math
import os
import socket
import time

import numpy
from pyspark.ml.recommendation import ALS
from pyspark.sql import SparkSession


EXPECTED_NUMPY_VERSION = "2.2.6"


def inspect_partition(partition_id, rows):
    count = sum(1 for _ in rows)
    time.sleep(2)
    return [(partition_id, socket.gethostname(), os.getpid(), numpy.__version__, count)]


if numpy.__version__ != EXPECTED_NUMPY_VERSION:
    raise RuntimeError(
        f"Driver NumPy version must be {EXPECTED_NUMPY_VERSION}, got {numpy.__version__}"
    )

spark = SparkSession.builder.appName("E106-Spark-ML-Check").getOrCreate()
sc = spark.sparkContext
sc.setLogLevel("WARN")

try:
    executor_rows = (
        sc.parallelize(range(8000), 8)
        .mapPartitionsWithIndex(inspect_partition)
        .collect()
    )
    executor_hosts = sorted({row[1] for row in executor_rows})
    executor_numpy_versions = sorted({row[3] for row in executor_rows})

    if len(executor_hosts) < 2:
        raise RuntimeError(f"Expected two executor hosts, got {executor_hosts}")
    if executor_numpy_versions != [EXPECTED_NUMPY_VERSION]:
        raise RuntimeError(
            "Executor NumPy versions must all be "
            f"{EXPECTED_NUMPY_VERSION}, got {executor_numpy_versions}"
        )

    ratings = spark.createDataFrame(
        [
            (1, 1, 5.0),
            (1, 2, 3.0),
            (1, 3, 1.0),
            (2, 1, 4.0),
            (2, 2, 1.0),
            (2, 4, 5.0),
            (3, 1, 1.0),
            (3, 3, 5.0),
            (3, 4, 4.0),
            (4, 2, 5.0),
            (4, 3, 4.0),
            (4, 4, 1.0),
        ],
        ["user_id", "movie_id", "rating"],
    ).repartition(4)

    model = ALS(
        userCol="user_id",
        itemCol="movie_id",
        ratingCol="rating",
        rank=2,
        maxIter=3,
        regParam=0.1,
        numUserBlocks=2,
        numItemBlocks=2,
        coldStartStrategy="drop",
        seed=290,
    ).fit(ratings)
    predictions = model.transform(ratings).select("prediction").collect()

    if len(predictions) != 12:
        raise RuntimeError(f"Expected 12 ALS predictions, got {len(predictions)}")
    if any(not math.isfinite(row.prediction) for row in predictions):
        raise RuntimeError("ALS produced a non-finite prediction")

    print(f"DRIVER_NUMPY_VERSION={numpy.__version__}")
    print(f"EXECUTOR_NUMPY_VERSIONS={','.join(executor_numpy_versions)}")
    print(f"EXECUTOR_HOSTS={','.join(executor_hosts)}")
    print(f"ALS_PREDICTION_COUNT={len(predictions)}")
    print("E106 SPARK ML CHECK: SUCCESS")
finally:
    spark.stop()
