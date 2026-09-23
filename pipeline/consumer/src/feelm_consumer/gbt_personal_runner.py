"""627 processing and explicit GBT entrypoint using the 742 wire contract."""

from __future__ import annotations

import argparse
import logging
import os
from dataclasses import dataclass
from uuid import uuid4

from feelm_consumer.events import EventValidationError, parse_event
from feelm_consumer.gbt.errors import GbtError
from feelm_consumer.gbt_personal import PersonalScorer
from feelm_consumer.gbt_snapshot import create_snapshot, safe_identifier
from feelm_consumer.gbt_store import GbtStore, PublicationConflict, StaleWriter
from feelm_consumer.postgres import load_user_snapshot, readonly_snapshot

LOG = logging.getLogger(__name__)
DATA_FAILURES = frozenset({"UNSUPPORTED_HISTORY", "INVALID_MOVIE_ID", "INVALID_RATING",
                           "DUPLICATE_RATING", "SNAPSHOT_TOO_LARGE"})


@dataclass(frozen=True)
class SourcePosition:
    topic: str
    partition: int
    offset: int

    def __post_init__(self):
        safe_identifier(self.topic)
        if (type(self.partition) is not int or not 0 <= self.partition < 2**31
                or type(self.offset) is not int or not 0 <= self.offset < 2**63):
            raise ValueError("invalid source position")


def process_user(store, connect, personal: PersonalScorer, user_id: int, *, fence,
                 source: SourcePosition, is_still_owned=lambda: True, publisher=None,
                 attempts: int = 3) -> str:
    """Caller ACKs PUBLISHED/QUARANTINED only; all other failures remain retryable.

    CAS is read BEFORE PostgreSQL. A losing writer must reread the database,
    never just attach an old snapshot to a new publication version.
    """
    if type(attempts) is not int or not 1 <= attempts <= 10:
        raise ValueError("attempts must be 1..10")
    for attempt in range(attempts):
        try:
            if not is_still_owned():
                return "STALE"
            expected = store.current_version(user_id, fence)
            with readonly_snapshot(connect) as connection:
                db = load_user_snapshot(connection, user_id)
            snapshot = create_snapshot(
                user_id=user_id, generation_id=str(uuid4()),
                serving_revision=personal.scorer.bundle.serving_revision,
                model_version=personal.scorer.bundle.model_version,
                ratings=[(item.movie_id, item.score) for item in db.ratings],
                dismissed_movie_ids=db.dismissed_movie_ids, watched_movie_ids=db.watched_movie_ids,
            )
            result = personal.calculate(snapshot)
            if not is_still_owned():
                return "STALE"
            store.publish(result, expected, fence)
            if not is_still_owned():
                return "STALE"
            if publisher is not None:
                # The Kafka adapter waits for broker delivery ACK here.
                publisher(result)
            return "PUBLISHED" if is_still_owned() else "STALE"
        except StaleWriter:
            return "STALE"
        except PublicationConflict:
            if attempt + 1 == attempts:
                raise
        except GbtError as exc:
            if exc.code not in DATA_FAILURES:
                raise
            if not is_still_owned():
                return "STALE"
            store.record_failure(topic=source.topic, partition=source.partition, offset=source.offset,
                                 code=exc.code, fence=fence)
            # The source offset is the replay locator; no history/IDs in the journal.
            LOG.warning("gbt input quarantined code=%s", exc.code)
            return "QUARANTINED" if is_still_owned() else "STALE"
    raise AssertionError("unreachable")


def run_consumer(consumer, store, connect, personal, *, group_id, topics, publisher,
                 poll_timeout=1.0, stopping=lambda: False):
    """Injectable real loop; a dependency/commit failure closes before a later ACK."""
    owned = {}

    def assigned(handle, partitions):
        claims = {(p.topic, p.partition): store.claim(group_id, p.topic, p.partition) for p in partitions}
        owned.update(claims)
        handle.assign(partitions)

    def revoked(handle, partitions):
        for p in partitions:
            owned.pop((p.topic, p.partition), None)
        handle.unassign()

    try:
        consumer.subscribe(topics, on_assign=assigned, on_revoke=revoked, on_lost=revoked)
        while not stopping():
            message = consumer.poll(poll_timeout)
            if message is None:
                continue
            if message.error():
                raise RuntimeError("GBT Kafka poll failed")
            key = (message.topic(), message.partition())
            fence = owned.get(key)
            if fence is None:
                continue
            def still_owned():
                return owned.get(key) == fence
            source = SourcePosition(*key, message.offset())
            try:
                event = parse_event(message.value(), message.key())
            except (EventValidationError, RecursionError):
                store.record_failure(topic=source.topic, partition=source.partition, offset=source.offset,
                                     code="INVALID_EVENT", fence=fence)
                outcome = "QUARANTINED"
            else:
                outcome = process_user(store, connect, personal, event.user_id, fence=fence, source=source,
                                       is_still_owned=still_owned, publisher=publisher)
            if outcome in {"PUBLISHED", "QUARANTINED"} and still_owned():
                consumer.commit(message=message, asynchronous=False)
            elif still_owned():
                # A model/fence change must not be skipped by committing N+1.
                raise StaleWriter("GBT ownership or model changed; restart required")
    except KeyboardInterrupt:
        pass
    finally:
        consumer.close()


