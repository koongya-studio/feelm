from pyspark.sql import SparkSession
import os
import socket
import time


spark = (
    SparkSession.builder
    .appName("E106-Spark-Distributed-Check")
    .getOrCreate()
)

sc = spark.sparkContext
sc.setLogLevel("WARN")


def inspect_partition(partition_id, iterator):
    count = sum(1 for _ in iterator)

    # 작업이 너무 빨리 끝나 한 Worker에 몰리는 것을 방지
    time.sleep(2)

    return [
        (
            partition_id,
            socket.gethostname(),
            os.getpid(),
            count,
        )
    ]


results = (
    sc.parallelize(range(8000), 8)
    .mapPartitionsWithIndex(inspect_partition)
    .collect()
)

print()
print("========== PARTITION RESULTS ==========")

for partition_id, hostname, pid, count in sorted(results):
    print(
        f"partition={partition_id}, "
        f"host={hostname}, "
        f"pid={pid}, "
        f"count={count}"
    )

hosts = sorted(set(row[1] for row in results))

print()
print("========== EXECUTOR HOSTS ==========")

for host in hosts:
    print(host)

print()
print(f"UNIQUE_EXECUTOR_HOSTS={len(hosts)}")

if len(hosts) < 2:
    raise RuntimeError(
        f"Expected at least 2 executor hosts, but found: {hosts}"
    )

print("E106 SPARK DISTRIBUTED CHECK: SUCCESS")

spark.stop()