"""829 three-field wire through immutable input validation and real Redis recovery."""

import json
from dataclasses import replace
from pathlib import Path

import pytest
from test_gbt_party import Reader, request_for, snapshot
from test_gbt_party_742 import message, put_snapshot, run_messages
from test_gbt_party_store import redis_case  # noqa: F401

from feelm_consumer.gbt.errors import GbtError
from feelm_consumer.gbt_party import score_party
from feelm_consumer.gbt_party_events import (
    canonical,
    parse_versioned_request,
    request_hash,
    request_payload,
    validate_request,
)
from feelm_consumer.gbt_party_runner import RedisSnapshotReader
from feelm_consumer.gbt_party_service import process_record


def compact(request):
    return {k: v for k, v in request_payload(request).items() if k in {"partyId", "inputId", "members"}}


def configure(client, args, snapshots=None, *, old_wire=False):
    snapshots = snapshots or (snapshot(),)
    request = replace(request_for(snapshots), party_id=int.from_bytes(args["key"], "big"))
    request = replace(request, input_id=request_hash(request))
    for value in snapshots:
        put_snapshot(client, value)
    args["raw"] = canonical(request_payload(request) if old_wire else compact(request))
    args["snapshot_reader"] = RedisSnapshotReader(client, 10**7)
    return request


def test_shared_backend_fixture_parses_as_unresolved_gbt_and_preserves_java_digest():
    body = (Path(__file__).parent / "fixtures/party-refresh-gbt-reference.json").read_bytes()
    request = parse_versioned_request(body, (9001).to_bytes(8, "big"))
    assert request.schema_version == 2
    assert request.model_version is request.serving_revision is None
    assert request.members[0].generation_id == "g1"
    with pytest.raises(GbtError, match="UNRESOLVED_PARTY_MODEL"):
        validate_request(request)
    resolved = replace(request, model_version="gbt-v26", serving_revision="serving-r1")
    validate_request(resolved)
    assert request_hash(resolved) == request.input_id


@pytest.mark.parametrize("mutation", [
    lambda p: p.update(modelVersion="guessed-model"),
    lambda p: p.update(servingRevision="guessed-revision"),
    lambda p: p.update(schemaVersion=2),
    lambda p: p.update(extra=True),
    lambda p: p["members"][0].update(extra=True),
    lambda p: p["members"][0].update(vector=[]),
    lambda p: p["members"][0].pop("inputHash"),
    lambda p: p["members"].append({"userId": 2, "vector": [], "candidateMovieIds": [10]}),
])
def test_compact_rejects_partial_metadata_mixed_members_and_extra_fields(mutation):
    request = request_for((snapshot(),))
    body = compact(request)
    mutation(body)
    with pytest.raises(GbtError):
        parse_versioned_request(canonical(body), request.party_id.to_bytes(8, "big"))


def test_duplicate_compact_fields_are_rejected_before_use():
    request = request_for((snapshot(),))
    raw = canonical(compact(request)).replace('"partyId":19', '"partyId":19,"partyId":19')
    with pytest.raises(GbtError, match="DUPLICATE_PARTY_FIELD"):
        parse_versioned_request(raw, request.party_id.to_bytes(8, "big"))


@pytest.mark.parametrize("members", [1, 2, 3, 4])
@pytest.mark.parametrize("old_wire", [False, True])
def test_real_redis_accepts_both_wire_shapes_with_same_scoring(redis_case, members, old_wire):  # noqa: F811
    client, args, outcome_key, live_key = redis_case
    snapshots = tuple(snapshot(uid, ratings=() if uid % 2 == 0 else ((uid, .5),), watched=(8,))
                      for uid in range(1, members + 1))
    request = configure(client, args, snapshots, old_wire=old_wire)
    expected = score_party(args["scorer"], request, snapshots)
    delivered = []
    receipt = process_record(**args, notify=delivered.append)
    value = json.loads(client.get(live_key))
    assert receipt.disposition == "PUBLISHED" and receipt.commit_allowed
    assert value["candidates"] == [c.to_payload() for c in expected.candidates]
    assert value["activeMemberIds"] == [uid for uid in range(1, members + 1) if uid % 2]
    assert value["modelVersion"] == request.model_version
    assert value["servingRevision"] == request.serving_revision
    assert value["inputId"] == request.input_id
    assert client.get(outcome_key) == client.get(live_key) and delivered == [value]


