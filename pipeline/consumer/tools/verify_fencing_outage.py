"""Real isolated Redis/Kafka outage and replay check for S15P21E106-401.

Run in a disposable network with a writable checkpoint directory. An external
operator stops only the named test service at the ``ready`` checkpoint and
starts it again at the ``failed`` checkpoint.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from uuid import uuid4

import psycopg
import redis
from confluent_kafka import Consumer, KafkaException, Producer, TopicPartition
from confluent_kafka.admin import AdminClient, NewTopic
from verify_fencing_integration import (
    await_condition,
    consumer_config,
    poll_message,
    prepare_postgres,
    require_isolation,
    score_from_snapshot,
)

from feelm_consumer import runner
from feelm_consumer.redis_store import claim_partition_fence, user_keys


def checkpoint(directory: Path, name: str) -> None:
    (directory / name).touch(exist_ok=False)


def wait_for(directory: Path, name: str, timeout: float = 75) -> None:
    await_condition(lambda: (directory / name).exists(), timeout, name)


def consumer_for_topic(kafka: str, group: str, topic: str, store: redis.Redis):
    config = consumer_config(kafka, group)
    config.update({"socket.timeout.ms": 3000, "max.poll.interval.ms": 120000})
    consumer = Consumer(config)
    fences: list[tuple[str, str]] = []
    messages: list[object] = []

    def on_assign(client: Consumer, partitions: list[TopicPartition]) -> None:
        fences.append(claim_partition_fence(store, group, topic, 0))
        client.assign(partitions)

    consumer.subscribe([topic], on_assign=on_assign)
    def assigned() -> bool:
        message = consumer.poll(0.5)
        if message is not None:
            if message.error():
                raise KafkaException(message.error())
            messages.append(message)
        return bool(fences)

    await_condition(assigned, 25, "Kafka assignment")
    assert fences
    return consumer, fences, messages


def produce_fixture(kafka: str, topic: str, user_id: int) -> None:
    producer = Producer({"bootstrap.servers": kafka})
    producer.produce(topic, key=str(user_id), value=b"401-isolated-fixture")
    assert producer.flush(20) == 0


def process(store: redis.Redis, dsn: str, user_id: int, fence: tuple[str, str]) -> bool | None:
    return runner.process_user(
        store,
        lambda: psycopg.connect(dsn),
        object(),
        user_id,
        attempts=1,
        is_still_owned=lambda: True,
        partition_fence=fence,
    )


def run_redis_outage(
    kafka: str, store: redis.Redis, dsn: str, directory: Path, user_id: int, topic: str, group: str
) -> dict[str, object]:
    consumer, fences, messages = consumer_for_topic(kafka, group, topic, store)
    try:
        produce_fixture(kafka, topic, user_id)
        message = messages.pop(0) if messages else poll_message(consumer, 20)
        checkpoint(directory, "ready")
        wait_for(directory, "go")
        assert process(store, dsn, user_id, fences[-1]) is False
        checkpoint(directory, "failed")
        wait_for(directory, "recovered")
    finally:
        consumer.close()

    replay, replay_fences, replay_messages = consumer_for_topic(kafka, group, topic, store)
    try:
        assert replay.committed([TopicPartition(topic, 0)], timeout=10)[0].offset < 0
        replay_message = replay_messages.pop(0) if replay_messages else poll_message(replay, 20)
        assert replay_message.offset() == message.offset()
        assert process(store, dsn, user_id, replay_fences[-1]) is True
        replay.commit(message=replay_message, asynchronous=False)
        committed = replay.committed([TopicPartition(topic, 0)], timeout=10)[0].offset
        assert committed == message.offset() + 1
        version, vector, candidates = store.mget(user_keys(user_id))
        assert version == "1"
        assert json.loads(vector)["generationId"] == json.loads(candidates)["generationId"]
        return {
            "redis_outage_failed_closed": True,
            "offset_before_recovery": "uncommitted",
            "replay_version": 1,
            "committed_offset": committed,
        }
    finally:
        replay.close()


def run_kafka_outage(
    kafka: str, store: redis.Redis, dsn: str, directory: Path, user_id: int, topic: str, group: str
) -> dict[str, object]:
    consumer, fences, messages = consumer_for_topic(kafka, group, topic, store)
    try:
        produce_fixture(kafka, topic, user_id)
        message = messages.pop(0) if messages else poll_message(consumer, 20)
        assert process(store, dsn, user_id, fences[-1]) is True
        assert store.get(user_keys(user_id)[0]) == "1"
        checkpoint(directory, "ready")
        wait_for(directory, "go")
        try:
            result = consumer.commit(message=message, asynchronous=False)
        except KafkaException:
            rejected = True
        else:
            rejected = bool(result and result[0].error is not None)
        assert rejected, "Kafka commit unexpectedly succeeded during broker outage"
        checkpoint(directory, "failed")
    finally:
        consumer.close()

    wait_for(directory, "recovered")
    replay, replay_fences, replay_messages = consumer_for_topic(kafka, group, topic, store)
    try:
        assert replay.committed([TopicPartition(topic, 0)], timeout=10)[0].offset < 0
        message = replay_messages.pop(0) if replay_messages else poll_message(replay, 20)
        assert message.offset() == 0
        assert process(store, dsn, user_id, replay_fences[-1]) is True
        replay.commit(message=message, asynchronous=False)
        committed = replay.committed([TopicPartition(topic, 0)], timeout=10)[0].offset
        assert committed == 1
        assert store.get(user_keys(user_id)[0]) == "2"
        return {"kafka_commit_rejected": True, "replay_version": 2, "committed_offset": committed}
    finally:
        replay.close()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("kind", choices=("redis", "kafka"))
    parser.add_argument("--checkpoint-dir", required=True, type=Path)
    args = parser.parse_args()
    kafka, redis_url, dsn, user_id = require_isolation()
    directory = args.checkpoint_dir
    if any(directory.iterdir()):
        raise RuntimeError("checkpoint directory must be empty")
    run_id = uuid4().hex[:12]
    topic = f"feelm401-{args.kind}-{run_id}"
    group = topic
    store = redis.Redis.from_url(redis_url, decode_responses=True, socket_connect_timeout=2, socket_timeout=2)
    store.ping()
    prepare_postgres(dsn, user_id)
    admin = AdminClient({"bootstrap.servers": kafka})
    admin.create_topics([NewTopic(topic, num_partitions=1, replication_factor=1)])[topic].result(20)
    previous_calculate = runner.calculate_user_results
    runner.calculate_user_results = score_from_snapshot
    try:
        if args.kind == "redis":
            result = run_redis_outage(kafka, store, dsn, directory, user_id, topic, group)
        else:
            result = run_kafka_outage(kafka, store, dsn, directory, user_id, topic, group)
    finally:
        runner.calculate_user_results = previous_calculate
    report = {"run_id": run_id, "kind": args.kind, "result": result}
    serialized = json.dumps(report, sort_keys=True)
    (directory / "result.json").write_text(serialized + "\n", encoding="utf-8")
    print(serialized, flush=True)


if __name__ == "__main__":
    main()
