from __future__ import annotations

import logging
import signal
from collections.abc import Callable
from time import sleep
from typing import Any

import psycopg
import redis
from confluent_kafka import Consumer, KafkaException, Producer, TopicPartition

from feelm_consumer.artifacts import ArtifactBundle, load_bundle
from feelm_consumer.candidate_events import CandidateEventPublishError, publish_candidate_updated
from feelm_consumer.content import ContentBundle, load_content
from feelm_consumer.discovery_popularity import load_discovery_popularity
from feelm_consumer.discovery_release import validate_discovery_artifacts
from feelm_consumer.events import EventValidationError, parse_event
from feelm_consumer.group_artifacts import GroupBundle, load_group_bundle
from feelm_consumer.postgres import load_user_snapshot, readonly_snapshot
from feelm_consumer.ranking import RankedMovie
from feelm_consumer.redis_store import (
    StalePartitionFenceError,
    claim_partition_fence,
    current_version,
    publish,
)
from feelm_consumer.service import calculate_user_results
from feelm_consumer.settings import Settings

LOG = logging.getLogger(__name__)


def _expected_version(store: Any, user_id: int, partition_fence: tuple[str, str]) -> int:
    return current_version(store, user_id, partition_fence)


def process_user(
    store: Any,
    connect: Callable[[], Any],
    bundle: ArtifactBundle,
    user_id: int,
    *,
    attempts: int = 3,
    backoff_seconds: float = 0.25,
    is_still_owned: Callable[[], bool] | None = None,
    partition_fence: tuple[str, str] | None = None,
    content: ContentBundle | None = None,
    groups: GroupBundle | None = None,
    discovery_popularity: tuple[RankedMovie, ...] | None = None,
    hybrid_alpha: float = 0.5,
    candidate_event_publisher: Callable[[int], None] | None = None,
) -> bool | None:
    for attempt in range(attempts):
        try:
            if partition_fence is None:
                raise ValueError("partition fence is required")
            expected = _expected_version(store, user_id, partition_fence)
            with readonly_snapshot(connect) as connection:
                snapshot = load_user_snapshot(connection, user_id)
            results = calculate_user_results(
                bundle,
                snapshot.ratings,
                snapshot.onboarding,
                content=content,
                groups=groups,
                discovery_popularity=discovery_popularity,
                watched_movie_ids=snapshot.watched_movie_ids,
                dismissed_movie_ids=snapshot.dismissed_movie_ids,
                hybrid_alpha=hybrid_alpha,
            )
            if results is None:
                # Not a failure: the configured artifacts expose no publishable
                # candidate population, so retrying changes nothing.
                # Treating it as a failure would pause the partition, leave the
                # offset uncommitted, and replay the same event after restart.
                LOG.warning("no_publishable_result_skipped")
                return True
            if is_still_owned is not None and not is_still_owned():
                LOG.warning("partition ownership lost before Redis publish")
                return False
            publish(store, user_id, expected, *results, partition_fence)
            if candidate_event_publisher is not None:
                candidate_event_publisher(user_id)
            LOG.info("candidate published")
            return True
        except StalePartitionFenceError:
            LOG.info("partition fence lost before Redis publish")
            return None
        except (redis.RedisError, psycopg.Error, ValueError, CandidateEventPublishError) as exc:
            # Do not log DSNs, Redis URLs, event payloads, or user identifiers.
            LOG.warning("fold-in dependency or data failure code=%s", type(exc).__name__)
            if attempt + 1 < attempts:
                sleep(backoff_seconds * (2**attempt))
    return False


