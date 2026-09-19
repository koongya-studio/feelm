from __future__ import annotations

from dataclasses import replace
from struct import pack
from types import SimpleNamespace

import pytest
import redis

from feelm_consumer import party_runner
from feelm_consumer.artifacts import ArtifactError
from feelm_consumer.party_candidate_events import PartyCandidateEventPublishError
from feelm_consumer.party_events import PartyRefresh
from feelm_consumer.party_redis_store import StalePartyModelError, StalePartyPartitionFenceError
from feelm_consumer.party_settings import load_party_settings


class FakePartition:
    def __init__(self, topic: str, partition: int) -> None:
        self.topic = topic
        self.partition = partition


class FakeMessage:
    def __init__(
        self,
        partition: int,
        offset: int,
        value: bytes,
        key: bytes = pack(">q", 99),
        timestamp_ms: int = 1_789_725_600_000,
    ) -> None:
        self._partition = partition
        self._offset = offset
        self._value = value
        self._key = key
        self._timestamp_ms = timestamp_ms

    def topic(self) -> str:
        return "feelm.party-refresh.v1"

    def partition(self) -> int:
        return self._partition

    def offset(self) -> int:
        return self._offset

    def value(self) -> bytes:
        return self._value

    def key(self) -> bytes:
        return self._key

    def error(self) -> None:
        return None

    def timestamp(self) -> tuple[int, int]:
        return (1, self._timestamp_ms)


class FakeConsumer:
    def __init__(self, messages: list[FakeMessage | None]) -> None:
        self.messages = iter(messages)
        self.paused: list[FakePartition] = []
        self.committed: list[FakeMessage] = []
        self.closed = False
        self.callbacks: dict[str, object] = {}

    def subscribe(self, _topics: list[str], **callbacks: object) -> None:
        self.callbacks = callbacks
        callbacks["on_assign"](  # type: ignore[operator]
            self,
            [FakePartition("feelm.party-refresh.v1", 0), FakePartition("feelm.party-refresh.v1", 1)],
        )

    def assign(self, _partitions: list[FakePartition]) -> None:
        pass

    def unassign(self) -> None:
        pass

    def poll(self, _timeout: float) -> FakeMessage | None:
        return next(self.messages, None)

    def pause(self, partitions: list[FakePartition]) -> None:
        self.paused.extend(partitions)

    def commit(self, *, message: FakeMessage, asynchronous: bool) -> None:
        assert asynchronous is False
        self.committed.append(message)

    def close(self) -> None:
        self.closed = True


class StoppingConsumer(FakeConsumer):
    def poll(self, _timeout: float) -> FakeMessage | None:
        message = next(self.messages, None)
        if message is None:
            raise SystemExit(0)
        return message


class FailingCommitConsumer(FakeConsumer):
    def commit(self, *, message: FakeMessage, asynchronous: bool) -> None:
        raise FakeKafkaException("commit failed")


class FakeKafkaException(Exception):
    pass


def settings():
    return replace(
        load_party_settings(
            {
                "PARTY_CONSUMER_KAFKA_BOOTSTRAP_SERVERS": "broker",
                "PARTY_CONSUMER_KAFKA_GROUP_ID": "party-group",
                "PARTY_CONSUMER_REDIS_URL": "redis://secret",
                "PARTY_CONSUMER_MODEL_URI": "hdfs:///models/als-v1",
                "PARTY_CONSUMER_MAPPING_URI": "hdfs:///mapping/revision-a",
                "PARTY_CONSUMER_METADATA_URI": "hdfs:///metadata/revision-a",
            }
        ),
        max_paused_polls=2,
    )


def valid_value() -> bytes:
    return (
        b'{"partyId":99,"inputId":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","members":'
        b'[{"userId":10,"vector":[1.0],"candidateMovieIds":[100]}]}'
    )


def wire_run(monkeypatch, fake: FakeConsumer) -> None:
    monkeypatch.setattr(party_runner, "Consumer", lambda _config: fake)
    monkeypatch.setattr(party_runner, "TopicPartition", FakePartition)
    monkeypatch.setattr(party_runner, "load_bundle", lambda *_args: SimpleNamespace(model_version="als-v1"))
    monkeypatch.setattr(party_runner.redis.Redis, "from_url", lambda *_args, **_kwargs: object())
    monkeypatch.setattr(party_runner, "claim_party_partition_fence", lambda *_args: ("fence", "token"))
    monkeypatch.setattr(party_runner.signal, "signal", lambda *_args: None)


