from __future__ import annotations

import json

import pytest

from feelm_consumer.candidate_events import (
    TOPIC,
    CandidateEventPublishError,
    publish_candidate_updated,
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


def test_candidate_notification_contains_only_user_id_after_ack() -> None:
    producer = FakeProducer()
    publish_candidate_updated(producer, 7)

    topic, key, value = producer.record
    assert topic == TOPIC
    assert key == b"7"
    assert json.loads(value) == {"userId": 7}


@pytest.mark.parametrize("producer", [FakeProducer(delivery_error=object()), FakeProducer(remaining=1)])
def test_unconfirmed_candidate_notification_fails(producer: FakeProducer) -> None:
    with pytest.raises(CandidateEventPublishError, match="not confirmed"):
        publish_candidate_updated(producer, 7)


def test_full_producer_queue_fails_without_exposing_event_body() -> None:
    class FullProducer(FakeProducer):
        def produce(self, topic, *, key, value, on_delivery) -> None:
            raise BufferError("queue full")

    with pytest.raises(CandidateEventPublishError, match="delivery failed") as error:
        publish_candidate_updated(FullProducer(), 7)
    assert "userId" not in str(error.value)