def test_compact_uses_requested_generation_once_not_latest(redis_case):  # noqa: F811
    client, args, _, live_key = redis_case
    request = configure(client, args)
    newer = replace(snapshot(), generation_id="newer", ratings=((2, 5.0),))
    put_snapshot(client, newer)
    reader = args["snapshot_reader"]
    calls = []

    class Observed:
        def read_snapshot(self, user_id, generation_id, **kwargs):
            calls.append((user_id, generation_id))
            return reader.read_snapshot(user_id, generation_id, **kwargs)

    args["snapshot_reader"] = Observed()
    assert process_record(**args).commit_allowed
    assert calls == [(1, "gen-1")]
    assert args["scorer"].calls[0][0] == ((1, 4.0),)
    assert json.loads(client.get(live_key))["inputId"] == request.input_id


@pytest.mark.parametrize("field,value", [("model_version", "different-v26"),
                                        ("serving_revision", "other-revision")])
def test_compact_rejects_cross_member_model_mismatch(redis_case, field, value):  # noqa: F811
    client, args, _, live_key = redis_case
    configure(client, args, (snapshot(), replace(snapshot(2), **{field: value})))
    client.set(live_key, "previous")
    receipt = process_record(**args)
    assert receipt.commit_allowed and receipt.result_status == "PARTY_SNAPSHOT_MODEL_MISMATCH"
    assert client.get(live_key) == "previous" and not args["scorer"].calls


def test_compact_snapshot_model_must_match_loaded_model(redis_case):  # noqa: F811
    client, args, _, live_key = redis_case
    configure(client, args, (replace(snapshot(), model_version="old-v26"),))
    client.set(live_key, "previous")
    receipt = process_record(**args)
    assert receipt.result_status == "STALE_REQUEST_MODEL" and receipt.commit_allowed
    assert client.get(live_key) == "previous" and not args["scorer"].calls


@pytest.mark.parametrize("mutation,code", [
    (lambda p: p.update(inputId="sha256:" + "f" * 64), "PARTY_INPUT_HASH_MISMATCH"),
    (lambda p: p["members"][0].update(inputHash="sha256:" + "f" * 64), "SNAPSHOT_REFERENCE_MISMATCH"),
    (lambda p: p["members"][0].update(generationId="not-published"), "SNAPSHOT_MISSING"),
    (lambda p: p["members"][0].update(inputSchemaVersion="unknown"), "UNSUPPORTED_PARTY_INPUT_SCHEMA"),
])
def test_compact_invalid_reference_preserves_result_and_next_request_runs(redis_case, mutation, code):  # noqa: F811
    client, args, _, live_key = redis_case
    configure(client, args)
    valid = args["raw"]
    body = json.loads(valid)
    mutation(body)
    args["raw"] = canonical(body)
    client.set(live_key, "previous")
    receipt = process_record(**args)
    assert receipt.result_status == code and receipt.commit_allowed
    assert client.get(live_key) == "previous"
    args.update(raw=valid, source=replace(args["source"], offset=1))
    assert process_record(**args).disposition == "PUBLISHED"


@pytest.mark.parametrize("lost_side", ["none", "live", "damaged-live", "outcome"])
def test_compact_success_replay_after_snapshot_expiry_and_partial_loss(redis_case, lost_side):  # noqa: F811
    client, args, outcome_key, live_key = redis_case
    configure(client, args)

    def lost_ack(_payload):
        raise ConnectionError("injected ACK loss")

    with pytest.raises(ConnectionError):
        process_record(**args, notify=lost_ack)
    before = client.get(outcome_key)
    score_calls = len(args["scorer"].calls)
    client.delete("feelm:user:1:input:gen-1")
    if lost_side == "outcome":
        client.delete(outcome_key)
    elif lost_side == "live":
        client.delete(live_key)
    elif lost_side == "damaged-live":
        client.set(live_key, "broken")
    delivered = []
    receipt = process_record(**args, notify=delivered.append)
    assert receipt.disposition == "IDEMPOTENT" and receipt.commit_allowed
    assert client.get(live_key) == client.get(outcome_key) == before
    assert delivered == [json.loads(before)] and len(args["scorer"].calls) == score_calls


@pytest.mark.parametrize("field,value", [("generationId", "tampered"), ("inputHash", "sha256:" + "f" * 64)])
def test_replay_validates_compact_member_digest_before_any_repair(redis_case, field, value):  # noqa: F811
    client, args, outcome_key, live_key = redis_case
    configure(client, args)
    assert process_record(**args).commit_allowed
    before = client.get(outcome_key)
    client.delete(live_key, "feelm:user:1:input:gen-1")
    body = json.loads(args["raw"])
    body["members"][0][field] = value
    args["raw"] = canonical(body)
    delivered = []
    receipt = process_record(**args, notify=delivered.append)
    assert receipt.result_status == "PARTY_INPUT_HASH_MISMATCH" and not delivered
    assert client.get(outcome_key) == before and client.get(live_key) is None