def main(argv=None) -> None:
    parser = argparse.ArgumentParser(description="GBT personal consumer (742 contract; shadow by default)")
    parser.add_argument("--gbt-bundle", "--bundle", dest="bundle", required=True)
    parser.add_argument("--expected-revision", required=True)
    parser.add_argument("--group-id", default=os.environ.get("CONSUMER_KAFKA_GROUP_ID"))
    parser.add_argument("--snapshot-retention-seconds", "--snapshot-ttl-seconds",
                        dest="snapshot_ttl_seconds", required=True, type=int)
    parser.add_argument("--max-snapshot-bytes", required=True, type=int)
    parser.add_argument("--group-uri")
    parser.add_argument("--popularity-uri")
    parser.add_argument("--publish-live", action="store_true", help="explicit approved deployment only")
    args = parser.parse_args(argv)
    if not args.group_id or (not args.publish_live and not args.group_id.startswith("gbt-shadow-")):
        parser.error("group is required; shadow groups must start with gbt-shadow-")
    if bool(args.group_uri) != bool(args.popularity_uri):
        parser.error("group and popularity URIs must be provided together")

    import psycopg
    import redis
    from confluent_kafka import Consumer, Producer

    from feelm_consumer.candidate_events import publish_candidate_updated
    from feelm_consumer.discovery_popularity import load_discovery_popularity
    from feelm_consumer.gbt.artifacts import load_bundle
    from feelm_consumer.gbt.scorer import Scorer
    from feelm_consumer.group_artifacts import load_group_bundle

    # A fully validated model must exist before connections or offsets are touched.
    scorer = Scorer(load_bundle(args.bundle, expected_revision=args.expected_revision))
    groups = load_group_bundle(args.group_uri) if args.group_uri else None
    popularity = load_discovery_popularity(args.popularity_uri) if args.popularity_uri else None
    personal = PersonalScorer(scorer, groups=groups, popularity=popularity)
    config = {name: os.environ[name] for name in (
        "CONSUMER_POSTGRES_DSN", "CONSUMER_REDIS_URL", "CONSUMER_KAFKA_BOOTSTRAP_SERVERS")}
    client = redis.Redis.from_url(config["CONSUMER_REDIS_URL"])
    store = GbtStore(client, serving_revision=args.expected_revision, model_version=scorer.bundle.model_version,
                     snapshot_ttl_seconds=args.snapshot_ttl_seconds, max_snapshot_bytes=args.max_snapshot_bytes,
                     shadow=not args.publish_live)
    if not args.publish_live:
        from feelm_consumer.gbt_snapshot import canonical
        client.set(store.revision_key, canonical({"modelKind": "GBT", "modelVersion": scorer.bundle.model_version,
                                                 "servingRevision": args.expected_revision}), nx=True)
    consumer = Consumer({"bootstrap.servers": config["CONSUMER_KAFKA_BOOTSTRAP_SERVERS"],
                         "group.id": args.group_id, "enable.auto.commit": False, "auto.offset.reset": "earliest"})
    producer = None

    def notify(result):
        nonlocal producer
        if not args.publish_live:
            return  # Shadow never emits operational refresh notifications.
        if producer is None:
            producer = Producer({"bootstrap.servers": config["CONSUMER_KAFKA_BOOTSTRAP_SERVERS"],
                                 "enable.idempotence": True, "acks": "all"})
        publish_candidate_updated(producer, result.snapshot.user_id)

    topics = [os.environ.get("CONSUMER_KAFKA_TOPIC", "feelm.rating.v1"),
              os.environ.get("CONSUMER_DISMISS_KAFKA_TOPIC", "feelm.dismiss.v1")]
    if not all(topics) or len(set(topics)) != 2:
        parser.error("rating and dismiss topics must be non-empty and different")
    run_consumer(consumer, store, lambda: psycopg.connect(config["CONSUMER_POSTGRES_DSN"]), personal,
                 group_id=args.group_id, topics=topics, publisher=notify)


if __name__ == "__main__":
    main()
