"""Execute publication Lua against a disposable local Redis, never configured Redis."""

import json
import subprocess
import time
from dataclasses import replace
from uuid import uuid4

import pytest
import redis
from test_gbt_personal import scorer as scorer
from test_gbt_personal import snapshot

from feelm_consumer.gbt.errors import GbtError
from feelm_consumer.gbt_personal import PersonalScorer
from feelm_consumer.gbt_store import NAMESPACE, GbtStore, PublicationConflict, PublicationVersion, StaleWriter


def cas(store, value, fence):
    # Most storage tests set a specific numeric CAS within the current epoch.
    # Interleaving tests below capture the actual value before the competing write.
    actual = store.current_version(10, fence)
    return PublicationVersion(value, actual.epoch)


@pytest.fixture(scope="module")
def isolated_redis():
    name = "feelm-gbt627-test-" + uuid4().hex[:12]
    try:
        # Never pull an image or use an existing application's Redis connection.
        subprocess.run(["docker", "image", "inspect", "redis:8.2.9-alpine"], check=True, capture_output=True)
    except (OSError, subprocess.CalledProcessError):
        pytest.skip("requires local Docker and redis:8.2.9-alpine")
    subprocess.run(["docker", "run", "--rm", "--detach", "--name", name,
                    "--publish", "127.0.0.1::6379", "--memory", "128m", "redis:8.2.9-alpine",
                    "redis-server", "--save", "", "--appendonly", "no"], check=True, capture_output=True)
    client = None
    try:
        port = subprocess.run(["docker", "port", name, "6379"], check=True, capture_output=True, text=True)
        client = redis.Redis(host="127.0.0.1", port=int(port.stdout.strip().rsplit(":", 1)[1]))
        for _ in range(50):
            try:
                client.ping()
                break
            except redis.ConnectionError:
                time.sleep(.1)
        client.ping()
        yield client
    finally:
        if client is not None:
            client.close()
        subprocess.run(["docker", "rm", "--force", name], check=True, capture_output=True)


@pytest.fixture
def prepared(isolated_redis, scorer):
    # This client only belongs to the freshly created, uniquely named container.
    isolated_redis.flushdb()
    store = GbtStore(isolated_redis, serving_revision=scorer.bundle.serving_revision,
                        model_version=scorer.bundle.model_version,
                        snapshot_ttl_seconds=600, max_snapshot_bytes=1024**2)
    isolated_redis.set(store.revision_key, json.dumps({"modelKind": "GBT",
                       "modelVersion": scorer.bundle.model_version, "servingRevision": scorer.bundle.serving_revision}))
    fence = store.claim("gbt-shadow-test", "feelm.rating.v1", 0)
    result = PersonalScorer(scorer).calculate(snapshot(scorer, [(1, 1)]))
    return isolated_redis, store, fence, result


def test_atomic_snapshot_state_candidate_and_historical_read(prepared, scorer):
    client, store, fence, result = prepared
    assert store.current_version(10, fence) == 0
    assert store.publish(result, cas(store, 0, fence), fence) == 1
    raw_state, raw_candidates = client.mget(
        f"{NAMESPACE}:user:10:recommendation-state", f"{NAMESPACE}:user:10:candidates")
    assert json.loads(raw_state)["generationId"] == json.loads(raw_candidates)["generationId"] == "g1"
    second = PersonalScorer(scorer).calculate(snapshot(scorer, [(1, 5)], generation="g2"))
    store.publish(second, cas(store, 1, fence), fence)
    old = store.read_snapshot(10, "g1", serving_revision=result.snapshot.serving_revision,
                              model_version=result.snapshot.model_version, input_hash=result.snapshot.input_hash)
    assert old.ratings == ((1, 1.0),) and store.current_version(10, fence) == 2
    assert not list(client.scan_iter("feelm:user:*:vector"))
    assert client.ttl(f"{NAMESPACE}:user:10:input:g2") == -1
    assert 0 < client.ttl(f"{NAMESPACE}:user:10:input:g1") <= 600


@pytest.mark.parametrize("stale", ["fence", "revision", "version"])
def test_stale_writer_rejected_without_partial_snapshot(prepared, stale):
    client, store, fence, result = prepared
    if stale == "fence":
        store.claim("gbt-shadow-test", "feelm.rating.v1", 0)
    elif stale == "revision":
        client.set(store.revision_key, "sha256:" + "0" * 64)
    else:
        client.set(f"{NAMESPACE}:user:10:candidate-version", 1)
    with pytest.raises((StaleWriter, PublicationConflict)):
        store.publish(result, cas(store, 0, fence), fence)
    assert not list(client.scan_iter(f"{NAMESPACE}:user:10:input:*"))
    assert not client.exists(f"{NAMESPACE}:user:10:recommendation-state")


