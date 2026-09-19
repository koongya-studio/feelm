"""Opt-in isolated Kafka/Redis integration for the party Consumer boundary."""

from __future__ import annotations

import json
import os
import time
from struct import pack
from uuid import uuid4

import numpy as np
import pytest
import redis
from confluent_kafka import Consumer, Producer, TopicPartition
from confluent_kafka.admin import AdminClient, NewTopic

from feelm_consumer.artifacts import ArtifactBundle
from feelm_consumer.party_candidate_events import publish_party_candidate_updated
from feelm_consumer.party_events import parse_party_refresh
from feelm_consumer.party_redis_store import (
    StalePartyModelError,
    StalePartyPartitionFenceError,
    claim_party_partition_fence,
    party_candidates_key,
    publish_party,
)
from feelm_consumer.party_runner import _source_generated_at
from feelm_consumer.party_service import calculate_party_result

KAFKA = os.environ.get("PARTY_CONSUMER_INTEGRATION_KAFKA_BOOTSTRAP_SERVERS")
REDIS = os.environ.get("PARTY_CONSUMER_INTEGRATION_REDIS_URL")
pytestmark = pytest.mark.skipif(
    not KAFKA or not REDIS,
    reason="isolated PARTY_CONSUMER_INTEGRATION_KAFKA_BOOTSTRAP_SERVERS/REDIS_URL are required",
)


def _bundle() -> ArtifactBundle:
    return ArtifactBundle(
        "als-party-integration-v1",
        "snapshot-a",
        0.1,
        {1: np.asarray([1.0], dtype=np.float64)},
        {100: 1},
        {},
        "sha256:" + "a" * 64,
        "sha256:" + "b" * 64,
    )


def test_keyed_kafka_record_is_published_then_committed_with_model_fence() -> None:
    suffix = uuid4().hex
    topic = f"test.feelm.party-refresh.{suffix}"
    completion_topic = f"test.feelm.party-candidate-updated.{suffix}"
    group = f"test-feelm-party-{suffix}"
    party_id = int(suffix[:12], 16)
    admin = AdminClient({"bootstrap.servers": KAFKA})
    store = redis.Redis.from_url(REDIS, decode_responses=True)
    old_model = store.get("feelm:model:current")
    consumer = Consumer(
        {
            "bootstrap.servers": KAFKA,
            "group.id": group,
            "enable.auto.commit": False,
            "auto.offset.reset": "latest",
        }
    )
    completion_consumer = Consumer(
        {
            "bootstrap.servers": KAFKA,
            "group.id": f"test-feelm-party-completion-{suffix}",
            "enable.auto.commit": False,
            "auto.offset.reset": "earliest",
        }
    )
    try:
        topics = admin.create_topics([NewTopic(topic, 1, 1), NewTopic(completion_topic, 1, 1)])
        topics[topic].result(20)
        topics[completion_topic].result(20)
        bundle = _bundle()
        store.set("feelm:model:current", json.dumps({"modelVersion": bundle.model_version}))
        consumer.subscribe([topic])
        deadline = time.monotonic() + 20
        while not consumer.assignment() and time.monotonic() < deadline:
            consumer.poll(0.2)
        assert consumer.assignment()
        fence = claim_party_partition_fence(store, group, topic, 0)
        completion_consumer.subscribe([completion_topic])

        producer = Producer({"bootstrap.servers": KAFKA, "enable.idempotence": True})
        value = json.dumps(
            {
                "partyId": party_id,
                "inputId": "sha256:" + "a" * 64,
                "members": [{"userId": 10, "vector": [1.0], "candidateMovieIds": [100, 200]}],
            },
            separators=(",", ":"),
        ).encode()
        for _ in range(2):
            producer.produce(topic, key=pack(">q", party_id), value=value)
        producer.flush(20)
        messages = []
        deadline = time.monotonic() + 20
        while len(messages) < 2 and time.monotonic() < deadline:
            message = consumer.poll(1)
            if message is not None and not message.error():
                messages.append(message)
        assert len(messages) == 2

        for message in messages:
            refresh = parse_party_refresh(message.value(), message.key())
            result = calculate_party_result(
                bundle, refresh, generated_at=_source_generated_at(message)
            )
            publish_party(store, party_id, bundle.model_version, result, fence)
            publish_party_candidate_updated(
                producer,
                result,
                source_partition=message.partition(),
                source_offset=message.offset(),
                topic=completion_topic,
            )
            consumer.commit(message=message, asynchronous=False)

        stored = json.loads(store.get(party_candidates_key(party_id)))
        assert stored["partyId"] == party_id
        assert stored["inputId"] == "sha256:" + "a" * 64
        assert stored["modelVersion"] == bundle.model_version
        assert [candidate["movieId"] for candidate in stored["candidates"]] == [100, 200]
        committed = consumer.committed([TopicPartition(topic, 0)], timeout=10)[0]
        assert committed.offset >= messages[-1].offset() + 1
        completions = []
        deadline = time.monotonic() + 20
        while len(completions) < 2 and time.monotonic() < deadline:
            completion = completion_consumer.poll(1)
            if completion is not None and not completion.error():
                completions.append(json.loads(completion.value()))
        assert [completion["sourceOffset"] for completion in completions] == [
            messages[0].offset(),
            messages[1].offset(),
        ]
        assert all(completion["partyId"] == party_id for completion in completions)
        assert completions[-1]["generatedAt"] == stored["generatedAt"]

        new_fence = claim_party_partition_fence(store, group, topic, 0)
        with pytest.raises(StalePartyPartitionFenceError):
            publish_party(store, party_id, bundle.model_version, stored, fence)
        assert json.loads(store.get(party_candidates_key(party_id)))["inputId"] == stored["inputId"]

        store.set("feelm:model:current", json.dumps({"modelVersion": "als-party-integration-v2"}))
        with pytest.raises(StalePartyModelError):
            publish_party(store, party_id, bundle.model_version, stored, new_fence)
        assert json.loads(store.get(party_candidates_key(party_id)))["modelVersion"] == bundle.model_version
    finally:
        consumer.close()
        completion_consumer.close()
        store.delete(party_candidates_key(party_id))
        for key in store.scan_iter(f"feelm:party-consumer-fence:{group}:*"):
            store.delete(key)
        if old_model is None:
            store.delete("feelm:model:current")
        else:
            store.set("feelm:model:current", old_model)
        try:
            deletions = admin.delete_topics([topic, completion_topic], operation_timeout=10)
            deletions[topic].result(20)
            deletions[completion_topic].result(20)
        except Exception:
            pass
