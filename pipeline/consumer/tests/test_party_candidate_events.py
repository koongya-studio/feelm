from __future__ import annotations

import json

import pytest

from feelm_consumer.party_candidate_events import (
    TOPIC,
    PartyCandidateEventPublishError,
    publish_party_candidate_updated,
)


class FakeProducer:
    def __init__(self, *, delivery_error: object | None = None, remaining: int = 0) -> None:
        self.delivery_error = delivery_error
        self.remaining = remaining
        self.record: tuple[str, bytes, bytes] | None = None
        self.callback = None

    def produce(self, topic, *, key, value, on_delivery) -> None:
        self.record = (topic, key, value)
        self.callback = on_delivery

    def flush(self, _timeout: float) -> int:
        if self.remaining == 0:
            self.callback(self.delivery_error, None)
        return self.remaining


def result(model_version: str = "als-v1") -> dict[str, object]:
    return {
        "partyId": 7,
        "inputId": "sha256:" + "a" * 64,
        "modelVersion": model_version,
        "generatedAt": "2026-09-18T12:00:00Z",
    }


def test_party_notification_contains_only_publication_identity_after_ack() -> None:
    producer = FakeProducer()

    publish_party_candidate_updated(producer, result(), source_partition=2, source_offset=31)

    topic, key, value = producer.record
    assert topic == TOPIC
    assert key == (7).to_bytes(8, byteorder="big", signed=True)
    assert json.loads(value) == {
        "partyId": 7,
        "inputId": "sha256:" + "a" * 64,
        "modelVersion": "als-v1",
        "generatedAt": "2026-09-18T12:00:00Z",
        "sourcePartition": 2,
        "sourceOffset": 31,
    }


@pytest.mark.parametrize("producer", [FakeProducer(delivery_error=object()), FakeProducer(remaining=1)])
def test_unconfirmed_party_notification_fails(producer: FakeProducer) -> None:
    with pytest.raises(PartyCandidateEventPublishError, match="not confirmed"):
        publish_party_candidate_updated(producer, result(), source_partition=0, source_offset=1)


@pytest.mark.parametrize("model_version", [" ", "ALS-V1", "x" * 51])
def test_party_notification_rejects_model_version_outside_db_contract(
    model_version: str,
) -> None:
    with pytest.raises(ValueError, match="DB contract"):
        publish_party_candidate_updated(
            FakeProducer(), result(model_version), source_partition=0, source_offset=1
        )


def test_full_producer_queue_fails_without_exposing_event_body() -> None:
    class FullProducer(FakeProducer):
        def produce(self, topic, *, key, value, on_delivery) -> None:
            raise BufferError("queue full")

    with pytest.raises(PartyCandidateEventPublishError, match="delivery failed") as error:
        publish_party_candidate_updated(
            FullProducer(), result(), source_partition=0, source_offset=1
        )
    assert "inputId" not in str(error.value)
