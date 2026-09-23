"""742 wire/transport integration: actual Redis Lua, existing Kafka ACK adapters."""

import json
import shutil
import subprocess
import sys
from dataclasses import replace
from pathlib import Path

import pytest
from test_gbt_bundle import bundle, source  # noqa: F401
from test_gbt_party import Reader, StubScorer, request_for, snapshot
from test_gbt_party_store import redis_case  # noqa: F401
from test_party_runner import FakeMessage, FakePartition, StoppingConsumer, settings

from feelm_consumer import party_runner
from feelm_consumer.gbt.artifacts import load_bundle
from feelm_consumer.gbt.scorer import Scorer
from feelm_consumer.gbt_party_events import canonical, parse_request, request_hash, request_payload
from feelm_consumer.gbt_party_runner import GbtPartyRuntime, RedisSnapshotReader
from feelm_consumer.gbt_party_service import process_record
from feelm_consumer.party_redis_store import StalePartyModelError, publish_party
from feelm_consumer.party_settings import load_party_settings


class Producer:
    def __init__(self, *, fail=False):
        self.messages = []
        self.fail = fail

    def produce(self, topic, *, key, value, on_delivery):
        self.messages.append((topic, key, json.loads(value)))
        on_delivery(None, None)

    def flush(self, timeout):
        return int(self.fail)


def run_messages(monkeypatch, client, messages, *, scorer=None, reader=None, producer=None):
    class ScopedConsumer(StoppingConsumer):
        def subscribe(self, _topics, **callbacks):
            self.callbacks = callbacks
            callbacks["on_assign"](self, [FakePartition(messages[0].topic(), p) for p in (0, 1)])

    consumer = ScopedConsumer(messages)
    producer = producer or Producer()
    monkeypatch.setattr(party_runner, "Consumer", lambda _: consumer)
    monkeypatch.setattr(party_runner, "Producer", lambda _: producer)
    monkeypatch.setattr(party_runner, "TopicPartition", FakePartition)
    monkeypatch.setattr(party_runner.signal, "signal", lambda *_: None)
    monkeypatch.setattr(party_runner, "load_bundle", lambda *_: pytest.fail("GBT must never load ALS artifacts"))
    runtime = GbtPartyRuntime(client, scorer or StubScorer(), reader or RedisSnapshotReader(client, 10**7))
    with pytest.raises(SystemExit):
        party_runner.run(settings(), gbt_runtime=runtime)
    return consumer, producer


def put_snapshot(client, value):
    client.set(f"feelm:user:{value.user_id}:input:{value.generation_id}", canonical(value.to_payload()))


def message(args, *, offset=0, raw=None):
    value = FakeMessage(0, offset, (raw or args["raw"]).encode(), key=args["key"])
    value.topic = lambda: args["source"].topic
    return value


def test_actual_java_input_id_parity_including_utf8_lengths(tmp_path):
    javac, java = shutil.which("javac"), shutil.which("java")
    if not javac or not java:
        pytest.skip("actual Java parity requires JDK; Python boundary tests still run")
    request = request_for((snapshot(), snapshot(2, ratings=())))
    request = replace(request, serving_revision="모델-r1", members=(
        replace(request.members[0], generation_id="세대-1"), request.members[1]))
    root = Path(__file__).parents[3]
    models = root / "backend/src/main/java/com/feelm/backend/domain/party/model"
    members = ",".join("PartyInputSnapshot.Member.gbt(" + str(m.user_id) + "L,"
                       + ",".join(json.dumps(value, ensure_ascii=False) for value in
                                  (m.generation_id, m.input_schema_version, m.input_hash))
                       + ",List.of(10L))" for m in request.members)
    probe = tmp_path / "Probe.java"
    probe.write_text("import java.util.List; import com.feelm.backend.domain.party.model.*; "
                     "class Probe { public static void main(String[] args) { "
                     f"var s = new PartyInputSnapshot(2,{json.dumps(request.model_version)},"
                     f"{json.dumps(request.serving_revision, ensure_ascii=False)},List.of({members}));"
                     f"System.out.print(PartyInputId.from({request.party_id}L,s)); }} }}", encoding="utf-8")
    subprocess.run([javac, "-encoding", "UTF-8", "-d", str(tmp_path), str(models / "PartyInputId.java"),
                    str(models / "PartyInputSnapshot.java"), str(probe)], check=True, capture_output=True)
    actual = subprocess.run([java, "-cp", str(tmp_path), "Probe"], check=True, capture_output=True, text=True)
    assert actual.stdout == request_hash(request)


