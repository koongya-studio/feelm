from __future__ import annotations

import logging
import signal
from collections.abc import Callable, Mapping
from datetime import UTC, datetime
from time import sleep
from typing import Any

import redis
from confluent_kafka import Consumer, KafkaException, Producer, TopicPartition

from feelm_consumer.artifacts import ArtifactBundle, load_bundle
from feelm_consumer.party_candidate_events import (
    MAX_MODEL_VERSION_LENGTH,
    MODEL_VERSION_PATTERN,
    PartyCandidateEventPublishError,
    publish_party_candidate_updated,
)
from feelm_consumer.party_events import PartyEventValidationError, PartyRefresh, parse_party_refresh
from feelm_consumer.party_redis_store import (
    StalePartyModelError,
    StalePartyPartitionFenceError,
    claim_party_partition_fence,
    publish_party,
)
from feelm_consumer.party_service import calculate_party_result
from feelm_consumer.party_settings import PartySettings

LOG = logging.getLogger(__name__)


def process_party(
    store: object,
    bundle: ArtifactBundle,
    refresh: PartyRefresh,
    *,
    partition_fence: tuple[str, str] | None,
    attempts: int = 3,
    backoff_seconds: float = 0.25,
    is_still_owned: Any = None,
    candidate_event_publisher: Callable[[Mapping[str, object]], None] | None = None,
    generated_at: str | None = None,
) -> bool | None:
    """Publish one result; return None when this assignment has lost its fence."""
    payload: Mapping[str, object] | None = None
    for attempt in range(attempts):
        try:
            if partition_fence is None:
                raise ValueError("partition fence is required")
            # Delivery ACK가 불확실해 재시도하더라도 같은 source offset은 같은 snapshot이다.
            # generatedAt까지 고정해 Redis와 중복 완료 이벤트의 식별 정보가 갈라지지 않게 한다.
            if payload is None:
                if generated_at is None:
                    payload = calculate_party_result(bundle, refresh)
                else:
                    payload = calculate_party_result(bundle, refresh, generated_at=generated_at)
            if is_still_owned is not None and not is_still_owned():
                LOG.warning("party partition ownership lost before Redis publish")
                return None
            publish_party(store, refresh.party_id, bundle.model_version, payload, partition_fence)
            if candidate_event_publisher is not None:
                candidate_event_publisher(payload)
            LOG.info("party candidate published")
            return True
        except StalePartyPartitionFenceError:
            LOG.info("party partition fence lost before Redis publish")
            return None
        except StalePartyModelError:
            LOG.warning("party model fence rejected publish; restart with current artifacts")
            return False
        except (redis.RedisError, ValueError, PartyCandidateEventPublishError) as exc:
            LOG.warning("party dependency or data failure code=%s", type(exc).__name__)
            if attempt + 1 < attempts:
                sleep(backoff_seconds * (2**attempt))
    return False


