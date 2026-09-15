"""Small, explicit Kafka/HDFS verification runs; never deletes data or publishes events."""

import argparse
import json
import re
from pathlib import Path
from urllib.parse import urlsplit


MAX_EVENTS = 1000


def validate_base(value):
    uri = urlsplit(value)
    prefix = "/verification/S15P21E106-137/"
    parts = uri.path.split("/")
    if (
        uri.scheme != "hdfs"
        or not uri.hostname
        or uri.username is not None
        or uri.password is not None
        or uri.query
        or uri.fragment
        or not uri.path.startswith(prefix)
        or not uri.path[len(prefix):].strip("/")
        or any(part in (".", "..") for part in parts)
        or not re.fullmatch(r"/[A-Za-z0-9_/-]+", uri.path)
    ):
        raise ValueError("Use a dedicated hdfs://host/verification/S15P21E106-137/<run> path")
    return value.rstrip("/")


def load_expected(filename):
    events = json.loads(Path(filename).read_text(encoding="utf-8"))
    if not isinstance(events, list) or not 1 <= len(events) <= MAX_EVENTS:
        raise ValueError("Expected events must be a nonempty JSON array of at most 1000 events")
    expected = {}
    for event in events:
        if (
            not isinstance(event, dict)
            or set(event) != {"event_id", "value"}
            or not isinstance(event["event_id"], str)
            or not event["event_id"]
            or type(event["value"]) is not int
            or event["event_id"] in expected
        ):
            raise ValueError("Each event needs a unique nonempty event_id and an integer value")
        expected[event["event_id"]] = event["value"]
    return expected


def verify_rows(rows, expected, topic):
    if len(rows) != len(expected):
        raise ValueError("Unexpected row count; do not republish events or delete checkpoints")
    actual = {}
    records = set()
    offsets = set()
    for row in rows:
        event = json.loads(row["payload"])
        if (
            not isinstance(event, dict)
            or set(event) != {"event_id", "value"}
            or not isinstance(event["event_id"], str)
            or type(event["value"]) is not int
            or event["event_id"] in actual
            or row["topic"] != topic
        ):
            raise ValueError("Invalid payload, duplicate event ID, or unexpected topic")
        actual[event["event_id"]] = event["value"]
        offset = (row["topic"], row["partition"], row["offset"])
        if offset in offsets:
            raise ValueError("Duplicate Kafka offset")
        offsets.add(offset)
        records.add((*offset, row["payload"]))
    if actual != expected:
        raise ValueError("Event contents differ from expectations")
    return records, offsets


def check_paths(exists, base, mode):
    if mode == "init":
        if exists(base):
            raise ValueError("Init requires a new run directory; existing path is protected")
    else:
        for suffix in ("output", "output/_spark_metadata", "checkpoint/metadata", "checkpoint/commits"):
            if not exists(base + "/" + suffix):
                raise ValueError("Resume requires existing output and checkpoint metadata: " + suffix)


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--mode", choices=("init", "resume"), required=True)
    result.add_argument("--topic", required=True)
    result.add_argument("--bootstrap-server", required=True)
    result.add_argument("--base", required=True)
    result.add_argument("--expected-before", help="JSON file required for resume")
    result.add_argument("--expected-after", required=True, help="JSON file of all final events")
    result.add_argument("--timeout-seconds", type=int, default=120)
    return result


def main():
    args = parser().parse_args()
    base = validate_base(args.base)
    if not re.fullmatch(r"e106-137-check-[A-Za-z0-9_-]+", args.topic):
        raise ValueError("Use a dedicated e106-137-check-* topic")
    if args.timeout_seconds <= 0:
        raise ValueError("Timeout must be positive")
    if (args.mode == "resume") != bool(args.expected_before):
        raise ValueError("Use --expected-before only and always with resume")
    before_expected = load_expected(args.expected_before) if args.expected_before else {}
    after_expected = load_expected(args.expected_after)
    if not before_expected.items() <= after_expected.items():
        raise ValueError("Final expectations must preserve all earlier events")

    # Import after argument validation so local unit tests do not need PySpark.
    from pyspark.sql import SparkSession

    spark = (
        SparkSession.builder.appName("E106-137-Kafka-HDFS-Check")
        .config("spark.hadoop.dfs.replication", "2")
        .config("spark.hadoop.dfs.client.use.datanode.hostname", "true")
        .getOrCreate()
    )
    spark.sparkContext.setLogLevel("WARN")
    query = None
    output = base + "/output"
    checkpoint = base + "/checkpoint"
    print("OUTPUT_PATH=" + output, flush=True)
    print("CHECKPOINT_PATH=" + checkpoint, flush=True)

    try:
        jvm = spark.sparkContext._jvm
        conf = spark.sparkContext._jsc.hadoopConfiguration()
        fs = jvm.org.apache.hadoop.fs.FileSystem.get(jvm.java.net.URI(base), conf)
        hpath = jvm.org.apache.hadoop.fs.Path
        check_paths(lambda path: fs.exists(hpath(path)), base, args.mode)

        def read_rows():
            return spark.read.parquet(output).limit(MAX_EVENTS + 1).collect()

        before_records, before_offsets = set(), set()
        if args.mode == "resume":
            before_records, before_offsets = verify_rows(read_rows(), before_expected, args.topic)

        events = (
            spark.readStream.format("kafka")
            .option("kafka.bootstrap.servers", args.bootstrap_server)
            .option("subscribe", args.topic)
            .option("startingOffsets", "earliest")
            .option("failOnDataLoss", "true")
            .load()
            .selectExpr("topic", "partition", "offset", "CAST(value AS STRING) AS payload")
        )
        query = (
            events.writeStream.format("parquet").outputMode("append")
            .option("path", output).option("checkpointLocation", checkpoint)
            .trigger(availableNow=True).start()
        )
        if not query.awaitTermination(args.timeout_seconds):
            raise TimeoutError("Query did not finish; preserve output/checkpoint before investigating")

        after_records, after_offsets = verify_rows(read_rows(), after_expected, args.topic)
        if not before_records <= after_records:
            raise ValueError("Previously stored rows were modified or lost")

        print("BEFORE_COUNT=" + str(len(before_records)))
        print("AFTER_COUNT=" + str(len(after_records)))
        print("NEW_OFFSETS=" + str(len(after_offsets - before_offsets)))
        print("UNIQUE_KAFKA_OFFSETS=" + str(len(after_offsets)))
        print("REQUESTED_REPLICATION=2")
        print("KAFKA_SPARK_HDFS_CHECK: SUCCESS")
    finally:
        try:
            if query is not None and query.isActive:
                query.stop()
        finally:
            spark.stop()


if __name__ == "__main__":
    main()
