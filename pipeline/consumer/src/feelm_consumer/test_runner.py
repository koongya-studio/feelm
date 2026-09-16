from __future__ import annotations

from dataclasses import replace

import numpy as np
import pytest
import redis

from feelm_consumer import runner
from feelm_consumer.artifacts import ArtifactError
from feelm_consumer.candidate_events import CandidateEventPublishError
from feelm_consumer.postgres import UserSnapshot
from feelm_consumer.ranking import RankedMovie
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


class _Bundle:
    mapping_revision = "sha256:" + "a" * 64
    movie_metadata_revision = "sha256:" + "b" * 64
    input_snapshot = "snapshot-a"
    movie_metadata = {10: object(), 20: object()}


class _NullSnapshot:
    def __enter__(self) -> object:
        return object()

    def __exit__(self, *_exc: object) -> bool:
        return False


def _null_snapshot(_connect: object) -> _NullSnapshot:
    return _NullSnapshot()


class _Supported:
    size = 2

    @staticmethod
    def sum() -> int:
        return 1


class _Content:
    content_version = "content-v1"
    movie_count = 2
    vocabulary_size = 4
    supported = _Supported()


CONTENT = _Content()


class _Groups:
    group_version = "groups-v1"
    mapping_revision = _Bundle.mapping_revision
    service_movie_ids = np.asarray([10, 20], dtype=np.int64)
    tmdb_ids = np.asarray([100, 200], dtype=np.int64)
    group_ids = np.asarray([0, 1], dtype=np.int32)
    group_mapped = np.asarray([True, False])
    group_means = np.zeros((2, 3), dtype=np.float64)


GROUPS = _Groups()


class _Popularity:
    popularity_version = "popularity-v1"
    mapping_revision = _Bundle.mapping_revision
    candidates = (RankedMovie(20, 0.9),)
    service_movie_ids = np.asarray([20, 10], dtype=np.int64)
    tmdb_ids = np.asarray([200, 100], dtype=np.int64)


POPULARITY = _Popularity()


class StoppingConsumer(FakeConsumer):
    """Leave the poll loop once the scripted messages are consumed."""

    def poll(self, _timeout: float) -> FakeMessage:
        message = next(self.messages, None)
        if message is None:
            raise SystemExit(0)
        return message


def _wire_run(monkeypatch, fake) -> None:
    monkeypatch.setattr(runner, "Consumer", lambda _config: fake)
    monkeypatch.setattr(runner, "TopicPartition", FakePartition)
    monkeypatch.setattr(runner.redis.Redis, "from_url", lambda *_args, **_kwargs: object())
    monkeypatch.setattr(runner, "claim_partition_fence", lambda *_args: ("fence", 1))
    monkeypatch.setattr(runner.signal, "signal", lambda *_args: None)


def test_content_bundle_is_loaded_with_the_model_generation_and_passed_down(monkeypatch) -> None:
    """The generation arguments must be the model's own, not placeholders."""
    bundle = _Bundle()
    seen: dict[str, object] = {}
    fake = StoppingConsumer([])
    _wire_run(monkeypatch, fake)
    monkeypatch.setattr(runner, "load_bundle", lambda *_args: bundle)
    def fake_load_content(uri, **kwargs):
        seen.update({"uri": uri, **kwargs})
        return CONTENT

    monkeypatch.setattr(runner, "load_content", fake_load_content)
    monkeypatch.setattr(
        runner,
        "process_user",
        lambda *_args, **kwargs: seen.update({"process": kwargs}) or True,
    )
    settings = replace(_settings(), content_uri="content/v1", hybrid_alpha=0.25)

    with pytest.raises(SystemExit):
        runner.run(settings)

    assert seen["uri"] == "content/v1"
    assert seen["mapping_revision"] == bundle.mapping_revision
    assert seen["movie_metadata_revision"] == bundle.movie_metadata_revision
    assert seen["input_snapshot"] == bundle.input_snapshot
    assert seen["service_movie_ids"] == set(bundle.movie_metadata)