def test_new_group_starts_at_latest_before_current_inputs_are_republished(monkeypatch) -> None:
    configs: list[dict[str, object]] = []
    fake = StoppingConsumer([])
    monkeypatch.setattr(
        party_runner,
        "Consumer",
        lambda config: (configs.append(config), fake)[1],
    )
    monkeypatch.setattr(party_runner, "TopicPartition", FakePartition)
    monkeypatch.setattr(party_runner, "load_bundle", lambda *_args: SimpleNamespace(model_version="als-v1"))
    monkeypatch.setattr(party_runner.redis.Redis, "from_url", lambda *_args, **_kwargs: object())
    monkeypatch.setattr(party_runner, "claim_party_partition_fence", lambda *_args: ("fence", "token"))
    monkeypatch.setattr(party_runner.signal, "signal", lambda *_args: None)

    with pytest.raises(SystemExit):
        party_runner.run(settings())

    assert configs[0]["auto.offset.reset"] == "latest"


def test_artifacts_are_validated_before_redis_or_kafka_clients(monkeypatch) -> None:
    clients: list[str] = []
    monkeypatch.setattr(
        party_runner,
        "load_bundle",
        lambda *_args: (_ for _ in ()).throw(ArtifactError("bad revision")),
    )
    monkeypatch.setattr(
        party_runner.redis.Redis,
        "from_url",
        lambda *_args, **_kwargs: clients.append("redis"),
    )
    monkeypatch.setattr(party_runner, "Consumer", lambda _config: clients.append("kafka"))

    with pytest.raises(ArtifactError, match="revision"):
        party_runner.run(settings())

    assert clients == []


@pytest.mark.parametrize("model_version", [" ", "ALS-V1", "x" * 51])
def test_spring_model_version_contract_is_checked_before_external_clients(
    monkeypatch, model_version: str
) -> None:
    clients: list[str] = []
    monkeypatch.setattr(
        party_runner,
        "load_bundle",
        lambda *_args: SimpleNamespace(model_version=model_version),
    )
    monkeypatch.setattr(
        party_runner.redis.Redis,
        "from_url",
        lambda *_args, **_kwargs: clients.append("redis"),
    )
    monkeypatch.setattr(party_runner, "Consumer", lambda _config: clients.append("kafka"))

    with pytest.raises(ValueError, match="Spring reader"):
        party_runner.run(settings())

    assert clients == []


def test_spring_model_version_limit_accepts_exactly_fifty_characters(monkeypatch) -> None:
    fake = StoppingConsumer([])
    wire_run(monkeypatch, fake)
    monkeypatch.setattr(
        party_runner,
        "load_bundle",
        lambda *_args: SimpleNamespace(model_version="x" * 50),
    )

    with pytest.raises(SystemExit):
        party_runner.run(settings())

    assert fake.closed


def test_poison_partition_is_paused_while_other_partition_commits(monkeypatch) -> None:
    fake = StoppingConsumer(
        [
            FakeMessage(0, 10, b"not-json"),
            FakeMessage(1, 11, valid_value()),
            FakeMessage(1, 12, valid_value()),
            FakeMessage(1, 13, valid_value()),
        ]
    )
    wire_run(monkeypatch, fake)
    monkeypatch.setattr(party_runner, "process_party", lambda *_args, **_kwargs: True)

    with pytest.raises(SystemExit):
        party_runner.run(settings())

    assert [message.partition() for message in fake.committed] == [1, 1, 1]
    assert {partition.partition for partition in fake.paused} == {0}
    assert fake.closed


def test_overflowing_vector_is_isolated_as_a_poison_record(monkeypatch) -> None:
    poison = valid_value().replace(b"[1.0]", str(10**400).encode().join((b"[", b"]")))
    fake = StoppingConsumer(
        [FakeMessage(0, 10, poison), FakeMessage(1, 11, valid_value())]
    )
    wire_run(monkeypatch, fake)
    monkeypatch.setattr(party_runner, "process_party", lambda *_args, **_kwargs: True)

    with pytest.raises(SystemExit):
        party_runner.run(settings())

    assert [message.partition() for message in fake.committed] == [1]
    assert {partition.partition for partition in fake.paused} == {0}


def test_commit_failure_pauses_partition_for_replay(monkeypatch) -> None:
    fake = FailingCommitConsumer(
        [FakeMessage(0, 10, valid_value()), FakeMessage(1, 11, valid_value()), None, None]
    )
    wire_run(monkeypatch, fake)
    monkeypatch.setattr(party_runner, "KafkaException", FakeKafkaException)
    monkeypatch.setattr(party_runner, "process_party", lambda *_args, **_kwargs: True)

    with pytest.raises(RuntimeError, match="requires restart"):
        party_runner.run(settings())

    assert fake.committed == []
    assert [partition.partition for partition in fake.paused] == [0, 1]
    assert fake.closed


