"""Exercise the actual Consumer run loop across an isolated Kafka rebalance.

The old process is SIGSTOPed after its real PostgreSQL read. The broker then
expires its group membership and assigns the partition to a new run loop.
The only replaced production dependency is candidate scoring: it emits the
rating read from PostgreSQL as a small deterministic fixture payload.
"""

from __future__ import annotations

import json
import logging
import multiprocessing
import os
import signal
import time
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

import psycopg
import redis
from confluent_kafka import Consumer, Producer, TopicPartition
from confluent_kafka.admin import AdminClient, NewTopic
from verify_fencing_integration import await_condition, prepare_postgres, require_isolation, score_from_snapshot

from feelm_consumer import runner
from feelm_consumer.redis_store import StalePartitionFenceError, user_keys
from feelm_consumer.settings import load_settings


def worker(role: str, kafka: str, redis_url: str, dsn: str, topic: str, group: str, directory: Path) -> None:
    logging.basicConfig(level=logging.WARNING)
    runner.load_bundle = lambda *_args: object()
    real_claim = runner.claim_partition_fence
    real_publish = runner.publish
    real_consumer = runner.Consumer

    def claim(*args):
        fence = real_claim(*args)
        (directory / f"{role}-assigned").touch(exist_ok=True)
        return fence

    def calculate(bundle, ratings, onboarding, **kwargs):
        if role == "old":
            (directory / "old-snapshot").write_text(str(ratings[0].score), encoding="utf-8")
            await_condition(lambda: (directory / "release-old").exists(), 120, "old release")
        else:
            (directory / "new-snapshot").write_text(str(ratings[0].score), encoding="utf-8")
        return score_from_snapshot(bundle, ratings, onboarding, **kwargs)

    def publish(*args):
        try:
            result = real_publish(*args)
        except StalePartitionFenceError:
            (directory / f"{role}-stale-publish").touch(exist_ok=True)
            raise
        (directory / f"{role}-published").touch(exist_ok=True)
        return result

    class AuditedConsumer:
        def __init__(self, config):
            self.client = real_consumer(config)

        def __getattr__(self, name):
            return getattr(self.client, name)

        def commit(self, *, message, asynchronous):
            (directory / f"{role}-commit-called").touch(exist_ok=True)
            return self.client.commit(message=message, asynchronous=asynchronous)

    runner.claim_partition_fence = claim
    runner.calculate_user_results = calculate
    runner.publish = publish
    runner.Consumer = AuditedConsumer
    settings = load_settings(
        {
            "CONSUMER_KAFKA_BOOTSTRAP_SERVERS": kafka,
            "CONSUMER_KAFKA_TOPIC": topic,
            "CONSUMER_KAFKA_GROUP_ID": group,
            "CONSUMER_POSTGRES_DSN": dsn,
            "CONSUMER_REDIS_URL": redis_url,
            "CONSUMER_MODEL_URI": "/fixture/model-v1",
            "CONSUMER_MAPPING_URI": "/fixture/mapping-v1",
            "CONSUMER_METADATA_URI": "/fixture/metadata-v1",
            "CONSUMER_KAFKA_POLL_TIMEOUT_SECONDS": "0.2",
        }
    )
    runner.run(settings)


def committed_offset(kafka: str, group: str, topic: str) -> int:
    client = Consumer({"bootstrap.servers": kafka, "group.id": group})
    try:
        return client.committed([TopicPartition(topic, 0)], timeout=10)[0].offset
    finally:
        client.close()


def stop_process(process: multiprocessing.Process) -> None:
    if process.is_alive():
        process.terminate()
        process.join(timeout=10)
    if process.is_alive():
        process.kill()
        process.join(timeout=5)


