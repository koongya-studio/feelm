"""Processing boundaries: fresh DB snapshot after CAS loss and delivery ACK."""

import json
from types import SimpleNamespace

import pytest
from test_gbt_personal import scorer as scorer

from feelm_consumer.gbt.errors import GbtError
from feelm_consumer.gbt_personal import PersonalScorer
from feelm_consumer.gbt_personal_runner import SourcePosition, process_user, run_consumer
from feelm_consumer.gbt_store import PublicationConflict, StaleWriter


class Connection:
    def __init__(self, ratings=()):
        self.ratings, self.statements, self.closed = ratings, [], False

    def execute(self, statement, params=()):
        self.statements.append(statement)

    def cursor(self):
        return self

    def fetchall(self):
        return list(self.ratings) if "FROM ratings " in self.statements[-1] else []

    def close(self):
        self.closed = True


class Store:
    def __init__(self):
        self.published, self.failures, self.version = [], [], 0

    def current_version(self, user_id, fence):
        return self.version

    def publish(self, result, expected, fence):
        assert expected == self.version
        self.version += 1
        self.published.append(result)

    def record_failure(self, **kwargs):
        self.failures.append(kwargs)


OPTIONS = {"fence": ("shadow-fence", "token"), "source": SourcePosition("feelm.rating.v1", 0, 15)}


def test_processor_reads_transaction_and_publishes_before_ack(scorer):
    store, connection = Store(), Connection([(1, 1)])
    seen = []
    result = process_user(store, lambda: connection, PersonalScorer(scorer), 10,
                          publisher=lambda value: seen.append(len(store.published)), **OPTIONS)
    assert result == "PUBLISHED" and seen == [1]
    assert connection.statements[0] == "BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY"
    assert connection.statements[-1] == "COMMIT" and connection.closed


def test_cold_notice_failure_replays_candidates_before_source_commit(scorer):
    store = Store()
    def fail_after_publish(result):
        assert store.published[-1] == result
        assert result.state["status"] == "READY" and len(result.candidates["taste"]) == 500
        raise ConnectionError("no broker ACK")
    consumer = Consumer([Message(41), Message(42)])
    with pytest.raises(ConnectionError, match="no broker ACK"):
        loop(consumer, scorer, store=store, notify=fail_after_publish, connect=Connection)
    assert not consumer.commits and consumer.closed and len(consumer.messages) == 1
    delivered = []
    retry = Consumer([Message(41), Message(42)])
    loop(retry, scorer, store=store, notify=delivered.append, connect=Connection)
    assert retry.commits == [41, 42] and retry.closed
    assert store.published[0].snapshot.input_hash == store.published[1].snapshot.input_hash
    assert store.published[0].candidates["taste"] == delivered[0].candidates["taste"]
    assert not store.failures


def test_cas_conflict_rereads_all_inputs(scorer):
    class RacingStore(Store):
        def publish(self, result, expected, fence):
            if self.version == 0:
                self.version = 1
                raise PublicationConflict()
            super().publish(result, expected, fence)
    store = RacingStore()
    connections = []
    def connect():
        connection = Connection([(1, 1)] if not connections else [(1, 5), (2, 5)])
        connections.append(connection)
        return connection
    assert process_user(store, connect, PersonalScorer(scorer), 10, **OPTIONS) == "PUBLISHED"
    assert len(connections) == 2 and store.published[0].snapshot.ratings == ((1, 5.0), (2, 5.0))


def test_data_failure_journal_then_next_user(scorer):
    store = Store()
    assert process_user(store, lambda: Connection([(99999, 1)]), PersonalScorer(scorer), 10,
                        **OPTIONS) == "QUARANTINED"
    assert store.failures[0]["code"] == "UNSUPPORTED_HISTORY"
    assert not store.published
    assert process_user(store, lambda: Connection([(1, 1)]), PersonalScorer(scorer), 11, **OPTIONS) == "PUBLISHED"


def test_no_ack_when_failure_record_or_notification_fails(scorer):
    class BrokenStore(Store):
        def record_failure(self, **kwargs):
            raise ConnectionError("offline")
    with pytest.raises(ConnectionError):
        process_user(BrokenStore(), lambda: Connection([(99999, 1)]), PersonalScorer(scorer), 10, **OPTIONS)
    def no_ack(_):
        raise ConnectionError("no broker ACK")
    store = Store()
    with pytest.raises(ConnectionError):
        process_user(store, lambda: Connection([(1, 1)]), PersonalScorer(scorer), 10, publisher=no_ack, **OPTIONS)
    assert len(store.published) == 1


def test_stale_ownership_and_model_error_never_ack(scorer):
    store = Store()
    assert process_user(store, lambda: Connection([(1, 1)]), PersonalScorer(scorer), 10,
                        is_still_owned=lambda: False, **OPTIONS) == "STALE"
    class StaleStore(Store):
        def current_version(self, user_id, fence):
            raise StaleWriter()
    assert process_user(StaleStore(), lambda: Connection(), PersonalScorer(scorer), 10, **OPTIONS) == "STALE"
    def broken(_snapshot):
        raise GbtError("NONFINITE_PREDICTION")
    personal = SimpleNamespace(scorer=scorer, calculate=broken)
    with pytest.raises(GbtError, match="NONFINITE_PREDICTION"):
        process_user(store, lambda: Connection([(1, 1)]), personal, 10, **OPTIONS)
    assert not store.failures and not store.published


def test_zero_input_publishes_popularity_without_poison(scorer):
    store = Store()
    assert process_user(store, Connection, PersonalScorer(scorer), 10, **OPTIONS) == "PUBLISHED"
    assert store.published[0].state["status"] == "READY"
    assert store.published[0].state["supportedInputCount"] == 0
    assert len(store.published[0].candidates["taste"]) == 500