def run(settings: PartySettings) -> None:
    # Artifact and revision validation must finish before external clients are created.
    bundle = load_bundle(settings.model_uri, settings.mapping_uri, settings.metadata_uri)
    if (
        len(bundle.model_version) > MAX_MODEL_VERSION_LENGTH
        or not MODEL_VERSION_PATTERN.fullmatch(bundle.model_version)
    ):
        raise ValueError("party model version exceeds the Spring reader contract")
    store = redis.Redis.from_url(settings.redis_url, decode_responses=True)
    consumer = Consumer(
        {
            "bootstrap.servers": settings.kafka_bootstrap_servers,
            "group.id": settings.kafka_group_id,
            "enable.auto.commit": False,
            "auto.offset.reset": "earliest",
        }
    )
    producer: Producer | None = None

    def send_candidate_event(
        result: Mapping[str, object], source_partition: int, source_offset: int
    ) -> None:
        nonlocal producer
        if producer is None:
            try:
                producer = Producer(
                    {
                        "bootstrap.servers": settings.kafka_bootstrap_servers,
                        "enable.idempotence": True,
                        "acks": "all",
                    }
                )
            except KafkaException as error:
                raise PartyCandidateEventPublishError(
                    "party candidate notification Kafka producer failed"
                ) from error
        publish_party_candidate_updated(
            producer,
            result,
            source_partition=source_partition,
            source_offset=source_offset,
        )
    stopping = False
    paused: dict[tuple[str, int], int] = {}
    owned: set[tuple[str, int]] = set()
    fences: dict[tuple[str, int], tuple[str, str]] = {}
    assignment_failed = False

    def stop(_signum: int, _frame: object) -> None:
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)

    def on_assign(client: Consumer, partitions: list[TopicPartition]) -> None:
        nonlocal assignment_failed
        claims: dict[tuple[str, int], tuple[str, str]] = {}
        try:
            for partition in partitions:
                key = (partition.topic, partition.partition)
                claims[key] = claim_party_partition_fence(
                    store, settings.kafka_group_id, partition.topic, partition.partition
                )
        except redis.RedisError as exc:
            LOG.error("party partition fence claim failed; restart required code=%s", type(exc).__name__)
            assignment_failed = True
            return
        owned.update((partition.topic, partition.partition) for partition in partitions)
        fences.update(claims)
        client.assign(partitions)
        LOG.info(
            "party Kafka partitions assigned count=%s partitions=%s",
            len(partitions),
            ",".join(f"{partition.topic}:{partition.partition}" for partition in partitions),
        )

    def on_revoke(client: Consumer, partitions: list[TopicPartition]) -> None:
        for partition in partitions:
            key = (partition.topic, partition.partition)
            paused.pop(key, None)
            owned.discard(key)
            fences.pop(key, None)
        client.unassign()
        LOG.info("party Kafka partitions revoked count=%s", len(partitions))

    def pause_for_failure(message: Any, partition: TopicPartition, reason: str) -> None:
        paused[(message.topic(), message.partition())] = 0
        LOG.error(
            "%s; party partition paused topic=%s partition=%s offset=%s",
            reason,
            message.topic(),
            message.partition(),
            message.offset(),
        )
        consumer.pause([partition])

    try:
        consumer.subscribe([settings.kafka_topic], on_assign=on_assign, on_revoke=on_revoke)
        while not stopping:
            if assignment_failed:
                raise RuntimeError("party partition fence claim failed; restart required")
            message = consumer.poll(settings.kafka_poll_timeout_seconds)
            if assignment_failed:
                raise RuntimeError("party partition fence claim failed; restart required")
            for key in paused:
                paused[key] += 1
            all_owned_partitions_paused = bool(owned) and owned.issubset(paused)
            if all_owned_partitions_paused and max(paused.values()) >= settings.max_paused_polls:
                raise RuntimeError("paused party Kafka partition requires restart")
            if message is None:
                continue
            if message.error():
                LOG.warning("party Kafka record error topic=%s partition=%s", message.topic(), message.partition())
                continue
            partition = TopicPartition(message.topic(), message.partition())
            try:
                refresh = parse_party_refresh(message.value(), message.key())
                generated_at = _source_generated_at(message)
            except PartyEventValidationError:
                pause_for_failure(message, partition, "invalid party Kafka event")
                continue
            key = (message.topic(), message.partition())
            processed = process_party(
                store,
                bundle,
                refresh,
                partition_fence=fences.get(key),
                attempts=settings.retry_attempts,
                backoff_seconds=settings.retry_backoff_seconds,
                is_still_owned=lambda: key in owned,
                candidate_event_publisher=lambda result: send_candidate_event(
                    result, message.partition(), message.offset()
                ),
                generated_at=generated_at,
            )
            if processed is None:
                LOG.warning("party partition ownership lost before commit topic=%s partition=%s", *key)
                continue
            if not processed:
                if key in owned:
                    pause_for_failure(message, partition, "party processing failed")
                continue
            if key not in owned:
                LOG.warning("party partition ownership lost before commit topic=%s partition=%s", *key)
                continue
            try:
                consumer.commit(message=message, asynchronous=False)
                LOG.info(
                    "party Kafka event committed topic=%s partition=%s offset=%s",
                    message.topic(),
                    message.partition(),
                    message.offset(),
                )
            except KafkaException:
                pause_for_failure(message, partition, "party Kafka commit failed")
    finally:
        consumer.close()


def _source_generated_at(message: Any) -> str:
    """Return a replay-stable UTC timestamp from the Kafka source record."""
    try:
        _timestamp_type, timestamp_ms = message.timestamp()
        if isinstance(timestamp_ms, bool) or not isinstance(timestamp_ms, int) or timestamp_ms < 0:
            raise ValueError("Kafka record timestamp is unavailable")
        return (
            datetime.fromtimestamp(timestamp_ms / 1_000, UTC)
            .isoformat(timespec="milliseconds")
            .replace("+00:00", "Z")
        )
    except (OSError, OverflowError, TypeError, ValueError) as exc:
        raise PartyEventValidationError("Kafka record timestamp is invalid") from exc