def test_immutable_generation_and_cross_payload_rejected(prepared, scorer):
    client, store, fence, result = prepared
    store.publish(result, cas(store, 0, fence), fence)
    changed = PersonalScorer(scorer).calculate(snapshot(scorer, [(1, 5)]))
    with pytest.raises(PublicationConflict):
        store.publish(changed, cas(store, 1, fence), fence)
    wrong = replace(result, state={**result.state, "generationId": "g2"})
    with pytest.raises(GbtError, match="PUBLICATION_GENERATION_MISMATCH"):
        store.publish(wrong, cas(store, 1, fence), fence)
    assert store.current_version(10, fence) == 1
    assert json.loads(client.get(f"{NAMESPACE}:user:10:input:g1"))["ratings"] == [[1, 1.0]]


def test_expiry_absence_and_size_never_substitute_latest(prepared):
    client, store, fence, result = prepared
    tiny = GbtStore(client, serving_revision=store.serving_revision,
                       model_version=store.model_version,
                       snapshot_ttl_seconds=600, max_snapshot_bytes=1)
    with pytest.raises(GbtError, match="SNAPSHOT_TOO_LARGE"):
        tiny.publish(result, cas(store, 0, fence), fence)
    assert store.current_version(10, fence) == 0
    store.publish(result, cas(store, 0, fence), fence)
    client.delete(f"{NAMESPACE}:user:10:input:g1")  # simulate expiry/eviction of requested generation
    with pytest.raises(GbtError, match="SNAPSHOT_MISSING"):
        store.read_snapshot(10, "g1", serving_revision=result.snapshot.serving_revision,
                            model_version=result.snapshot.model_version, input_hash=result.snapshot.input_hash)


def test_failure_journal_fenced_idempotent_and_no_history(prepared):
    client, store, fence, _ = prepared
    args = dict(topic="feelm.rating.v1", partition=0, offset=17, code="UNSUPPORTED_HISTORY", fence=fence)
    store.record_failure(**args)
    store.record_failure(**args)
    payload = json.loads(client.get(f"{NAMESPACE}:gbt-failure:feelm.rating.v1:0:17"))
    assert set(payload) == {"topic", "partition", "offset", "code", "servingRevision"}
    store.claim("gbt-shadow-test", "feelm.rating.v1", 0)
    with pytest.raises(StaleWriter):
        store.record_failure(**{**args, "offset": 18})
    assert not client.exists(f"{NAMESPACE}:gbt-failure:feelm.rating.v1:0:18")


def test_existing_wrong_type_is_rejected_before_any_writes(prepared):
    client, store, fence, result = prepared
    client.lpush(f"{NAMESPACE}:user:10:input:g1", "corrupt")
    with pytest.raises(redis.RedisError):
        store.publish(result, cas(store, 0, fence), fence)
    assert not client.exists(f"{NAMESPACE}:user:10:recommendation-state", f"{NAMESPACE}:user:10:candidates")


def test_current_snapshot_state_candidates_do_not_expire_on_replay(prepared):
    client, store, fence, result = prepared
    store.publish(result, cas(store, 0, fence), fence)
    time.sleep(.15)
    store.publish(result, cas(store, 1, fence), fence)
    keys = [f"{NAMESPACE}:user:10:{suffix}" for suffix in ("input:g1", "recommendation-state", "candidates")]
    expiries = [client.pexpiretime(key) for key in keys]
    assert expiries == [-1, -1, -1]


def test_last_input_delete_publishes_popularity_in_same_generation_and_replays(prepared, scorer):
    client, store, fence, result = prepared
    store.publish(result, cas(store, 0, fence), fence)
    cold = PersonalScorer(scorer).calculate(snapshot(scorer, [], generation="cold"))
    store.publish(cold, cas(store, 1, fence), fence)
    state = json.loads(client.get(f"{NAMESPACE}:user:10:recommendation-state"))
    assert state["supportedInputCount"] == 0 and state["modelKind"] == "GBT"
    assert state["stateVersion"] == 1 and state["inputSchemaVersion"] == "gbt-input-v1"
    candidates = json.loads(client.get(f"{NAMESPACE}:user:10:candidates"))
    assert state["status"] == "READY" and len(candidates["taste"]) == 500
    assert state["generationId"] == candidates["generationId"] == "cold"
    assert state["inputHash"] == candidates["inputHash"] == cold.snapshot.input_hash
    assert json.loads(client.get(f"{NAMESPACE}:user:10:input:cold"))["inputs"] == []
    client.delete(f"{NAMESPACE}:user:10:candidates")  # Repair a lost fallback on redelivery.
    store.publish(cold, cas(store, 2, fence), fence)
    assert json.loads(client.get(f"{NAMESPACE}:user:10:candidates")) == candidates
    assert client.ttl(f"{NAMESPACE}:user:10:candidates") == -1
    assert client.ttl(f"{NAMESPACE}:user:10:recommendation-state") == -1
    assert 0 < client.ttl(f"{NAMESPACE}:user:10:input:g1") <= 600


def test_exhausted_state_still_removes_previous_candidates(prepared, scorer):
    client, store, fence, result = prepared
    store.publish(result, cas(store, 0, fence), fence)
    empty = PersonalScorer(scorer).calculate(snapshot(scorer, [(1, 1)], watched=range(2, 1006), generation="empty"))
    store.publish(empty, cas(store, 1, fence), fence)
    assert json.loads(client.get(f"{NAMESPACE}:user:10:recommendation-state"))["status"] == "EXHAUSTED"
    assert not client.exists(f"{NAMESPACE}:user:10:candidates")