def test_all_poisoned_partitions_request_bounded_restart(monkeypatch) -> None:
    fake = FakeConsumer(
        [FakeMessage(0, 10, b"not-json"), FakeMessage(1, 11, b"not-json"), None, None]
    )
    wire_run(monkeypatch, fake)

    with pytest.raises(RuntimeError, match="requires restart"):
        party_runner.run(settings())

    assert {partition.partition for partition in fake.paused} == {0, 1}
    assert fake.closed


def test_process_retries_redis_then_succeeds(monkeypatch) -> None:
    calls = 0
    delays: list[float] = []

    def publish(*_args) -> None:
        nonlocal calls
        calls += 1
        if calls == 1:
            raise redis.RedisError("secret")

    monkeypatch.setattr(party_runner, "calculate_party_result", lambda *_args: {"partyId": 99})
    monkeypatch.setattr(party_runner, "publish_party", publish)
    monkeypatch.setattr(party_runner, "sleep", delays.append)

    result = party_runner.process_party(
        object(),
        SimpleNamespace(model_version="als-v1"),
        PartyRefresh(99, "input", ()),
        partition_fence=("fence", "token"),
        attempts=2,
        backoff_seconds=0.25,
    )

    assert result is True
    assert calls == 2
    assert delays == [0.25]


def test_process_publishes_completion_only_after_redis(monkeypatch) -> None:
    calls: list[str] = []
    payload = {"partyId": 99, "modelVersion": "als-v1"}
    monkeypatch.setattr(party_runner, "calculate_party_result", lambda *_args: payload)
    monkeypatch.setattr(party_runner, "publish_party", lambda *_args: calls.append("redis"))

    result = party_runner.process_party(
        object(),
        SimpleNamespace(model_version="als-v1"),
        PartyRefresh(99, "input", ()),
        partition_fence=("fence", "token"),
        candidate_event_publisher=lambda result: calls.append(
            "event" if result is payload else "wrong"
        ),
    )

    assert result is True
    assert calls == ["redis", "event"]


def test_unconfirmed_completion_retries_without_reporting_success(monkeypatch) -> None:
    calls: list[str] = []
    calculations = 0
    generated_timestamps: list[str | None] = []

    def calculate(*_args, generated_at=None):
        nonlocal calculations
        calculations += 1
        generated_timestamps.append(generated_at)
        return {
            "partyId": 99,
            "modelVersion": "als-v1",
            "generatedAt": f"2026-09-18T12:00:0{calculations}Z",
        }

    monkeypatch.setattr(
        party_runner,
        "calculate_party_result",
        calculate,
    )
    monkeypatch.setattr(party_runner, "publish_party", lambda *_args: calls.append("redis"))

    def reject(_result) -> None:
        calls.append("event")
        raise PartyCandidateEventPublishError("not confirmed")

    result = party_runner.process_party(
        object(),
        SimpleNamespace(model_version="als-v1"),
        PartyRefresh(99, "input", ()),
        partition_fence=("fence", "token"),
        attempts=2,
        backoff_seconds=0.0,
        candidate_event_publisher=reject,
        generated_at="2026-09-18T12:00:00.000Z",
    )

    assert result is False
    assert calls == ["redis", "event", "redis", "event"]
    assert calculations == 1
    assert generated_timestamps == ["2026-09-18T12:00:00.000Z"]


def test_source_timestamp_is_stable_across_replay() -> None:
    first = FakeMessage(0, 10, valid_value())
    replay = FakeMessage(0, 10, valid_value())

    assert party_runner._source_generated_at(first) == party_runner._source_generated_at(replay)


@pytest.mark.parametrize(
    ("failure", "expected"),
    [(StalePartyPartitionFenceError("stale"), None), (StalePartyModelError("stale"), False)],
)
def test_process_never_commits_after_fence_rejection(monkeypatch, failure, expected) -> None:
    monkeypatch.setattr(party_runner, "calculate_party_result", lambda *_args: {"partyId": 99})
    monkeypatch.setattr(party_runner, "publish_party", lambda *_args: (_ for _ in ()).throw(failure))

    result = party_runner.process_party(
        object(),
        SimpleNamespace(model_version="als-v1"),
        PartyRefresh(99, "input", ()),
        partition_fence=("fence", "token"),
        attempts=1,
    )

    assert result is expected


def test_ownership_loss_skips_publish(monkeypatch) -> None:
    published: list[object] = []
    monkeypatch.setattr(party_runner, "calculate_party_result", lambda *_args: {"partyId": 99})
    monkeypatch.setattr(party_runner, "publish_party", lambda *_args: published.append(_args))

    result = party_runner.process_party(
        object(),
        object(),
        PartyRefresh(99, "input", ()),
        partition_fence=("fence", "token"),
        is_still_owned=lambda: False,
    )

    assert result is None
    assert published == []