def test_schema_failures_keep_result_and_next_record_in_same_partition_commits(redis_case, monkeypatch):  # noqa: F811
    client, args, _, live_key = redis_case
    client.set(live_key, "previous-result")
    original = parse_request(args["raw"], args["key"])
    broken = replace(original, members=(replace(original.members[0], input_schema_version="unknown-schema"),))
    broken = replace(broken, input_id=request_hash(broken))
    put_snapshot(client, snapshot())
    consumer, producer = run_messages(monkeypatch, client, [
        message(args, raw=canonical(request_payload(broken))), message(args, offset=1)])
    assert [m.offset() for m in consumer.committed] == [0, 1] and not consumer.paused
    assert [m[0] for m in producer.messages] == ["feelm.candidate-updated.v1", "feelm.party-candidate-updated.v1"]
    failure = json.loads(client.get(f"feelm:party-consumer:quarantine:{args['source'].topic}:0:0"))
    assert failure["errorCode"] == "UNSUPPORTED_PARTY_INPUT_SCHEMA"
    assert json.loads(client.get(live_key))["status"] == "OK"


def test_late_v1_keeps_gbt_result_and_requeues_once_per_input(redis_case, monkeypatch):  # noqa: F811
    client, args, _, live_key = redis_case
    client.set(live_key, "gbt-result")
    party_id = int.from_bytes(args["key"], "big")
    legacy = canonical({"partyId": party_id, "inputId": "sha256:" + "c" * 64,
                        "members": [{"userId": 1, "vector": [], "candidateMovieIds": [10]}]})
    consumer, producer = run_messages(monkeypatch, client, [
        message(args, offset=5, raw=legacy), message(args, offset=6, raw=legacy)])
    assert [m.offset() for m in consumer.committed] == [5, 6] and not consumer.paused
    assert client.get(live_key) == "gbt-result"
    assert [m[0] for m in producer.messages] == ["feelm.candidate-updated.v1"]


def test_recovery_ack_loss_holds_offset_and_replay_recovers(redis_case, monkeypatch):  # noqa: F811
    client, args, _, live_key = redis_case
    client.set(live_key, "previous")
    missing = parse_request(args["raw"], args["key"])
    missing = replace(missing, members=(replace(missing.members[0], generation_id="missing"),))
    missing = replace(missing, input_id=request_hash(missing))
    event = message(args, offset=11, raw=canonical(request_payload(missing)))
    failed, _ = run_messages(monkeypatch, client, [event], producer=Producer(fail=True))
    assert not failed.committed and failed.paused and client.get(live_key) == "previous"
    recovered, sent = run_messages(monkeypatch, client, [event])
    assert recovered.committed == [event] and not recovered.paused
    assert [m[0] for m in sent.messages] == ["feelm.candidate-updated.v1"]
    repeated, sent_again = run_messages(monkeypatch, client, [event])
    assert repeated.committed == [event] and not sent_again.messages


def test_success_ack_loss_replays_without_snapshot_and_never_loads_als(redis_case, monkeypatch):  # noqa: F811
    client, args, _, live_key = redis_case
    put_snapshot(client, snapshot())
    event = message(args, offset=15)
    first, _ = run_messages(monkeypatch, client, [event], producer=Producer(fail=True))
    assert not first.committed and first.paused
    previous = client.get(live_key)
    client.delete("feelm:user:1:input:gen-1")
    second, producer = run_messages(monkeypatch, client, [event])
    assert second.committed == [event] and client.get(live_key) == previous
    assert [m[0] for m in producer.messages] == ["feelm.party-candidate-updated.v1"]


def test_malformed_payload_is_durable_and_does_not_pause_following_record(redis_case, monkeypatch):  # noqa: F811
    client, args, _, _ = redis_case
    put_snapshot(client, snapshot())
    consumer, producer = run_messages(monkeypatch, client, [message(args, offset=20, raw="{"),
                                                           message(args, offset=21)])
    assert [m.offset() for m in consumer.committed] == [20, 21] and not consumer.paused
    assert [m[0] for m in producer.messages] == ["feelm.party-candidate-updated.v1"]


