"""Actual Lua execution against an explicitly supplied isolated local Redis."""

import json
import os
import uuid
from dataclasses import replace

import pytest
import redis
from test_gbt_party import Reader, request_for, snapshot
from test_gbt_party_service import arguments

from feelm_consumer.gbt_party_events import canonical, request_hash, request_payload
from feelm_consumer.gbt_party_service import process_record
from feelm_consumer.gbt_party_store import PartyStore, PublishRejected


@pytest.fixture
def redis_case():
    url = os.environ.get("GBT_PARTY_TEST_REDIS_URL")
    if not url:
        pytest.skip("supply an isolated local Redis via GBT_PARTY_TEST_REDIS_URL")
    client = redis.Redis.from_url(url, decode_responses=True)
    client.ping()
    # Every case owns unique party/fence/quarantine keys; never flush an external DB.
    party_id = uuid.uuid4().int % (2**31 - 1) + 1
    member = snapshot()
    request = replace(request_for((member,)), party_id=party_id)
    request = replace(request, input_id=request_hash(request))
    fence = (f"feelm:party-consumer-fence:{uuid.uuid4().hex}", "owner-1")
    result_key = f"feelm:party:{party_id}:gbt-outcome"
    request_key = f"feelm:party:{party_id}:candidates"
    client.set(fence[0], fence[1])
    client.set("feelm:model:current", canonical({"modelKind": "GBT", "modelVersion": request.model_version,
                                               "servingRevision": request.serving_revision}))
    args = arguments((member,), store=PartyStore(client))
    args.update(raw=canonical(request_payload(request)), key=party_id.to_bytes(8, "big"), partition_fence=fence)
    args["source"] = replace(args["source"], topic="test-" + uuid.uuid4().hex)
    yield client, args, result_key, request_key
    quarantine_key = f"feelm:party-consumer:quarantine:{args['source'].topic}:0:0"
    client.delete(fence[0], result_key, request_key, quarantine_key)
    client.close()


def test_lua_idempotent_replay_and_notification_retry(redis_case):
    client, args, result_key, _ = redis_case

    def fail_notification(payload):
        raise ConnectionError("injected ACK loss")

    with pytest.raises(ConnectionError):
        process_record(**args, notify=fail_notification)
    before = client.get(result_key)
    delivered = []
    receipt = process_record(**args, notify=delivered.append)
    assert receipt.commit_allowed and receipt.disposition == "IDEMPOTENT"
    assert before == client.get(result_key) and len(delivered) == 1


def test_replay_uses_durable_success_after_snapshot_expiry_and_ack_loss(redis_case):
    client, args, result_key, _ = redis_case

    def fail_notification(payload):
        raise ConnectionError("injected ACK loss")

    with pytest.raises(ConnectionError):
        process_record(**args, notify=fail_notification)
    published = client.get(result_key)
    args["snapshot_reader"] = Reader(())
    delivered = []
    receipt = process_record(**args, notify=delivered.append)
    assert receipt.disposition == "IDEMPOTENT" and receipt.commit_allowed and receipt.result_status == "OK"
    assert client.get(result_key) == published and delivered == [json.loads(published)]


@pytest.mark.parametrize("field,value", [("serving_revision", "sha256:" + "b" * 64),
                                        ("model_version", "different-v26")])
def test_stale_loaded_model_does_not_persist_failure_or_ack(redis_case, field, value):
    client, args, result_key, _ = redis_case
    setattr(args["scorer"].bundle, field, value)
    delivered = []
    receipt = process_record(**args, notify=delivered.append)
    assert receipt.disposition == "STALE_MODEL" and not receipt.commit_allowed
    assert client.get(result_key) is None and not delivered
    assert not args["scorer"].calls


@pytest.mark.parametrize("attack,expected", [("fence", "FENCED"), ("revision", "STALE_MODEL"),
                                           ("offset", "STALE_SOURCE"), ("partition", "SOURCE_PARTITION_CHANGED"),
                                           ("same_offset", "REPLAY_CONFLICT")])
