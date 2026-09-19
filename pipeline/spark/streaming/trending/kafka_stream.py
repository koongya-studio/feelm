"""Spark Structured Streaming adapter for VIEW events."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from threading import RLock

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql.functions import col, collect_set, from_json, window
from pyspark.sql.types import LongType, StringType, StructField, StructType, TimestampType

from streaming.trending.aggregation import (
    align_publish_time,
    build_snapshot,
    items_from_snapshot,
)
from streaming.trending.bucket_store import ViewerBucket, read_window_events, write_buckets
from streaming.trending.redis_store import publish, read_snapshot

VIEW_SCHEMA = StructType(
    [
        StructField("eventId", StringType(), False),
        StructField("eventType", StringType(), False),
        StructField("occurredAt", TimestampType(), False),
        StructField("userId", LongType(), False),
        StructField(
            "payload",
            StructType([StructField("movieId", LongType(), False)]),
            False,
        ),
    ]
)


def build_view_stream(
    spark: SparkSession, *, kafka_bootstrap_servers: str, kafka_topic: str
) -> DataFrame:
    """Create a deduplicated VIEW stream with one bounded Spark state operator."""
    if not kafka_bootstrap_servers:
        raise ValueError("kafka_bootstrap_servers is required")
    if not kafka_topic:
        raise ValueError("kafka_topic is required")
    source = (
        spark.readStream.format("kafka")
        .option("kafka.bootstrap.servers", kafka_bootstrap_servers)
        .option("subscribe", kafka_topic)
        .option("startingOffsets", "earliest")
        .load()
    )
    return deduplicate_view_events(source)


def deduplicate_view_events(source: DataFrame) -> DataFrame:
    """Validate and deduplicate by eventId before the batch-only bucket update."""
    valid = valid_view_events(source)
    return (
        valid.withWatermark("occurredAt", "10 minutes")
        .dropDuplicates(["eventId"])
        .select("occurredAt", "userId", col("payload.movieId").alias("movieId"))
    )


def valid_view_events(source: DataFrame) -> DataFrame:
    """Reject records that cannot be a valid VIEW envelope before aggregation."""
    events = source.select(from_json(col("value").cast("string"), VIEW_SCHEMA).alias("event"))
    event = col("event")
    valid = events.where(
        (event.eventType == "VIEW")
        & event.eventId.rlike(
            r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
        )
        & event.occurredAt.isNotNull()
        & (event.userId > 0)
        & event.payload.movieId.isNotNull()
        & (event.payload.movieId > 0)
    ).select("event.*")
    return valid


def start_view_stream(
    spark: SparkSession,
    *,
    kafka_bootstrap_servers: str,
    kafka_topic: str,
    checkpoint_uri: str,
    redis: object,
    publication_lock: RLock,
) -> object:
    """Consume VIEW events without tying publication to incoming micro-batches."""
    if not checkpoint_uri:
        raise ValueError("checkpoint_uri is required")
    spark.conf.set("spark.sql.session.timeZone", "UTC")
    events = build_view_stream(
        spark, kafka_bootstrap_servers=kafka_bootstrap_servers, kafka_topic=kafka_topic
    )

    def process_batch(batch: DataFrame, _: int) -> None:
        with publication_lock:
            store_batch(batch, redis=redis)

    return (
        events.writeStream.outputMode("append")
        .option("checkpointLocation", checkpoint_uri)
        .foreachBatch(process_batch)
        .start()
    )


def store_batch(batch: DataFrame, *, redis: object, now: datetime | None = None) -> None:
    """Store only buckets still eligible for the latest 120-minute window."""
    if now is None:
        now = datetime.now(timezone.utc)
    cutoff = align_publish_time(now) - timedelta(hours=2)
    rows = (
        batch.where(col("occurredAt") >= cutoff)
        .groupBy(window("occurredAt", "5 minutes"), "movieId")
        .agg(collect_set("userId").alias("userIds"))
        .collect()
    )
    write_buckets(
        redis,
        (
            ViewerBucket(
                start=datetime.fromtimestamp(row["window"]["start"].timestamp(), timezone.utc),
                movie_id=int(row["movieId"]),
                user_ids=frozenset(int(user_id) for user_id in row["userIds"]),
            )
            for row in rows
        ),
    )


def publish_boundary(redis: object, *, now: datetime, publication_lock: RLock) -> None:
    """Publish one UTC boundary even if no VIEW event has arrived."""
    to = align_publish_time(now)
    with publication_lock:
        previous = read_snapshot(redis)
        previous_period = previous.get("period") if previous else None
        previous_to = previous_period.get("to") if isinstance(previous_period, dict) else None
        if isinstance(previous_to, str) and previous_to >= to.isoformat().replace("+00:00", "Z"):
            return
        events = read_window_events(redis, to=to)
        payload = build_snapshot(events, to=to, previous_items=items_from_snapshot(previous))
        if payload["items"]:
            publish(redis, payload)