def test_ownership_lost_after_notification_is_not_acknowledged(scorer):
    owned = [True]
    def notify(_):
        owned[0] = False
    assert process_user(Store(), lambda: Connection([(1, 1)]), PersonalScorer(scorer), 10,
                        publisher=notify, is_still_owned=lambda: owned[0], **OPTIONS) == "STALE"


class Message:
    def __init__(self, offset, raw=None):
        self.position = offset
        self.raw = raw if raw is not None else json.dumps({
            "eventId": "123e4567-e89b-12d3-a456-426614174000", "userId": 10,
            "eventType": "DISMISS", "occurredAt": "2026-09-23T00:00:00Z", "payload": {"movieId": 1},
        }).encode()

    def topic(self):
        return "feelm.dismiss.v1"

    def partition(self):
        return 0

    def offset(self):
        return self.position

    def value(self):
        return self.raw

    def key(self):
        return (10).to_bytes(8, "big")

    def error(self):
        return None


class Consumer:
    def __init__(self, messages, *, fail_commit=False):
        self.messages, self.commits, self.closed = list(messages), [], False
        self.fail_commit = fail_commit

    def subscribe(self, topics, *, on_assign, on_revoke, on_lost):
        self.revoked = on_revoke
        self.lost = on_lost
        on_assign(self, [SimpleNamespace(topic="feelm.dismiss.v1", partition=0)])

    def assign(self, partitions):
        pass

    def unassign(self):
        pass

    def poll(self, _timeout):
        if not self.messages:
            raise KeyboardInterrupt
        return self.messages.pop(0)

    def commit(self, *, message, asynchronous):
        assert not asynchronous
        if self.fail_commit:
            raise ConnectionError("commit ACK lost")
        self.commits.append(message.offset())

    def close(self):
        self.closed = True


def loop(consumer, scorer, *, store=None, notify=lambda _: None, connect=None):
    store = store or Store()
    store.claim = lambda *_: ("test-fence", "token")
    run_consumer(consumer, store, connect or (lambda: Connection([(1, 1)])), PersonalScorer(scorer),
                 group_id="gbt-test", topics=["feelm.dismiss.v1"], publisher=notify)
    return store


@pytest.mark.parametrize("poison", [b"invalid", b"[" * 2000 + b"0" + b"]" * 2000])
def test_loop_poison_n_durable_then_valid_n_plus_one(scorer, poison):
    consumer = Consumer([Message(41, poison), Message(42)])
    notified = []
    store = loop(consumer, scorer, notify=lambda r: notified.append(r.snapshot.user_id))
    assert consumer.commits == [41, 42] and consumer.closed
    assert store.failures[0]["offset"] == 41 and store.failures[0]["code"] == "INVALID_EVENT"
    assert notified == [10] and len(store.published) == 1


@pytest.mark.parametrize("failure", ["journal", "notify", "commit", "stale_model"])
def test_loop_does_not_skip_failed_offset_by_acknowledging_later_record(scorer, failure):
    store = Store()
    def fail(*_args, **_kwargs):
        raise ConnectionError("dependency unavailable")
    if failure == "journal":
        store.record_failure = fail
    if failure == "stale_model":
        def stale(*_args):
            raise StaleWriter()
        store.current_version = stale
    consumer = Consumer([Message(41, b"invalid" if failure == "journal" else None), Message(42)],
                        fail_commit=failure == "commit")
    with pytest.raises((ConnectionError, StaleWriter)):
        loop(consumer, scorer, store=store, notify=fail if failure == "notify" else lambda _: None)
    assert not consumer.commits and consumer.closed and len(consumer.messages) == 1


def test_loop_revocation_during_notify_prevents_commit(scorer):
    consumer = Consumer([Message(41)])
    def revoke(_result):
        consumer.lost(consumer, [SimpleNamespace(topic="feelm.dismiss.v1", partition=0)])
    loop(consumer, scorer, notify=revoke)
    assert not consumer.commits and consumer.closed


def test_zero_onboarding_event_publishes_popularity_and_notice(scorer):
    class OnboardingMessage(Message):
        def topic(self):
            return "feelm.rating.v1"

    raw = json.dumps({
        "eventId": "123e4567-e89b-12d3-a456-426614174000", "userId": 10,
        "eventType": "ONBOARDING", "occurredAt": "2026-09-26T00:00:00Z",
        "payload": {"preferenceCount": 0},
    }).encode()
    class OnboardingConsumer(Consumer):
        def subscribe(self, topics, *, on_assign, on_revoke, on_lost):
            self.revoked, self.lost = on_revoke, on_lost
            on_assign(self, [SimpleNamespace(topic="feelm.rating.v1", partition=0)])

    consumer, store, notices = OnboardingConsumer([OnboardingMessage(41, raw)]), Store(), []
    store.claim = lambda *_: ("test-fence", "token")
    run_consumer(consumer, store, Connection, PersonalScorer(scorer), group_id="gbt-test",
                 topics=["feelm.rating.v1"], publisher=notices.append)
    assert consumer.commits == [41] and consumer.closed
    assert not store.failures and len(notices) == 1
    result = store.published[0]
    assert result.snapshot.ratings == () and result.state["supportedInputCount"] == 0
    assert result.state["status"] == "READY" and len(result.candidates["taste"]) == 500
    expected = sorted(
        ((int(mid), float(scorer.features.movie[index, 0]))
         for index, mid in enumerate(scorer.features.ids) if scorer.eligible[index]),
        key=lambda item: (-item[1], item[0]),
    )[:500]
    assert [(item["movieId"], item["score"]) for item in result.candidates["taste"]] == expected
    assert notices[0] == result