def test_actual_gbt_snapshot_to_official_party_result(redis_case, bundle):  # noqa: F811
    client, args, _, live_key = redis_case
    root, revision = bundle
    scorer = Scorer(load_bundle(root, expected_revision=revision))
    value = snapshot(ratings=(), dismiss=(1,), revision=revision)
    request = replace(request_for((value,)), party_id=int.from_bytes(args["key"], "big"))
    request = replace(request, input_id=request_hash(request))
    put_snapshot(client, value)
    client.set("feelm:model:current", canonical({"modelKind": "GBT", "modelVersion": value.model_version,
                                               "servingRevision": revision}))
    args.update(scorer=scorer, snapshot_reader=RedisSnapshotReader(client, 10**7),
                raw=canonical(request_payload(request)))
    delivered = []
    receipt = process_record(**args, notify=delivered.append)
    assert receipt.commit_allowed and receipt.result_status == "OK"
    actual = json.loads(client.get(live_key))
    assert actual["inputId"] == request_hash(request) and actual["servingRevision"] == revision
    assert actual["activeMemberIds"] == [1] and len(actual["candidates"]) == 3
    assert delivered == [actual]


def test_legacy_lua_cannot_overwrite_gbt_even_when_model_version_matches(redis_case):  # noqa: F811
    client, args, _, live_key = redis_case
    client.set(live_key, "gbt-result")
    request = parse_request(args["raw"], args["key"])
    legacy = {"partyId": request.party_id, "modelVersion": request.model_version, "candidates": [{"movieId": 10}]}
    with pytest.raises(StalePartyModelError):
        publish_party(client, request.party_id, request.model_version, legacy, args["partition_fence"])
    assert client.get(live_key) == "gbt-result"


def test_snapshot_hash_failure_preserves_previous_candidate(redis_case):  # noqa: F811
    client, args, _, live_key = redis_case
    client.set(live_key, "previous")
    args["snapshot_reader"] = Reader((snapshot(ratings=((1, 1.0),)),))
    recovered = []
    args["requeue_member"] = recovered.append
    receipt = process_record(**args)
    assert receipt.commit_allowed and receipt.disposition == "QUARANTINED"
    assert client.get(live_key) == "previous" and recovered == [1]


def test_gbt_settings_need_only_existing_transport_environment():
    env = {"PARTY_CONSUMER_KAFKA_BOOTSTRAP_SERVERS": "broker",
           "PARTY_CONSUMER_KAFKA_GROUP_ID": "party", "PARTY_CONSUMER_REDIS_URL": "redis://example"}
    configured = load_party_settings(env, gbt=True)
    assert configured.model_uri == configured.mapping_uri == configured.metadata_uri == ""


def test_gbt_cli_validates_real_bundle_and_dispatches_without_als_load(bundle, monkeypatch):  # noqa: F811
    from feelm_consumer import artifacts, gbt_party_runner, party_settings
    root, revision = bundle
    dispatched = []
    monkeypatch.setattr(party_settings, "load_party_settings", lambda **kwargs: settings())
    monkeypatch.setattr(artifacts, "load_bundle", lambda *args: pytest.fail("leftover ALS URI must not be loaded"))
    monkeypatch.setattr(gbt_party_runner.redis.Redis, "from_url", lambda *args, **kwargs: object())
    monkeypatch.setattr(party_runner, "run", lambda settings, **kwargs: dispatched.append(kwargs["gbt_runtime"]))
    gbt_party_runner.main(["--gbt-bundle", str(root), "--expected-revision", revision,
                           "--max-snapshot-bytes", "1048576"])
    assert len(dispatched) == 1 and dispatched[0].scorer.bundle.serving_revision == revision
    assert dispatched[0].legacy_bundle is None


def test_snapshot_size_error_is_quarantined_without_partial_publish(redis_case):  # noqa: F811
    client, args, _, live_key = redis_case
    client.set(live_key, "previous")
    put_snapshot(client, snapshot())
    args["snapshot_reader"] = RedisSnapshotReader(client, 1)
    receipt = process_record(**args)
    assert receipt.commit_allowed and receipt.result_status == "SNAPSHOT_TOO_LARGE"
    assert client.get(live_key) == "previous"


def test_party_main_dispatches_equals_form_without_loading_legacy_settings(monkeypatch):
    from feelm_consumer import gbt_party_runner, party_main
    called = []
    monkeypatch.setattr(sys, "argv", ["party_main", "--gbt-bundle=immutable-v26"])
    monkeypatch.setattr(gbt_party_runner, "main", lambda: called.append("gbt"))
    monkeypatch.setattr(party_main, "load_party_settings", lambda: pytest.fail("legacy settings must not load"))
    party_main.main()
    assert called == ["gbt"]