def main() -> None:
    kafka, redis_url, dsn, user_id = require_isolation()
    directory = Path(os.environ["FEELM_401_RUNNER_REPORT_DIR"])
    if directory.name != "runner" or "runtime" not in directory.parts or any(directory.iterdir()):
        raise RuntimeError("runner report directory must be an empty 401 runtime/runner folder")
    run_id = uuid4().hex[:12]
    topic = f"feelm401-runner-{run_id}"
    group = topic
    store = redis.Redis.from_url(redis_url, decode_responses=True)
    store.ping()
    prepare_postgres(dsn, user_id)
    admin = AdminClient({"bootstrap.servers": kafka})
    admin.create_topics([NewTopic(topic, num_partitions=1, replication_factor=1)])[topic].result(20)
    before_keys = store.mget(user_keys(user_id))
    assert before_keys == [None, None, None]
    before_offset = committed_offset(kafka, group, topic)
    assert before_offset < 0

    context = multiprocessing.get_context("spawn")
    old = context.Process(target=worker, args=("old", kafka, redis_url, dsn, topic, group, directory))
    new = context.Process(target=worker, args=("new", kafka, redis_url, dsn, topic, group, directory))
    old_stopped = False
    try:
        old.start()
        await_condition(lambda: (directory / "old-assigned").exists(), 30, "old assignment")
        event = {
            "eventId": str(uuid4()),
            "userId": user_id,
            "eventType": "RATING",
            "occurredAt": datetime.now(UTC).isoformat(),
            "payload": {"action": "UPDATE", "movieId": 1, "score": 1.0},
        }
        producer = Producer({"bootstrap.servers": kafka})
        producer.produce(topic, key=str(user_id), value=json.dumps(event))
        assert producer.flush(20) == 0
        await_condition(lambda: (directory / "old-snapshot").exists(), 30, "old PostgreSQL snapshot")
        assert (directory / "old-snapshot").read_text(encoding="utf-8") == "1.0"
        os.kill(old.pid, signal.SIGSTOP)
        old_stopped = True
        with psycopg.connect(dsn) as connection:
            connection.execute("UPDATE ratings SET score = 5.0 WHERE user_id = %s", (user_id,))
        new.start()
        await_condition(lambda: (directory / "new-assigned").exists(), 105, "new assignment")
        await_condition(lambda: (directory / "new-commit-called").exists(), 30, "new commit call")
        await_condition(lambda: committed_offset(kafka, group, topic) == 1, 30, "new offset 1")
        assert (directory / "new-snapshot").read_text(encoding="utf-8") == "5.0"
        new_keys = store.mget(user_keys(user_id))
        assert new_keys[0] == "1"
        assert json.loads(new_keys[1])["score"] == json.loads(new_keys[2])["score"] == 5.0

        os.kill(old.pid, signal.SIGCONT)
        old_stopped = False
        (directory / "release-old").touch(exist_ok=False)
        await_condition(lambda: (directory / "old-stale-publish").exists(), 20, "old stale Redis publish")
        time.sleep(0.5)
        after_old_keys = store.mget(user_keys(user_id))
        after_old_offset = committed_offset(kafka, group, topic)
        assert after_old_keys == new_keys
        assert after_old_offset == 1
        assert not (directory / "old-commit-called").exists()
        report = {
            "run_id": run_id,
            "old_snapshot_score": 1.0,
            "new_snapshot_score": 5.0,
            "before_three_keys": before_keys,
            "after_new_three_keys": new_keys,
            "after_old_three_keys": after_old_keys,
            "before_offset": before_offset,
            "after_old_offset": after_old_offset,
            "old_stale_publish": True,
            "old_commit_called": False,
            "new_commit_called": True,
        }
        serialized = json.dumps(report, sort_keys=True)
        (directory / "result.json").write_text(serialized + "\n", encoding="utf-8")
        print(serialized, flush=True)
    finally:
        if old_stopped:
            os.kill(old.pid, signal.SIGCONT)
        stop_process(old)
        if new.pid is not None:
            stop_process(new)


if __name__ == "__main__":
    main()
