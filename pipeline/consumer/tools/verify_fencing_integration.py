"""Isolated real-service check for S15P21E106-401.

This script creates a Kafka topic and writes PostgreSQL/Redis fixture data. Run it
only against disposable, network-isolated services; never point it at production.
"""

from __future__ import annotations

import json
import os
import secrets
import sys
import threading
import time
from datetime import UTC, datetime
from pathlib import Path
from urllib.parse import urlsplit
from uuid import uuid4

import psycopg
import redis
from confluent_kafka import Consumer, KafkaException, Producer, TopicPartition
from confluent_kafka.admin import AdminClient, NewTopic

from feelm_consumer import runner
from feelm_consumer.redis_store import (
    StalePartitionFenceError,
    claim_partition_fence,
    current_version,
    publish,
    user_keys,
)


def require_isolation() -> tuple[str, str, str, int]:
    if os.environ.get("FEELM_401_ISOLATED") != "YES":
        raise RuntimeError("FEELM_401_ISOLATED=YES is required for disposable services")
    required = ("FEELM_401_KAFKA", "FEELM_401_REDIS_URL", "FEELM_401_POSTGRES_DSN")
    if any(not os.environ.get(name) for name in required):
        raise RuntimeError("isolated Kafka, Redis and PostgreSQL endpoints are required")
    kafka, redis_url, dsn = (os.environ[name] for name in required)
    redis_target = urlsplit(redis_url)
    postgres_target = urlsplit(dsn)
    if (
        kafka != "feelm401-kafka:9092"
        or redis_target.hostname != "feelm401-redis"
        or redis_target.port != 6379
        or redis_target.path != "/0"
        or postgres_target.hostname != "feelm401-postgres"
        or postgres_target.port != 5432
        or postgres_target.path != "/feelm401"
        or postgres_target.username != "feelm401"
    ):
        raise RuntimeError("endpoints must name the dedicated feelm401 Docker services and database")
    return kafka, redis_url, dsn, 401000000000 + secrets.randbelow(1_000_000_000)


def await_condition(predicate, timeout: float, label: str) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.1)
    raise AssertionError(f"timed out waiting for {label}")


def real_redis_check(store: redis.Redis, group: str, topic: str, user_id: int) -> dict[str, object]:
    old = claim_partition_fence(store, group, topic, 0)
    new = claim_partition_fence(store, group, topic, 0)
    before = store.mget(user_keys(user_id))
    assert before == [None, None, None]
    try:
        publish(
            store,
            user_id,
            0,
            {"candidateVersion": 4, "generationId": "old"},
            {"candidateVersion": 4, "generationId": "old"},
            old,
        )
    except StalePartitionFenceError:
        pass
    else:
        raise AssertionError("old Redis fence unexpectedly published")
    assert store.mget(user_keys(user_id)) == before
    assert current_version(store, user_id, new) == 0
    assert publish(
        store,
        user_id,
        0,
        {"candidateVersion": 4, "generationId": "new"},
        {"candidateVersion": 4, "generationId": "new"},
        new,
    ) == 1
    version, vector, candidates = store.mget(user_keys(user_id))
    assert version == "1"
    vector_payload = json.loads(vector)
    candidates_payload = json.loads(candidates)
    assert vector_payload["candidateVersion"] == candidates_payload["candidateVersion"] == 4
    assert vector_payload["generationId"] == candidates_payload["generationId"] == "new"
    normal_before = [version, vector, candidates]
    try:
        publish(
            store,
            user_id,
            1,
            {"candidateVersion": 4, "generationId": "old-overwrite"},
            {"candidateVersion": 4, "generationId": "old-overwrite"},
            old,
        )
    except StalePartitionFenceError:
        pass
    else:
        raise AssertionError("old fence unexpectedly overwrote existing three-key result")
    assert store.mget(user_keys(user_id)) == normal_before

    corrupt_user = user_id + 2
    version_key, vector_key, candidates_key = user_keys(corrupt_user)
    store.set(version_key, "not-a-number")
    store.set(candidates_key, '{"candidateVersion":4}')
    corrupt_before = store.mget((version_key, vector_key, candidates_key))
    newer = claim_partition_fence(store, group, topic, 0)
    try:
        current_version(store, corrupt_user, new)
    except StalePartitionFenceError:
        pass
    else:
        raise AssertionError("old fence unexpectedly recovered partial state")
    assert store.mget((version_key, vector_key, candidates_key)) == corrupt_before
    try:
        publish(
            store,
            corrupt_user,
            0,
            {"candidateVersion": 4, "generationId": "old-corrupt"},
            {"candidateVersion": 4, "generationId": "old-corrupt"},
            new,
        )
    except StalePartitionFenceError:
        pass
    else:
        raise AssertionError("old fence unexpectedly published over partial state")
    corrupt_after = store.mget((version_key, vector_key, candidates_key))
    assert corrupt_after == corrupt_before
    assert current_version(store, corrupt_user, newer) == 0
    assert store.mget((version_key, vector_key, candidates_key)) == [None, None, None]
    return {
        "old_publish_rejected": True,
        "new_version": 1,
        "three_keys_consistent": True,
        "old_recovery_rejected": True,
        "old_corrupt_publish_rejected": True,
        "new_recovery_cleared_partial_state": True,
        "normal_three_keys_before_old_attempt": normal_before,
        "normal_three_keys_after_old_attempt": store.mget(user_keys(user_id)),
        "corrupt_three_keys_before_old_attempt": corrupt_before,
        "corrupt_three_keys_after_old_attempt": corrupt_after,
    }