@pytest.mark.parametrize("guard", ["model", "fence"])
def test_model_or_fence_change_between_metadata_read_and_repair_is_blocked(redis_case, guard):  # noqa: F811
    client, args, outcome_key, live_key = redis_case
    configure(client, args)
    assert process_record(**args).commit_allowed
    before = client.get(outcome_key)
    client.delete(live_key, "feelm:user:1:input:gen-1")
    store = args["outcome_store"]
    original = store.replay_metadata

    def read_then_change(identity, fence):
        result = original(identity, fence)
        if guard == "model":
            client.set("feelm:model:current", canonical({"modelKind": "GBT", "modelVersion": "new-v26",
                                                       "servingRevision": "new-revision"}))
        else:
            client.set(fence[0], "new-owner")
        return result

    store.replay_metadata = read_then_change
    delivered = []
    receipt = process_record(**args, notify=delivered.append)
    assert not receipt.commit_allowed and not delivered
    assert receipt.disposition == ("STALE_MODEL" if guard == "model" else "FENCED")
    assert client.get(live_key) is None and client.get(outcome_key) == before


def test_compact_older_source_is_drained_without_snapshot_read_or_repair(redis_case):  # noqa: F811
    client, args, outcome_key, live_key = redis_case
    configure(client, args)
    args["source"] = replace(args["source"], offset=20)
    assert process_record(**args).commit_allowed
    before = client.get(live_key)
    client.delete(outcome_key, "feelm:user:1:input:gen-1")
    args["source"] = replace(args["source"], offset=19)
    receipt = process_record(**args)
    assert receipt.disposition == "STALE_SOURCE" and receipt.commit_allowed
    assert client.get(live_key) == before and client.get(outcome_key) is None


@pytest.mark.parametrize("change", ["model", "snapshot"])
def test_compact_recovery_resumes_original_identity_after_context_changes(redis_case, change):  # noqa: F811
    client, args, _, live_key = redis_case
    configure(client, args)
    client.delete("feelm:user:1:input:gen-1")
    client.set(live_key, "previous")

    def lost_ack(_user_id):
        raise ConnectionError("injected recovery ACK loss")

    args["requeue_member"] = lost_ack
    with pytest.raises(ConnectionError):
        process_record(**args)
    journal = f"feelm:party-consumer:quarantine:{args['source'].topic}:0:0"
    before = client.get(journal)
    assert json.loads(before)["modelVersion"] is None
    if change == "model":
        args["scorer"].bundle.serving_revision = "new-revision"
        client.set("feelm:model:current", canonical({"modelKind": "GBT",
                                                   "modelVersion": args["scorer"].bundle.model_version,
                                                   "servingRevision": "new-revision"}))
    else:
        put_snapshot(client, replace(snapshot(), ratings=((1, .5),)))
    recovered = []
    args["requeue_member"] = recovered.append
    receipt = process_record(**args)
    assert receipt.commit_allowed and receipt.result_status == "SNAPSHOT_MISSING"
    assert client.get(journal) == before and client.get(live_key) == "previous"
    assert recovered == [1]
    assert process_record(**args).commit_allowed and recovered == [1]


def test_runner_commits_compact_failure_then_normal_success(redis_case, monkeypatch):  # noqa: F811
    client, args, _, live_key = redis_case
    configure(client, args)
    broken = json.loads(args["raw"])
    broken["members"][0]["inputSchemaVersion"] = "unsupported"
    consumer, producer = run_messages(monkeypatch, client, [
        message(args, raw=canonical(broken)), message(args, offset=1)])
    assert [m.offset() for m in consumer.committed] == [0, 1] and not consumer.paused
    assert [m[0] for m in producer.messages] == ["feelm.candidate-updated.v1", "feelm.party-candidate-updated.v1"]
    assert json.loads(client.get(live_key))["status"] == "OK"


def test_snapshot_io_outage_does_not_turn_compact_request_into_terminal_failure(redis_case):  # noqa: F811
    client, args, _, live_key = redis_case
    configure(client, args)

    class Outage(Reader):
        def read_snapshot(self, *args, **kwargs):
            raise ConnectionError("snapshot dependency outage")

    args["snapshot_reader"] = Outage(())
    with pytest.raises(ConnectionError):
        process_record(**args)
    assert client.get(live_key) is None
    assert client.get(f"feelm:party-consumer:quarantine:{args['source'].topic}:0:0") is None
