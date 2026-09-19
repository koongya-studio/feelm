"""Publish an acknowledged notification after a party Redis snapshot is complete."""

from __future__ import annotations

import json
import re
from collections.abc import Mapping
from datetime import UTC, datetime

from confluent_kafka import KafkaException, Producer

from feelm_consumer.party_events import INPUT_ID_PATTERN

TOPIC = "feelm.party-candidate-updated.v1"
DELIVERY_TIMEOUT_SECONDS = 10.0
MAX_MODEL_VERSION_LENGTH = 50
MODEL_VERSION_PATTERN = re.compile(r"[a-z0-9][a-z0-9._-]{0,49}\Z")


class PartyCandidateEventPublishError(RuntimeError):
    """The party publication notification was not confirmed by Kafka."""


def publish_party_candidate_updated(
    producer: Producer,
    result: Mapping[str, object],
    *,
    source_partition: int,
    source_offset: int,
    topic: str = TOPIC,
) -> None:
    party_id = result.get("partyId")
    input_id = result.get("inputId")
    model_version = result.get("modelVersion")
    generated_at = result.get("generatedAt")
    if isinstance(party_id, bool) or not isinstance(party_id, int) or party_id <= 0:
        raise ValueError("party candidate notification requires a positive party ID")
    if party_id > 2**63 - 1:
        raise ValueError("party ID exceeds the Kafka Long key contract")
    if not isinstance(input_id, str) or not INPUT_ID_PATTERN.fullmatch(input_id):
        raise ValueError("party candidate notification requires an input digest")
    if (
        not isinstance(model_version, str)
        or not MODEL_VERSION_PATTERN.fullmatch(model_version)
    ):
        raise ValueError("party candidate notification model version exceeds the DB contract")
    if not isinstance(generated_at, str) or not _is_utc_timestamp(generated_at):
        raise ValueError("party candidate notification requires a UTC generation timestamp")
    if source_partition < 0 or source_offset < 0:
        raise ValueError("party candidate notification source position must be non-negative")

    body = {
        "partyId": party_id,
        "inputId": input_id,
        "modelVersion": model_version,
        "generatedAt": generated_at,
        "sourcePartition": source_partition,
        "sourceOffset": source_offset,
    }
    deliveries: list[object | None] = []
    try:
        producer.produce(
            topic,
            key=party_id.to_bytes(8, byteorder="big", signed=True),
            value=json.dumps(body, separators=(",", ":"), ensure_ascii=False).encode("utf-8"),
            on_delivery=lambda error, _message: deliveries.append(error),
        )
        remaining = producer.flush(DELIVERY_TIMEOUT_SECONDS)
    except (BufferError, KafkaException) as error:
        raise PartyCandidateEventPublishError(
            "party candidate notification Kafka delivery failed"
        ) from error
    if remaining or deliveries != [None]:
        raise PartyCandidateEventPublishError(
            "party candidate notification Kafka delivery was not confirmed"
        )


def _is_utc_timestamp(value: str) -> bool:
    if not value.endswith("Z"):
        return False
    try:
        parsed = datetime.fromisoformat(value.removesuffix("Z") + "+00:00")
    except ValueError:
        return False
    return parsed.tzinfo == UTC
