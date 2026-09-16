"""Publish a small notification after a user's Redis candidates are committed."""

from __future__ import annotations

import json

from confluent_kafka import KafkaException, Producer

TOPIC = "feelm.candidate-updated.v1"
DELIVERY_TIMEOUT_SECONDS = 10.0


class CandidateEventPublishError(RuntimeError):
    """The candidate update was not confirmed by Kafka."""


def publish_candidate_updated(
    producer: Producer,
    user_id: int,
) -> None:
    """Wait for broker acknowledgement before the source offset can be committed.

    Delivery is at least once: a crash after Kafka acknowledgement and before
    the source offset commit may emit another notification on replay. Readers
    must read the current Redis snapshot and compare its generation with the
    last generation processed for each party member.
    """
    if isinstance(user_id, bool) or not isinstance(user_id, int) or user_id <= 0:
        raise ValueError("candidate update requires a positive user ID")
    deliveries: list[object | None] = []
    try:
        producer.produce(
            TOPIC,
            key=str(user_id).encode("utf-8"),
            value=json.dumps({"userId": user_id}, separators=(",", ":")).encode("utf-8"),
            on_delivery=lambda error, _message: deliveries.append(error),
        )
        remaining = producer.flush(DELIVERY_TIMEOUT_SECONDS)
    except (BufferError, KafkaException) as error:
        raise CandidateEventPublishError("candidate update Kafka delivery failed") from error
    if remaining or deliveries != [None]:
        raise CandidateEventPublishError("candidate update Kafka delivery was not confirmed")