def prepare_postgres(dsn: str, user_id: int) -> None:
    with psycopg.connect(dsn) as connection:
        connection.execute("CREATE TABLE IF NOT EXISTS ratings (user_id BIGINT, movie_id BIGINT, score NUMERIC)")
        connection.execute(
            "CREATE TABLE IF NOT EXISTS onboarding_preferences "
            "(user_id BIGINT, movie_id BIGINT, preference TEXT)"
        )
        existing = connection.execute("SELECT COUNT(*) FROM ratings WHERE user_id = %s", (user_id,)).fetchone()
        if existing is None or existing[0] != 0:
            raise RuntimeError("test user ID is not empty in isolated PostgreSQL")
        connection.execute("INSERT INTO ratings (user_id, movie_id, score) VALUES (%s, 1, 1.0)", (user_id,))


def score_from_snapshot(_bundle, ratings, **_kwargs):
    score = ratings[0].score
    generation = f"score-{score}"
    return (
        {"candidateVersion": 4, "generationId": generation, "score": score},
        {"candidateVersion": 4, "generationId": generation, "score": score},
    )


def consumer_config(kafka: str, group: str) -> dict[str, object]:
    return {
        "bootstrap.servers": kafka,
        "group.id": group,
        "enable.auto.commit": False,
        "auto.offset.reset": "earliest",
        "session.timeout.ms": 6000,
        "heartbeat.interval.ms": 2000,
        "max.poll.interval.ms": 10000,
    }


def poll_message(consumer: Consumer, timeout: float):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        message = consumer.poll(0.5)
        if message is not None:
            if message.error():
                raise KafkaException(message.error())
            return message
    raise AssertionError("timed out waiting for Kafka fixture event")


