"""Kafka-to-Redis adapter for party candidate refresh events.

The Kafka connector is supplied by the Spark deployment.  This module keeps the
connector and Redis writer at the edges: message validation and score computation
remain testable without a broker, Redis, or Spark cluster.
"""

from __future__ import annotations

import json
from collections.abc import Callable, Mapping, Sequence

from streaming.party.processor import parse_party_refresh, process_party_refresh
from streaming.party.redis_store import RedisStringWriter

KafkaMessageHandler = Callable[[str], None]


def party_refresh_handler(
    model_version: str,
    service_to_model_movie_ids: Mapping[int, int],
    model_item_factors: Mapping[int, Sequence[float]],
    redis: RedisStringWriter,
) -> KafkaMessageHandler:
    """Build the per-message handler used by a Spark micro-batch.

    The Kafka envelope may contain a ``payload`` field (Spring/Consumer event)
    or be the refresh payload itself, which keeps local replay fixtures small.
    Invalid messages fail their micro-batch instead of silently publishing an
    unrelated or partial party snapshot.
    """

    def handle(value: str) -> None:
        try:
            decoded = json.loads(value)
        except json.JSONDecodeError as error:
            raise ValueError("party refresh Kafka value must be JSON") from error
        if not isinstance(decoded, Mapping):
            raise ValueError("party refresh Kafka value must be an object")
        payload = decoded.get("payload", decoded)
        if not isinstance(payload, Mapping):
            raise ValueError("party refresh event payload must be an object")
        refresh = parse_party_refresh(payload)
        process_party_refresh(
            refresh,
            service_to_model_movie_ids,
            model_item_factors,
            redis,
            model_version=model_version,
        )

    return handle


def start_party_refresh_stream(
    spark: object,
    *,
    kafka_bootstrap_servers: str,
    kafka_topic: str,
    checkpoint_uri: str,
    handle_message: KafkaMessageHandler,
    max_records_per_trigger: int = 100,
) -> object:
    """Start an at-least-once Kafka stream that only commits after Redis writes.

    ``foreachBatch`` is intentionally used because each party refresh is a
    bounded request (at most four vectors and 2,400 candidate IDs).  A failed
    message raises from the batch, leaves the Kafka offset uncommitted, and lets
    Spark retry from its checkpoint; Redis publication is one atomic ``SET``.
    """
    _required(kafka_bootstrap_servers, "kafka_bootstrap_servers")
    _required(kafka_topic, "kafka_topic")
    _required(checkpoint_uri, "checkpoint_uri")
    if max_records_per_trigger <= 0:
        raise ValueError("max_records_per_trigger must be positive")
    source = (
        spark.readStream.format("kafka")
        .option("kafka.bootstrap.servers", kafka_bootstrap_servers)
        .option("subscribe", kafka_topic)
        .option("startingOffsets", "earliest")
        .option("maxOffsetsPerTrigger", max_records_per_trigger)
        .load()
        .selectExpr("CAST(value AS STRING) AS value")
    )

    def process_batch(batch: object, _: int) -> None:
        for row in batch.toLocalIterator():
            handle_message(row["value"])

    return (
        source.writeStream.option("checkpointLocation", checkpoint_uri)
        .foreachBatch(process_batch)
        .start()
    )


def _required(value: str, name: str) -> None:
    if not value:
        raise ValueError(f"{name} is required")
