from __future__ import annotations

import json

import pytest

from streaming.party.kafka_stream import party_refresh_handler, start_party_refresh_stream
from streaming.party.redis_store import party_candidates_key


class RecordingRedis:
    def __init__(self) -> None:
        self.values: dict[str, str] = {}

    def set(self, name: str, value: str) -> None:
        self.values[name] = value


class FakeWriteStream:
    def __init__(self) -> None:
        self.checkpoint = None
        self.callback = None

    def option(self, name, value):
        assert name == "checkpointLocation"
        self.checkpoint = value
        return self

    def foreachBatch(self, callback):
        self.callback = callback
        return self

    def start(self):
        return "query"


class FakeFrame:
    def __init__(self) -> None:
        self.writeStream = FakeWriteStream()
        self.expression = None

    def selectExpr(self, expression):
        self.expression = expression
        return self


class FakeKafkaReader:
    def __init__(self, frame) -> None:
        self.frame = frame
        self.format_name = None
        self.options = {}

    def format(self, name):
        self.format_name = name
        return self

    def option(self, name, value):
        self.options[name] = value
        return self

    def load(self):
        return self.frame


class FakeSpark:
    def __init__(self) -> None:
        self.frame = FakeFrame()
        self.readStream = FakeKafkaReader(self.frame)


def refresh_message() -> str:
    return json.dumps(
        {
            "eventType": "PARTY_REFRESH",
            "payload": {
                "partyId": 99,
                "inputId": "snapshot-99-a",
                "members": [
                    {
                        "userId": 10,
                        "vector": [1.0],
                        "candidateMovieIds": [100],
                    }
                ],
            },
        }
    )


def test_handler_accepts_kafka_envelope_and_publishes_party_snapshot():
    redis = RecordingRedis()
    handler = party_refresh_handler("als-v1", {100: 1}, {1: (1.0,)}, redis)

    handler(refresh_message())

    assert party_candidates_key(99) in redis.values
    payload = json.loads(redis.values[party_candidates_key(99)])
    assert payload["inputId"] == "snapshot-99-a"
    assert payload["modelVersion"] == "als-v1"


@pytest.mark.parametrize("message", ["not-json", "[]", '{"payload": []}'])
def test_handler_rejects_invalid_kafka_values(message):
    handler = party_refresh_handler("als-v1", {}, {}, RecordingRedis())

    with pytest.raises(ValueError):
        handler(message)


def test_stream_configures_kafka_and_checkpoints_before_starting():
    spark = FakeSpark()
    handled = []

    query = start_party_refresh_stream(
        spark,
        kafka_bootstrap_servers="localhost:9092",
        kafka_topic="feelm.party-refresh.v1",
        checkpoint_uri="file:///tmp/checkpoint",
        handle_message=handled.append,
    )

    assert query == "query"
    assert spark.readStream.format_name == "kafka"
    assert spark.readStream.options == {
        "kafka.bootstrap.servers": "localhost:9092",
        "subscribe": "feelm.party-refresh.v1",
        "startingOffsets": "earliest",
        "maxOffsetsPerTrigger": 100,
    }
    assert spark.frame.expression == "CAST(value AS STRING) AS value"
    assert spark.frame.writeStream.checkpoint == "file:///tmp/checkpoint"


@pytest.mark.parametrize("field", ["kafka_bootstrap_servers", "kafka_topic", "checkpoint_uri"])
def test_stream_requires_all_runtime_boundaries(field):
    arguments = {
        "kafka_bootstrap_servers": "localhost:9092",
        "kafka_topic": "feelm.party-refresh.v1",
        "checkpoint_uri": "file:///tmp/checkpoint",
        "handle_message": lambda _: None,
    }
    arguments[field] = ""

    with pytest.raises(ValueError, match=field):
        start_party_refresh_stream(FakeSpark(), **arguments)


def test_stream_requires_positive_batch_limit():
    with pytest.raises(ValueError, match="max_records_per_trigger"):
        start_party_refresh_stream(
            FakeSpark(),
            kafka_bootstrap_servers="localhost:9092",
            kafka_topic="feelm.party-refresh.v1",
            checkpoint_uri="file:///tmp/checkpoint",
            handle_message=lambda _: None,
            max_records_per_trigger=0,
        )