def test_lua_rejects_cross_execution_and_preserves_latest(redis_case, attack, expected):
    client, args, result_key, request_key = redis_case
    args["source"] = replace(args["source"], offset=2**53 + 4)
    assert process_record(**args).commit_allowed
    before = client.get(result_key)
    if attack == "fence":
        client.set(args["partition_fence"][0], "owner-2")
    elif attack == "revision":
        client.set("feelm:model:current", canonical({"modelKind": "GBT", "modelVersion": "different",
                                                   "servingRevision": "different"}))
    elif attack == "offset":
        args["source"] = replace(args["source"], offset=2**53 + 3)
    elif attack == "partition":
        args["source"] = replace(args["source"], partition=1)
    else:
        args["source"] = replace(args["source"], timestamp_ms=2000)
    receipt = process_record(**args)
    assert receipt.disposition == expected
    assert client.get(result_key) == before


def test_lua_accepts_strictly_newer_large_offset(redis_case):
    client, args, result_key, _ = redis_case
    args["source"] = replace(args["source"], offset=2**63 - 2)
    assert process_record(**args).disposition == "PUBLISHED"
    args["source"] = replace(args["source"], offset=2**63 - 1)
    assert process_record(**args).disposition == "PUBLISHED"
    assert json.loads(client.get(result_key))["source"]["offset"] == str(2**63 - 1)


def test_lua_quarantine_durable_idempotent_and_fenced(redis_case):
    client, args, _, _ = redis_case
    args["raw"] = b"{"
    receipt = process_record(**args)
    assert receipt.commit_allowed and receipt.disposition == "QUARANTINED"
    quarantine_key = f"feelm:party-consumer:quarantine:{args['source'].topic}:0:0"
    assert json.loads(client.get(quarantine_key))["errorCode"] == "INVALID_PARTY_JSON"
    assert process_record(**args).disposition == "QUARANTINED"
    client.set(args["partition_fence"][0], "owner-2")
    assert not process_record(**args).commit_allowed


def test_lua_corrupt_previous_result_is_not_silently_overwritten(redis_case):
    client, args, result_key, _ = redis_case
    client.set(result_key, "broken")
    receipt = process_record(**args)
    assert receipt.disposition == "CORRUPT_PREVIOUS_OUTCOME" and not receipt.commit_allowed
    assert client.get(result_key) == "broken"


@pytest.mark.parametrize("damage", ["missing", "corrupt"])
def test_guarded_replay_repairs_live_result_before_notification(redis_case, damage):
    client, args, result_key, live_key = redis_case

    def lost_ack(payload):
        raise ConnectionError("injected ACK loss")

    with pytest.raises(ConnectionError):
        process_record(**args, notify=lost_ack)
    confirmed = client.get(result_key)
    if damage == "missing":
        client.delete(live_key)
    else:
        client.set(live_key, "damaged")
    args["snapshot_reader"] = Reader(())
    delivered = []

    def notify_after_repair(payload):
        assert client.get(live_key) == confirmed
        delivered.append(payload)

    receipt = process_record(**args, notify=notify_after_repair)
    assert receipt.disposition == "IDEMPOTENT" and receipt.commit_allowed
    assert delivered == [json.loads(confirmed)]


@pytest.mark.parametrize("path", ["replay", "publish"])
def test_missing_outcome_never_allows_older_source_to_replace_live(redis_case, path):
    client, args, result_key, live_key = redis_case
    args["source"] = replace(args["source"], offset=20)
    assert process_record(**args).commit_allowed
    latest = client.get(live_key)
    client.delete(result_key)
    if path == "replay":
        args["source"] = replace(args["source"], offset=19)
        receipt = process_record(**args)
        assert receipt.disposition == "STALE_SOURCE"
    else:
        older = json.loads(latest)
        older["source"]["offset"] = "19"
        with pytest.raises(PublishRejected, match="STALE_SOURCE"):
            args["outcome_store"].publish(older, args["partition_fence"])
    assert client.get(live_key) == latest and client.get(result_key) is None