def run(settings: Settings) -> None:
    bundle = load_bundle(settings.model_uri, settings.mapping_uri, settings.metadata_uri)
    content = None
    if settings.content_uri is not None:
        content = load_content(
            settings.content_uri,
            mapping_revision=bundle.mapping_revision,
            movie_metadata_revision=bundle.movie_metadata_revision,
            input_snapshot=bundle.input_snapshot,
            service_movie_ids=set(bundle.movie_metadata),
        )
        LOG.info(
            "content artifacts loaded version=%s movies=%s supported=%s vocabulary=%s alpha=%s",
            content.content_version,
            content.movie_count,
            int(content.supported.sum()),
            content.vocabulary_size,
            settings.hybrid_alpha,
        )
    else:
        LOG.info("content artifacts not configured; publishing ALS-only candidates")
    groups = None
    discovery_popularity = None
    if settings.group_uri is not None:
        groups = load_group_bundle(settings.group_uri, expected_mapping_revision=bundle.mapping_revision)
        popularity = load_discovery_popularity(
            settings.discovery_popularity_uri or "",
            expected_mapping_revision=bundle.mapping_revision,
        )
        release = validate_discovery_artifacts(groups, popularity)
        discovery_popularity = popularity.candidates
        LOG.info(
            "group and Discovery popularity artifacts loaded "
            "group_version=%s popularity_version=%s movies=%s mapped=%s groups=%s groups_ge_10=%s",
            groups.group_version,
            popularity.popularity_version,
            release.movie_count,
            release.mapped_movie_count,
            release.mapped_group_count,
            release.groups_with_at_least_ten_movies,
        )
    else:
        LOG.info("group artifacts not configured; Discovery uses ranked-candidate fallback")
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

    def send_candidate_event(user_id: int) -> None:
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
                raise CandidateEventPublishError("candidate update Kafka producer failed") from error
        publish_candidate_updated(producer, user_id)

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
                claims[key] = claim_partition_fence(
                    store, settings.kafka_group_id, partition.topic, partition.partition
                )
        except redis.RedisError as exc:
            LOG.error("partition fence claim failed; Consumer restart required code=%s", type(exc).__name__)
            assignment_failed = True
            return
        owned.update((partition.topic, partition.partition) for partition in partitions)
        fences.update(claims)
        client.assign(partitions)
        LOG.info(
            "Kafka partitions assigned count=%s partitions=%s",
            len(partitions),
            ",".join(f"{partition.topic}:{partition.partition}" for partition in partitions),
        )

    def on_revoke(client: Consumer, partitions: list[TopicPartition]) -> None:
        for partition in partitions:
            paused.pop((partition.topic, partition.partition), None)
            owned.discard((partition.topic, partition.partition))
            fences.pop((partition.topic, partition.partition), None)
        client.unassign()
        LOG.info(
            "Kafka partitions revoked count=%s partitions=%s",
            len(partitions),
            ",".join(f"{partition.topic}:{partition.partition}" for partition in partitions),
        )

    def pause_for_failure(message: Any, partition: TopicPartition, reason: str) -> None:
        paused[(message.topic(), message.partition())] = 0
        LOG.error(
            "%s; partition paused topic=%s partition=%s offset=%s",
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
                raise RuntimeError("partition fence claim failed; Consumer restart required")
            message = consumer.poll(settings.kafka_poll_timeout_seconds)
            if assignment_failed:
                raise RuntimeError("partition fence claim failed; Consumer restart required")
            for key in paused:
                paused[key] += 1
            if paused and max(paused.values()) >= settings.max_paused_polls:
                LOG.error("paused partition restart required partitions=%s", len(paused))
                raise RuntimeError("paused Kafka partition requires restart")
            if message is None:
                continue
            if message.error():
                LOG.warning("Kafka record error topic=%s partition=%s", message.topic(), message.partition())
                continue
            partition = TopicPartition(message.topic(), message.partition())
            try:
                event = parse_event(message.value(), message.key())
            except EventValidationError:
                # A poison record must not take down unrelated partitions and
                # must remain uncommitted for explicit operator recovery.
                pause_for_failure(message, partition, "invalid Kafka event")
                continue
            processed = process_user(
                store,
                lambda: psycopg.connect(settings.postgres_dsn),
                bundle,
                event.user_id,
                attempts=settings.retry_attempts,
                backoff_seconds=settings.retry_backoff_seconds,
                is_still_owned=lambda: (message.topic(), message.partition()) in owned,
                partition_fence=fences.get((message.topic(), message.partition())),
                content=content,
                groups=groups,
                discovery_popularity=discovery_popularity,
                hybrid_alpha=settings.hybrid_alpha,
                candidate_event_publisher=send_candidate_event,
            )
            if processed is None:
                LOG.warning(
                    "partition fence lost before commit topic=%s partition=%s",
                    message.topic(),
                    message.partition(),
                )
                continue
            if not processed:
                if (message.topic(), message.partition()) not in owned:
                    LOG.warning(
                        "partition ownership lost before commit topic=%s partition=%s",
                        message.topic(),
                        message.partition(),
                    )
                    continue
                pause_for_failure(message, partition, "processing failed")
                continue
            if (message.topic(), message.partition()) not in owned:
                LOG.warning(
                    "partition ownership lost before commit topic=%s partition=%s",
                    message.topic(),
                    message.partition(),
                )
                continue
            try:
                consumer.commit(message=message, asynchronous=False)
                LOG.info(
                    "Kafka event committed topic=%s partition=%s offset=%s",
                    message.topic(),
                    message.partition(),
                    message.offset(),
                )
            except KafkaException:
                # Publishing may already have succeeded.  Never advance this
                # partition past an unconfirmed commit; replay is intentional.
                pause_for_failure(message, partition, "Kafka commit failed")
    finally:
        consumer.close()