def test_content_is_not_loaded_when_it_is_not_configured(monkeypatch) -> None:
    calls: list[str] = []
    fake = StoppingConsumer([])
    _wire_run(monkeypatch, fake)
    monkeypatch.setattr(runner, "load_bundle", lambda *_args: _Bundle())
    monkeypatch.setattr(runner, "load_content", lambda *_args, **_kwargs: calls.append("loaded"))
    monkeypatch.setattr(runner, "process_user", lambda *_args, **_kwargs: True)

    with pytest.raises(SystemExit):
        runner.run(_settings())

    assert calls == []


def test_content_and_alpha_reach_process_user(monkeypatch) -> None:
    seen: dict[str, object] = {}
    healthy = FakeMessage(
        0,
        10,
        b'{"eventId":"123e4567-e89b-12d3-a456-426614174000","userId":7,"eventType":"RATING",'
        b'"occurredAt":"2026-09-13T00:00:00Z","payload":{"action":"CREATE","movieId":1,"score":5}}',
    )
    fake = StoppingConsumer([healthy])
    _wire_run(monkeypatch, fake)
    monkeypatch.setattr(runner, "load_bundle", lambda *_args: _Bundle())
    monkeypatch.setattr(runner, "load_content", lambda *_args, **_kwargs: CONTENT)
    monkeypatch.setattr(
        runner, "process_user", lambda *_args, **kwargs: seen.update(kwargs) or True
    )

    with pytest.raises(SystemExit):
        runner.run(replace(_settings(), content_uri="content/v1", hybrid_alpha=0.25))

    assert seen["content"] is CONTENT
    assert seen["hybrid_alpha"] == 0.25


def test_group_bundle_is_generation_checked_and_passed_down(monkeypatch) -> None:
    seen: dict[str, object] = {}
    healthy = FakeMessage(
        0,
        10,
        b'{"eventId":"123e4567-e89b-12d3-a456-426614174000","userId":7,"eventType":"RATING",'
        b'"occurredAt":"2026-09-13T00:00:00Z","payload":{"action":"CREATE","movieId":1,"score":5}}',
    )
    fake = StoppingConsumer([healthy])
    _wire_run(monkeypatch, fake)
    monkeypatch.setattr(runner, "load_bundle", lambda *_args: _Bundle())
    monkeypatch.setattr(
        runner,
        "load_group_bundle",
        lambda uri, **kwargs: seen.update({"uri": uri, **kwargs}) or GROUPS,
    )
    monkeypatch.setattr(
        runner,
        "load_discovery_popularity",
        lambda uri, **kwargs: seen.update({"popularity_uri": uri, "popularity_kwargs": kwargs}) or POPULARITY,
    )
    monkeypatch.setattr(runner, "process_user", lambda *_args, **kwargs: seen.update({"process": kwargs}) or True)

    with pytest.raises(SystemExit):
        runner.run(
            replace(
                _settings(),
                group_uri="groups/groups-v1",
                discovery_popularity_uri="discovery-popularity/v1",
            )
        )

    assert seen["uri"] == "groups/groups-v1"
    assert seen["expected_mapping_revision"] == _Bundle.mapping_revision
    assert seen["popularity_uri"] == "discovery-popularity/v1"
    assert seen["popularity_kwargs"] == {"expected_mapping_revision": _Bundle.mapping_revision}
    assert seen["process"]["groups"] is GROUPS  # type: ignore[index]
    assert seen["process"]["discovery_popularity"] == POPULARITY.candidates  # type: ignore[index]