def real_kafka_rebalance_check(
    kafka: str, store: redis.Redis, dsn: str, user_id: int, group: str, topic: str
) -> dict[str, object]:
    admin = AdminClient({"bootstrap.servers": kafka})
    admin.create_topics([NewTopic(topic, num_partitions=1, replication_factor=1)])[topic].result(20)
    old = Consumer(consumer_config(kafka, group))
    new = Consumer(consumer_config(kafka, group))
    old_fence: list[tuple[str, str]] = []
    new_fence: list[tuple[str, str]] = []
    new_messages: list[object] = []
    snapshot_read = threading.Event()
    release_old = threading.Event()
    old_result: list[object] = []
    snapshot_scores: list[float] = []
    previous_calculate = runner.calculate_user_results

    def old_assigned(client: Consumer, partitions: list[TopicPartition]) -> None:
        old_fence.append(claim_partition_fence(store, group, topic, 0))
        client.assign(partitions)

    def new_assigned(client: Consumer, partitions: list[TopicPartition]) -> None:
        new_fence.append(claim_partition_fence(store, group, topic, 0))
        client.assign(partitions)

    def delayed_calculate(bundle, ratings, **kwargs):
        snapshot_scores.append(ratings[0].score)
        snapshot_read.set()
        if not release_old.wait(45):
            raise AssertionError("old snapshot barrier timed out")
        return score_from_snapshot(bundle, ratings, **kwargs)

    try:
        old.subscribe([topic], on_assign=old_assigned)
        await_condition(lambda: bool(old_fence) or old.poll(0.5) is not None, 20, "old assignment")
        assert old_fence
        payload = {
            "eventId": str(uuid4()),
            "userId": user_id,
            "eventType": "RATING",
            "occurredAt": datetime.now(UTC).isoformat(),
            "payload": {"action": "UPDATE", "movieId": 1, "score": 1.0},
        }
        producer = Producer({"bootstrap.servers": kafka})
        producer.produce(topic, key=str(user_id), value=json.dumps(payload))
        assert producer.flush(20) == 0
        old_message = poll_message(old, 20)

        def run_old() -> None:
            try:
                old_result.append(
                    runner.process_user(
                        store,
                        lambda: psycopg.connect(dsn),
                        object(),
                        user_id,
                        attempts=1,
                        is_still_owned=lambda: True,
                        partition_fence=old_fence[-1],
                    )
                )
            except BaseException as exc:
                old_result.append(exc)

        runner.calculate_user_results = delayed_calculate
        worker = threading.Thread(target=run_old, daemon=True)
        worker.start()
        assert snapshot_read.wait(20), "old PostgreSQL snapshot was not read"
        with psycopg.connect(dsn) as connection:
            connection.execute("UPDATE ratings SET score = 5.0 WHERE user_id = %s", (user_id,))

        new.subscribe([topic], on_assign=new_assigned)
        def poll_for_new_assignment() -> bool:
            message = new.poll(0.5)
            if message is not None:
                if message.error():
                    raise KafkaException(message.error())
                new_messages.append(message)
            return bool(new_fence)

        await_condition(poll_for_new_assignment, 35, "new assignment")
        assert new_fence and new_fence[-1] != old_fence[-1]
        release_old.set()
        worker.join(timeout=15)
        assert not worker.is_alive()
        assert old_result == [None], f"old processing result: {old_result}"
        assert snapshot_scores == [1.0]
        assert store.mget(user_keys(user_id)) == [None, None, None]
        before_commit = new.committed([TopicPartition(topic, 0)], timeout=10)[0].offset
        assert before_commit < 0, f"old offset advanced to {before_commit}"

        try:
            old.commit(message=old_message, asynchronous=False)
        except KafkaException:
            old_commit_rejected = True
        else:
            old_commit_rejected = False
        assert old_commit_rejected, "stale Kafka generation unexpectedly committed"
        assert new.committed([TopicPartition(topic, 0)], timeout=10)[0].offset < 0

        runner.calculate_user_results = score_from_snapshot
        new_message = new_messages.pop(0) if new_messages else poll_message(new, 20)
        assert new_message.offset() == old_message.offset()
        assert runner.process_user(
            store,
            lambda: psycopg.connect(dsn),
            object(),
            user_id,
            attempts=1,
            partition_fence=new_fence[-1],
        ) is True
        new.commit(message=new_message, asynchronous=False)
        committed = new.committed([TopicPartition(topic, 0)], timeout=10)[0].offset
        assert committed == old_message.offset() + 1
        version, vector, candidates = store.mget(user_keys(user_id))
        assert version == "1"
        assert json.loads(vector)["score"] == json.loads(candidates)["score"] == 5.0
        return {
            "old_snapshot_score": 1.0,
            "old_redis_publish_rejected": True,
            "old_offset_commit_rejected": old_commit_rejected,
            "new_snapshot_score": 5.0,
            "new_committed_offset": committed,
        }
    finally:
        release_old.set()
        runner.calculate_user_results = previous_calculate
        old.close()
        new.close()


def main() -> None:
    if len(sys.argv) > 2 or (len(sys.argv) == 2 and sys.argv[1] != "--report"):
        raise RuntimeError("usage: verify_fencing_integration.py [--report]")
    report_path = None
    if len(sys.argv) == 2:
        report_path = Path(os.environ["FEELM_401_REPORT_PATH"])
        if report_path.name != "integration.json" or "runtime" not in report_path.parts:
            raise RuntimeError("report path must be a 401 runtime integration.json file")
    kafka, redis_url, dsn, user_id = require_isolation()
    run_id = uuid4().hex[:12]
    topic = f"feelm401-{run_id}"
    group = f"feelm401-{run_id}"
    store = redis.Redis.from_url(redis_url, decode_responses=True)
    store.ping()
    prepare_postgres(dsn, user_id)
    prepare_postgres(dsn, user_id + 1)
    redis_result = real_redis_check(store, group, topic, user_id)
    # Separate user ID so the Redis-only check does not seed the rebalance fixture.
    rebalance_result = real_kafka_rebalance_check(kafka, store, dsn, user_id + 1, group, topic)
    report = {"run_id": run_id, "redis": redis_result, "rebalance": rebalance_result}
    serialized = json.dumps(report, sort_keys=True)
    if report_path is not None:
        report_path.write_text(serialized + "\n", encoding="utf-8")
    print(serialized)


if __name__ == "__main__":
    main()
