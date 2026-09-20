"""Run the VIEW-to-trending Redis Spark Structured Streaming job."""

from __future__ import annotations

import argparse
from collections.abc import Callable, Sequence
from datetime import datetime, timezone
from threading import RLock

from common.spark import create_spark_session
from streaming.party.runtime import redis_writer
from streaming.trending.aggregation import PUBLISH_INTERVAL, align_publish_time
from streaming.trending.kafka_stream import publish_boundary, start_view_stream


def parse_arguments(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spark-master")
    parser.add_argument("--kafka-bootstrap-servers", required=True)
    parser.add_argument("--kafka-topic", default="feelm.view.v1")
    parser.add_argument("--checkpoint-uri", required=True)
    parser.add_argument("--redis-url", required=True)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> None:
    arguments = parse_arguments(argv)
    spark = create_spark_session(arguments.spark_master, "feelm-trending")
    redis = redis_writer(arguments.redis_url)
    publication_lock = RLock()
    query = start_view_stream(
        spark,
        kafka_bootstrap_servers=arguments.kafka_bootstrap_servers,
        kafka_topic=arguments.kafka_topic,
        checkpoint_uri=arguments.checkpoint_uri,
        redis=redis,
        publication_lock=publication_lock,
    )
    try:
        run_publisher(query, redis=redis, publication_lock=publication_lock)
    finally:
        if query.isActive:
            query.stop()


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


def run_publisher(
    query: object,
    *,
    redis: object,
    publication_lock: RLock,
    clock: Callable[[], datetime] = _utc_now,
) -> None:
    """Use the driver's clock, not Spark input, to trigger each UTC boundary."""
    # Inspect the latest complete boundary immediately after a restart. A
    # missed boundary must not wait for the next five-minute tick.
    next_to = align_publish_time(clock())
    while True:
        now = clock()
        if not query.isActive:
            raise RuntimeError("VIEW streaming query terminated")
        if now >= next_to:
            # The boundary may be reached before Spark has stored Kafka's
            # currently available records in Redis. Wait for that batch sink.
            query.processAllAvailable()
            if not query.isActive:
                raise RuntimeError("VIEW streaming query terminated")
            to = align_publish_time(clock())
            if to != align_publish_time(now):
                # Old buckets may be skipped once the window advances. Drain
                # again and publish only the latest complete boundary.
                next_to = to
                continue
            publish_boundary(redis, now=to, publication_lock=publication_lock)
            next_to = to + PUBLISH_INTERVAL
            continue
        remaining = (next_to - now).total_seconds()
        if query.awaitTermination(max(1, int(remaining * 1000))):
            raise RuntimeError("VIEW streaming query terminated")


if __name__ == "__main__":
    main()