def test_mismatched_discovery_catalog_stops_before_external_clients(monkeypatch) -> None:
    class MismatchedPopularity(_Popularity):
        tmdb_ids = np.asarray([999, 100], dtype=np.int64)

    monkeypatch.setattr(runner, "load_bundle", lambda *_args: _Bundle())
    monkeypatch.setattr(runner, "load_group_bundle", lambda *_args, **_kwargs: GROUPS)
    monkeypatch.setattr(
        runner,
        "load_discovery_popularity",
        lambda *_args, **_kwargs: MismatchedPopularity(),
    )
    monkeypatch.setattr(
        runner.redis.Redis,
        "from_url",
        lambda *_args, **_kwargs: pytest.fail("Redis must not be created for a mismatched release"),
    )
    monkeypatch.setattr(
        runner,
        "Consumer",
        lambda _config: pytest.fail("Kafka must not be created for a mismatched release"),
    )

    with pytest.raises(ArtifactError, match="catalogs differ"):
        runner.run(
            replace(
                _settings(),
                group_uri="groups/groups-v1",
                discovery_popularity_uri="discovery-popularity/v1",
            )
        )


def test_user_without_publishable_result_is_committed_and_does_not_pause(monkeypatch, caplog) -> None:
    """An empty candidate population is not a failure worth replaying forever."""
    published: list[object] = []
    calculated: dict[str, object] = {}
    monkeypatch.setattr(runner, "_expected_version", lambda *_args: 4)
    monkeypatch.setattr(runner, "readonly_snapshot", _null_snapshot)
    monkeypatch.setattr(
        runner,
        "load_user_snapshot",
        lambda *_args: UserSnapshot([], [], frozenset({8}), frozenset({9})),
    )
    monkeypatch.setattr(
        runner,
        "calculate_user_results",
        lambda *_args, **kwargs: calculated.update(kwargs) or None,
    )
    monkeypatch.setattr(runner, "publish", lambda *_args: published.append(_args))

    with caplog.at_level("WARNING"):
        processed = runner.process_user(
            object(), lambda: object(), object(), 7, partition_fence=("fence", "token")
        )

    assert processed is True
    assert published == []
    assert calculated["watched_movie_ids"] == frozenset({8})
    assert calculated["dismissed_movie_ids"] == frozenset({9})
    assert "no_publishable_result_skipped" in caplog.text


def test_candidate_event_follows_redis_publish_and_failure_keeps_input_uncommitted(monkeypatch) -> None:
    calls: list[str] = []
    monkeypatch.setattr(runner, "_expected_version", lambda *_args: 0)
    monkeypatch.setattr(runner, "readonly_snapshot", _null_snapshot)
    monkeypatch.setattr(runner, "load_user_snapshot", lambda *_args: UserSnapshot([], [], frozenset(), frozenset()))
    monkeypatch.setattr(
        runner,
        "calculate_user_results",
        lambda *_args, **_kwargs: (
            {"modelVersion": "als-v1", "generationId": "gen-1"},
            {"modelVersion": "als-v1", "generationId": "gen-1"},
        ),
    )
    monkeypatch.setattr(runner, "publish", lambda *_args: calls.append("redis"))

    def fail_event(user_id: int) -> None:
        assert user_id == 7
        calls.append("kafka")
        raise CandidateEventPublishError("not acknowledged")

    assert not runner.process_user(
        object(), lambda: object(), object(), 7,
        attempts=1, partition_fence=("fence", "token"), candidate_event_publisher=fail_event,
    )
    assert calls == ["redis", "kafka"]


def test_redis_publish_failure_does_not_emit_candidate_event(monkeypatch) -> None:
    emitted: list[object] = []
    monkeypatch.setattr(runner, "_expected_version", lambda *_args: 0)
    monkeypatch.setattr(runner, "readonly_snapshot", _null_snapshot)
    monkeypatch.setattr(runner, "load_user_snapshot", lambda *_args: UserSnapshot([], [], frozenset(), frozenset()))
    monkeypatch.setattr(
        runner,
        "calculate_user_results",
        lambda *_args, **_kwargs: ({"generationId": "gen-1"}, {"generationId": "gen-1"}),
    )
    monkeypatch.setattr(runner, "publish", lambda *_args: (_ for _ in ()).throw(redis.RedisError("write failed")))

    assert not runner.process_user(
        object(), lambda: object(), object(), 7,
        attempts=1, partition_fence=("fence", "token"),
        candidate_event_publisher=lambda *_args: emitted.append(_args),
    )
    assert emitted == []


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
