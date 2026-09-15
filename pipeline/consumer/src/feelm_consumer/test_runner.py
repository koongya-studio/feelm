from __future__ import annotations

from dataclasses import replace

import pytest
import redis

from feelm_consumer import runner
from feelm_consumer.settings import load_settings


class FakePartition:
    def __init__(self, topic: str, partition: int) -> None:
        self.topic = topic
        self.partition = partition


class FakeMessage:
    def __init__(self, partition: int, offset: int, value: bytes, key: bytes = b"7") -> None:
        self._partition = partition
        self._offset = offset
        self._value = value
        self._key = key

    def topic(self) -> str:
        return "feelm.rating.v1"

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


class FakeConsumer:
    def __init__(self, messages: list[FakeMessage | None]) -> None:
        self.messages = iter(messages)
        self.assigned: list[FakePartition] = []
        self.paused: list[FakePartition] = []
        self.committed: list[FakeMessage] = []
        self.unassigned = False
        self.closed = False
        self.callbacks: dict[str, object] = {}

    def subscribe(self, _topics: list[str], **callbacks: object) -> None:
        self.callbacks = callbacks
        on_assign = callbacks["on_assign"]
        on_assign(  # type: ignore[operator]
            self,
            [FakePartition("feelm.rating.v1", 0), FakePartition("feelm.rating.v1", 1)],
        )

    def assign(self, partitions: list[FakePartition]) -> None:
        self.assigned = partitions

    def unassign(self) -> None:
        self.unassigned = True

    def poll(self, _timeout: float) -> FakeMessage | None:
        return next(self.messages, None)

    def pause(self, partitions: list[FakePartition]) -> None:
        self.paused.extend(partitions)

    def commit(self, *, message: FakeMessage, asynchronous: bool) -> None:
        assert asynchronous is False
        self.committed.append(message)

    def close(self) -> None:
        self.closed = True


def _settings():
    return replace(
        load_settings(
            {
                "CONSUMER_KAFKA_BOOTSTRAP_SERVERS": "broker",
                "CONSUMER_KAFKA_TOPIC": "feelm.rating.v1",
                "CONSUMER_KAFKA_GROUP_ID": "group",
                "CONSUMER_POSTGRES_DSN": "postgresql://secret",
                "CONSUMER_REDIS_URL": "redis://secret",
                "CONSUMER_MODEL_URI": "model",
                "CONSUMER_MAPPING_URI": "mapping",
                "CONSUMER_METADATA_URI": "metadata",
            }
        ),
        max_paused_polls=2,
    )


def test_malformed_partition_is_not_committed_and_healthy_partition_continues(monkeypatch) -> None:
    malformed = FakeMessage(0, 10, b"not-json")
    healthy = FakeMessage(
        1,
        11,
        b'{"eventId":"123e4567-e89b-12d3-a456-426614174000","userId":7,"eventType":"RATING",'
        b'"occurredAt":"2026-09-13T00:00:00Z","payload":{"action":"CREATE","movieId":1,"score":5}}',
    )
    fake = FakeConsumer([malformed, healthy, None, None])
    monkeypatch.setattr(runner, "Consumer", lambda _config: fake)
    monkeypatch.setattr(runner, "TopicPartition", FakePartition)
    monkeypatch.setattr(runner, "load_bundle", lambda *_args: object())
    monkeypatch.setattr(runner.redis.Redis, "from_url", lambda *_args, **_kwargs: object())
    monkeypatch.setattr(runner, "claim_partition_fence", lambda *_args: ("fence", 1))
    monkeypatch.setattr(runner, "process_user", lambda *_args, **_kwargs: True)
    monkeypatch.setattr(runner.signal, "signal", lambda *_args: None)

    with pytest.raises(RuntimeError, match="requires restart"):
        runner.run(_settings())

    assert [message.partition() for message in fake.committed] == [1]
    assert [(partition.topic, partition.partition) for partition in fake.paused] == [("feelm.rating.v1", 0)]
    assert fake.closed
    assert fake.assigned[0].partition == 0

    on_revoke = fake.callbacks["on_revoke"]
    on_revoke(fake, [FakePartition("feelm.rating.v1", 0)])  # type: ignore[operator]
    assert fake.unassigned


def test_invalid_utf8_key_is_partition_scoped_and_not_committed(monkeypatch) -> None:
    malformed = FakeMessage(0, 10, b'{"eventId":"bad"}', key=b"\xff")
    healthy = FakeMessage(
        1,
        11,
        b'{"eventId":"123e4567-e89b-12d3-a456-426614174000","userId":7,"eventType":"RATING",'
        b'"occurredAt":"2026-09-13T00:00:00Z","payload":{"action":"CREATE","movieId":1,"score":5}}',
    )
    fake = FakeConsumer([malformed, healthy, None])
    monkeypatch.setattr(runner, "Consumer", lambda _config: fake)
    monkeypatch.setattr(runner, "TopicPartition", FakePartition)
    monkeypatch.setattr(runner, "load_bundle", lambda *_args: object())
    monkeypatch.setattr(runner.redis.Redis, "from_url", lambda *_args, **_kwargs: object())
    monkeypatch.setattr(runner, "claim_partition_fence", lambda *_args: ("fence", 1))
    monkeypatch.setattr(runner, "process_user", lambda *_args, **_kwargs: True)
    monkeypatch.setattr(runner.signal, "signal", lambda *_args: None)

    with pytest.raises(RuntimeError, match="requires restart"):
        runner.run(_settings())

    assert [message.partition() for message in fake.committed] == [1]
    assert [partition.partition for partition in fake.paused] == [0]
    assert fake.closed