def test_same_source_replay_repairs_missing_outcome_from_live(redis_case):
    client, args, result_key, live_key = redis_case
    assert process_record(**args).commit_allowed
    confirmed = client.get(live_key)
    client.delete(result_key)
    args["snapshot_reader"] = Reader(())
    assert process_record(**args).disposition == "IDEMPOTENT"
    assert client.get(result_key) == client.get(live_key) == confirmed


@pytest.mark.parametrize("guard", ["model", "fence"])
def test_partial_result_repair_cannot_bypass_fence_or_model(redis_case, guard):
    client, args, result_key, live_key = redis_case
    assert process_record(**args).commit_allowed
    confirmed = client.get(result_key)
    client.delete(live_key)
    if guard == "fence":
        client.set(args["partition_fence"][0], "new-owner")
    else:
        client.set("feelm:model:current", canonical({"modelKind": "GBT", "modelVersion": "new-model",
                                                   "servingRevision": "new-revision"}))
    notified = []
    receipt = process_record(**args, notify=notified.append)
    assert not receipt.commit_allowed and not notified
    assert client.get(live_key) is None and client.get(result_key) == confirmed


def test_recovery_after_model_change_resumes_original_quarantine(redis_case):
    client, args, result_key, live_key = redis_case
    client.set(live_key, "previous")
    args["snapshot_reader"] = Reader(())

    def fail_recovery(user_id):
        raise ConnectionError("injected recovery ACK loss")

    args["requeue_member"] = fail_recovery
    with pytest.raises(ConnectionError):
        process_record(**args)
    journal_key = f"feelm:party-consumer:quarantine:{args['source'].topic}:0:0"
    original = client.get(journal_key)
    assert json.loads(original)["errorCode"] == "SNAPSHOT_MISSING"
    args["scorer"].bundle.serving_revision = "sha256:" + "b" * 64
    client.set("feelm:model:current", canonical({"modelKind": "GBT",
                                               "modelVersion": args["scorer"].bundle.model_version,
                                               "servingRevision": args["scorer"].bundle.serving_revision}))
    recovered = []
    args["requeue_member"] = recovered.append
    receipt = process_record(**args)
    assert receipt.commit_allowed and receipt.disposition == "QUARANTINED"
    assert receipt.result_status == "SNAPSHOT_MISSING" and recovered == [1]
    assert client.get(journal_key) == original and client.get(live_key) == "previous"
    assert client.get(result_key) is None
    assert process_record(**args).commit_allowed and recovered == [1]


def test_quarantine_error_reclassification_never_accepts_a_different_request(redis_case):
    client, args, _, _ = redis_case
    args["snapshot_reader"] = Reader(())
    assert process_record(**args).commit_allowed
    key = f"feelm:party-consumer:quarantine:{args['source'].topic}:0:0"
    original = client.get(key)
    conflicting = json.loads(original)
    conflicting["inputId"] = "sha256:" + "f" * 64
    with pytest.raises(PublishRejected, match="REPLAY_CONFLICT"):
        args["outcome_store"].quarantine(conflicting, args["partition_fence"])
    assert client.get(key) == original



def test_lua_popularity_mode_survives_ack_loss_and_snapshot_expiry(redis_case):
    client, args, result_key, request_key = redis_case
    empty = snapshot(ratings=())
    party_id = int.from_bytes(args["key"], "big")
    request = replace(request_for((empty,)), party_id=party_id)
    request = replace(request, input_id=request_hash(request))
    args.update(raw=canonical(request_payload(request)), snapshot_reader=Reader((empty,)))
    def ack_loss(payload):
        raise ConnectionError("injected ack loss")
    with pytest.raises(ConnectionError):
        process_record(**args, notify=ack_loss)
    before = client.get(result_key)
    assert json.loads(before)["recommendationMode"] == "POPULARITY"
    assert json.loads(client.get(request_key))["recommendationMode"] == "POPULARITY"
    args["snapshot_reader"] = Reader(())
    delivered = []
    receipt = process_record(**args, notify=delivered.append)
    assert receipt.commit_allowed and receipt.disposition == "IDEMPOTENT"
    assert client.get(result_key) == before
    assert delivered[0]["recommendationMode"] == "POPULARITY"
