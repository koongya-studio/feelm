"""747 semantic failures never block later requests; dependency failure retries."""

from dataclasses import replace

import pytest
from test_gbt_party import MODEL, REVISION, Reader, StubScorer, request_for, snapshot

from feelm_consumer.gbt.errors import GbtError
from feelm_consumer.gbt_party_events import canonical, request_payload
from feelm_consumer.gbt_party_service import RecordSource, process_record
from feelm_consumer.gbt_party_store import PublishRejected


class MemoryStore:
    def __init__(self):
        self.outcomes = []
        self.quarantined = []
        self.error = None
        self.recovered = set()

    def validate_model(self, bundle, fence):
        if self.error:
            raise self.error
        if bundle.model_version != MODEL or bundle.serving_revision != REVISION:
            raise PublishRejected("STALE_MODEL")

    def recovery_done(self, payload, fence, *, mark=False):
        identity = tuple(payload.get(name) for name in ("partyId", "inputId", "errorCode"))
        if mark:
            self.recovered.add(identity)
        return identity in self.recovered

    def replay(self, identity, fence):
        if self.error:
            raise self.error
        return next((value for value in reversed(self.outcomes)
                     if all(value.get(key) == expected for key, expected in identity.items())), None)

    def publish(self, payload, fence):
        if self.error:
            raise self.error
        self.outcomes.append(payload)
        return "PUBLISHED"

    def quarantine(self, payload, fence):
        if self.error:
            raise self.error
        self.quarantined.append(payload)
        return payload


def arguments(snapshots=None, store=None):
    snapshots = snapshots or (snapshot(),)
    request = request_for(snapshots)
    return {"scorer": StubScorer(), "snapshot_reader": Reader(snapshots),
            "outcome_store": store or MemoryStore(), "raw": canonical(request_payload(request)),
            "key": request.party_id.to_bytes(8, "big"),
            "source": RecordSource("shadow-747", 0, 0, 1000),
            "partition_fence": ("feelm:party-consumer-fence:test:topic:0", "token"),
            "requeue_member": lambda user_id: None}


def test_missing_snapshot_is_durable_failed_then_unrelated_event_continues():
    args = arguments()
    args["snapshot_reader"] = Reader(())
    receipt = process_record(**args)
    assert receipt.commit_allowed and receipt.result_status == "SNAPSHOT_MISSING"
    outcome = args["outcome_store"].quarantined[0]
    assert outcome["errorCode"] == "SNAPSHOT_MISSING" and "candidates" not in outcome
    assert not args["outcome_store"].outcomes
    later = arguments(store=args["outcome_store"])
    later["source"] = replace(later["source"], offset=1)
    receipt = process_record(**later)
    assert receipt.commit_allowed and receipt.result_status == "OK"
    assert len(args["outcome_store"].outcomes) == 1


def test_existing_output_fields_and_replay_stable_generated_at():
    args = arguments()
    notified = []
    assert process_record(**args, notify=notified.append).commit_allowed
    assert process_record(**args, notify=notified.append).commit_allowed
    first, replay = notified
    assert first == replay
    assert len(args["outcome_store"].outcomes) == 1
    assert first["algorithmVersion"] == "party-gbt-mean-fit-v1"
    assert first["generatedAt"] == "1970-01-01T00:00:01.000Z"
    assert first["candidates"][0]["memberFit"] == [{"userId": 1, "score": 0.5}]


@pytest.mark.parametrize("code", ["UNSUPPORTED_HISTORY", "UNSUPPORTED_CANDIDATE", "INVALID_SNAPSHOT",
                                  "NONFINITE_PREDICTION"])
def test_semantic_error_persists_only_safe_code(code):
    args = arguments()

    def fail(*a, **kw):
        raise GbtError(code, sensitive_history="never serialized")

    args["scorer"].score = fail
    receipt = process_record(**args)
    assert receipt.commit_allowed and receipt.result_status == code
    outcome = args["outcome_store"].quarantined[0]
    assert outcome["errorCode"] == code
    assert "sensitive" not in canonical(outcome) and "ratings" not in outcome


@pytest.mark.parametrize("kind", ["malformed", "hash", "key"])
def test_invalid_request_quarantine_precedes_ack(kind):
    args = arguments()
    if kind == "malformed":
        args["raw"] = b'{"rawSecret":"never persist"'
    elif kind == "hash":
        args["raw"] = args["raw"].replace('"partyId":19', '"partyId":20')
    else:
        args["key"] = b"19"
    notified = []
    receipt = process_record(**args, notify=notified.append)
    assert receipt.commit_allowed and receipt.disposition == "QUARANTINED"
    assert not notified and len(args["outcome_store"].quarantined) == 1
    assert not args["outcome_store"].outcomes
    assert "rawSecret" not in canonical(args["outcome_store"].quarantined[0])


@pytest.mark.parametrize("stage", ["snapshot", "store", "notify", "quarantine"])
def test_dependency_failure_is_not_acknowledged(stage):
    args = arguments()

    def fail(*a, **kw):
        raise ConnectionError("dependency unavailable")

    if stage == "snapshot":
        args["snapshot_reader"].read_snapshot = fail
    elif stage == "store":
        args["outcome_store"].error = ConnectionError("store unavailable")
    elif stage == "quarantine":
        args["raw"] = b"{"
        args["outcome_store"].error = ConnectionError("store unavailable")
    else:
        args["notify"] = fail
    with pytest.raises(ConnectionError):
        process_record(**args)
    if stage == "notify":
        assert len(args["outcome_store"].outcomes) == 1
        args["notify"] = lambda payload: None
        assert process_record(**args).commit_allowed


@pytest.mark.parametrize("status", ["NEEDS_COLD_START", "EXHAUSTED"])
def test_explicit_empty_status_is_durable_and_acknowledgeable(status):
    member = snapshot(ratings=()) if status == "NEEDS_COLD_START" else snapshot(watched=(10, 11, 12))
    args = arguments((member,))
    receipt = process_record(**args)
    assert receipt.commit_allowed and receipt.result_status == status
    assert args["outcome_store"].quarantined[0]["errorCode"] == status
    assert not args["outcome_store"].outcomes


@pytest.mark.parametrize("code,ack", [("FENCED", False), ("STALE_MODEL", False),
                                    ("STALE_SOURCE", True), ("REPLAY_CONFLICT", False)])
def test_fenced_and_superseded_receipts(code, ack):
    args = arguments()
    args["outcome_store"].error = PublishRejected(code)
    receipt = process_record(**args)
    assert receipt.commit_allowed == ack and receipt.disposition == code


@pytest.mark.parametrize("lose_at", [0, 1, 2, 3])
def test_ownership_loss_never_allows_commit(lose_at):
    args = arguments()
    checks = iter([True] * lose_at + [False])
    receipt = process_record(**args, is_still_owned=lambda: next(checks))
    assert not receipt.commit_allowed and receipt.disposition == "FENCED"
    assert bool(args["outcome_store"].outcomes) == (lose_at >= 2)


@pytest.mark.parametrize("field,value", [("topic", "escape:path"), ("partition", True), ("offset", -1),
                                        ("offset", 2**63), ("timestamp_ms", float("nan")),
                                        ("timestamp_ms", 253402300800000)])
def test_source_metadata_boundaries(field, value):
    with pytest.raises(GbtError, match="INVALID_PARTY_SOURCE"):
        replace(RecordSource("test", 0, 1, 1000), **{field: value})