def test_dependency_failure_has_bounded_backoff_without_secret_logging(monkeypatch, caplog) -> None:
    delays: list[float] = []
    monkeypatch.setattr(
        runner, "current_version", lambda *_args: (_ for _ in ()).throw(redis.RedisError("redis://secret"))
    )
    monkeypatch.setattr(runner, "sleep", delays.append)

    assert not runner.process_user(object(), lambda: object(), object(), 7, attempts=3, backoff_seconds=0.25)
    assert delays == [0.25, 0.5]
    assert "redis://secret" not in caplog.text


def test_signal_shutdown_closes_consumer_without_processing(monkeypatch) -> None:
    fake = FakeConsumer([])
    handlers: dict[object, object] = {}
    monkeypatch.setattr(runner, "Consumer", lambda _config: fake)
    monkeypatch.setattr(runner, "TopicPartition", FakePartition)
    monkeypatch.setattr(runner, "load_bundle", lambda *_args: object())
    monkeypatch.setattr(runner.redis.Redis, "from_url", lambda *_args, **_kwargs: object())
    monkeypatch.setattr(runner, "claim_partition_fence", lambda *_args: ("fence", 1))
    monkeypatch.setattr(
        runner.signal,
        "signal",
        lambda signal_number, handler: handlers.__setitem__(signal_number, handler),
    )

    def stop_on_first_poll(_timeout: float) -> None:
        handlers[runner.signal.SIGTERM](runner.signal.SIGTERM, object())  # type: ignore[operator]
        return None

    fake.poll = stop_on_first_poll  # type: ignore[method-assign]
    runner.run(_settings())

    assert fake.closed
    assert fake.committed == []


def test_stale_partition_fence_does_not_pause_or_commit(monkeypatch) -> None:
    message = FakeMessage(
        0,
        10,
        b'{"eventId":"123e4567-e89b-12d3-a456-426614174000","userId":7,"eventType":"RATING",'
        b'"occurredAt":"2026-09-13T00:00:00Z","payload":{"action":"CREATE","movieId":1,"score":5}}',
    )
    fake = FakeConsumer([message])
    monkeypatch.setattr(runner, "Consumer", lambda _config: fake)
    monkeypatch.setattr(runner, "TopicPartition", FakePartition)
    monkeypatch.setattr(runner, "load_bundle", lambda *_args: object())
    monkeypatch.setattr(runner.redis.Redis, "from_url", lambda *_args, **_kwargs: object())
    monkeypatch.setattr(runner, "claim_partition_fence", lambda *_args: ("fence", 2))
    monkeypatch.setattr(runner, "process_user", lambda *_args, **_kwargs: None)
    handlers: dict[object, object] = {}
    monkeypatch.setattr(
        runner.signal,
        "signal",
        lambda signal_number, handler: handlers.__setitem__(signal_number, handler),
    )

    fake.poll = lambda _timeout: next(fake.messages, None)  # type: ignore[method-assign]
    original_poll = fake.poll

    def poll_once_then_stop(timeout: float):
        result = original_poll(timeout)
        if result is None:
            handlers[runner.signal.SIGTERM](runner.signal.SIGTERM, object())  # type: ignore[operator]
        return result

    fake.poll = poll_once_then_stop  # type: ignore[method-assign]
    runner.run(_settings())

    assert fake.committed == []
    assert fake.paused == []


def test_assign_claim_failure_after_partial_success_fails_closed_and_restarts(monkeypatch, caplog) -> None:
    fake = FakeConsumer([])
    calls: list[int] = []

    def claim(_store, _group, _topic, partition):
        calls.append(partition)
        if partition == 1:
            raise redis.RedisError("temporary Redis failure")
        return (f"fence:{partition}", f"token:{partition}")

    monkeypatch.setattr(runner, "Consumer", lambda _config: fake)
    monkeypatch.setattr(runner, "TopicPartition", FakePartition)
    monkeypatch.setattr(runner, "load_bundle", lambda *_args: object())
    monkeypatch.setattr(runner.redis.Redis, "from_url", lambda *_args, **_kwargs: object())
    monkeypatch.setattr(runner, "claim_partition_fence", claim)
    monkeypatch.setattr(runner.signal, "signal", lambda *_args: None)
    with pytest.raises(RuntimeError, match="claim failed; Consumer restart required"):
        runner.run(_settings())
    assert calls == [0, 1]
    assert fake.assigned == []
    assert fake.committed == []
    assert fake.closed
    assert "RedisError" in caplog.text