def test_model_version_is_fenced_independently_of_revision(prepared):
    client, store, fence, result = prepared
    client.set(store.revision_key, json.dumps({"modelKind": "GBT", "modelVersion": "another",
                                             "servingRevision": store.serving_revision}))
    with pytest.raises(StaleWriter):
        store.publish(result, cas(store, 0, fence), fence)
    assert not client.exists(f"{NAMESPACE}:user:10:recommendation-state")


@pytest.mark.parametrize("suffix", ["recommendation-state", "candidates", "candidate-version"])
@pytest.mark.parametrize("damage", ["missing", "json", "type"])
def test_partial_publication_repairs_without_losing_cas_or_retention(prepared, scorer, suffix, damage):
    client, store, fence, result = prepared
    store.publish(result, cas(store, 0, fence), fence)
    key = f"{NAMESPACE}:user:10:{suffix}"
    client.delete(key)
    if damage == "json":
        client.set(key, "broken")
    elif damage == "type":
        client.lpush(key, "broken")
    next_result = PersonalScorer(scorer).calculate(snapshot(scorer, [(1, 5)], generation="g2"))
    with pytest.raises(PublicationConflict):
        store.publish(next_result, cas(store, 0, fence), fence)  # A pre-loss DB snapshot cannot reset the CAS.
    assert store.current_version(10, fence) == 1
    assert store.publish(next_result, cas(store, 1, fence), fence) == 2
    assert json.loads(client.get(f"{NAMESPACE}:user:10:recommendation-state"))["generationId"] == "g2"
    assert json.loads(client.get(f"{NAMESPACE}:user:10:candidates"))["generationId"] == "g2"
    assert 0 < client.ttl(f"{NAMESPACE}:user:10:input:g1") <= 600
    assert client.ttl(f"{NAMESPACE}:user:10:input:g2") == -1


def test_all_formal_keys_lost_still_preserves_head_cas_and_retention(prepared, scorer):
    client, store, fence, result = prepared
    store.publish(result, cas(store, 0, fence), fence)
    client.delete(*(f"{NAMESPACE}:user:10:{key}" for key in
                    ("recommendation-state", "candidates", "candidate-version")))
    next_result = PersonalScorer(scorer).calculate(snapshot(scorer, [(1, 5)], generation="g2"))
    with pytest.raises(PublicationConflict):
        store.publish(next_result, cas(store, 0, fence), fence)
    store.publish(next_result, store.current_version(10, fence), fence)
    assert 0 < client.ttl(f"{NAMESPACE}:user:10:input:g1") <= 600


@pytest.mark.parametrize("remaining", ["input-current", "recommendation-state", "candidates"])
def test_missing_input_can_only_be_restored_with_original_identity(prepared, scorer, remaining):
    client, store, fence, result = prepared
    store.publish(result, cas(store, 0, fence), fence)
    client.delete(f"{NAMESPACE}:user:10:input:g1")
    for suffix in ("input-current", "recommendation-state", "candidates"):
        if suffix != remaining:
            client.delete(f"{NAMESPACE}:user:10:{suffix}")
    changed = PersonalScorer(scorer).calculate(snapshot(scorer, [(1, 5)]))
    with pytest.raises(PublicationConflict):
        store.publish(changed, cas(store, 1, fence), fence)
    assert not client.exists(f"{NAMESPACE}:user:10:input:g1")
    store.publish(result, cas(store, 1, fence), fence)
    assert json.loads(client.get(f"{NAMESPACE}:user:10:input:g1"))["ratings"] == [[1, 1.0]]


@pytest.mark.parametrize("lost", [("input-current",), ("input-current", "candidate-version")])
def test_counter_recovery_invalidates_prepared_writers_across_topics(prepared, scorer, lost):
    client, store, rating_fence, result = prepared
    store.publish(result, store.current_version(10, rating_fence), rating_fence)
    old_rating_version = store.current_version(10, rating_fence)
    dismiss_fence = store.claim("gbt-shadow-test", "feelm.dismiss.v1", 0)
    fresh = PersonalScorer(scorer).calculate(snapshot(scorer, [(1, 5)], generation="g2"))
    store.publish(fresh, store.current_version(10, dismiss_fence), dismiss_fence)
    client.delete(*(f"{NAMESPACE}:user:10:{key}" for key in lost))
    with pytest.raises(PublicationConflict):
        store.publish(result, old_rating_version, rating_fence)
    repaired = store.current_version(10, dismiss_fence)
    assert repaired.epoch != old_rating_version.epoch
    with pytest.raises(PublicationConflict):
        store.publish(result, old_rating_version, rating_fence)
    latest = PersonalScorer(scorer).calculate(snapshot(scorer, [(1, 4)], generation="g3"))
    store.publish(latest, repaired, dismiss_fence)
    assert json.loads(client.get(f"{NAMESPACE}:user:10:recommendation-state"))["generationId"] == "g3"
    assert 0 < client.ttl(f"{NAMESPACE}:user:10:input:g2") <= 600
