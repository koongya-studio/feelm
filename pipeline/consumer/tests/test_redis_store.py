import json
import shutil
import socket
import subprocess
import time

import pytest
import redis

from feelm_consumer.redis_store import (
    PUBLISH_LUA,
    StalePartitionFenceError,
    claim_partition_fence,
    current_version,
    partition_fence_key,
    publish,
    user_keys,
)


def test_redis_key_set_has_separate_version_key() -> None:
    assert user_keys(7) == (
        "feelm:user:7:candidate-version",
        "feelm:user:7:vector",
        "feelm:user:7:candidates",
    )


def test_partition_fence_key_is_scoped_to_consumer_group_and_partition() -> None:
    assert partition_fence_key("foldin-v1", "feelm.rating.v1", 3) == (
        "feelm:consumer-fence:foldin-v1:feelm.rating.v1:3"
    )


def test_lua_clears_partial_result_state_without_version_before_rebuild() -> None:
    assert "redis.call('TYPE', vector_key).ok" in PUBLISH_LUA
    assert "redis.call('DEL', version_key, vector_key, candidates_key)" in PUBLISH_LUA
    assert "redis.call('SET', vector_key, ARGV[3])" in PUBLISH_LUA
    assert "redis.call('SET', candidates_key, ARGV[4])" in PUBLISH_LUA
    assert "redis.call('GET', fence_key) ~= fence_token" in PUBLISH_LUA


@pytest.fixture
def real_redis() -> redis.Redis:
    if shutil.which("redis-server") is None:
        pytest.skip("redis-server is not installed")
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    process = subprocess.Popen(
        ["redis-server", "--save", "", "--appendonly", "no", "--port", str(port)],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    client = redis.Redis(host="127.0.0.1", port=port, decode_responses=True)
    try:
        for _ in range(30):
            try:
                client.ping()
                break
            except redis.ConnectionError:
                time.sleep(0.05)
        else:
            pytest.fail("test Redis did not start")
        yield client
    finally:
        process.terminate()
        process.wait(timeout=5)


def test_real_redis_corrupt_or_partial_version_is_atomically_recovered(real_redis: redis.Redis) -> None:
    version, vector, candidates = user_keys(42)
    real_redis.set(version, "not-a-number")
    real_redis.set(candidates, '{"candidateVersion":999}')

    fence = claim_partition_fence(real_redis, "group", "topic", 0)
    assert current_version(real_redis, 42, fence) == 0
    assert real_redis.mget([version, vector, candidates]) == [None, None, None]

    next_version = publish(
        real_redis,
        42,
        0,
        {"candidateVersion": 1},
        {"candidateVersion": 1},
        fence,
    )
    assert next_version == 1
    assert current_version(real_redis, 42, fence) == 1


def test_real_redis_persists_reason_arrays_with_matching_generation(real_redis: redis.Redis) -> None:
    fence = claim_partition_fence(real_redis, "group", "topic", 0)
    generation_id = "generation-1"
    vector = {"candidateVersion": 6, "generationId": generation_id, "vector": [0.1]}
    candidates = {
        "candidateVersion": 6,
        "generationId": generation_id,
        "taste": [
            {
                "movieId": 7,
                "score": 0.5,
                "reasons": [
                    {
                        "code": "SHARED_GENRE",
                        "sourceMovieIds": [3],
                        "matchedGenreCodes": ["DRAMA"],
                    }
                ],
            }
        ],
        "discovery": [
            {
                "movieId": 8,
                "score": 0.4,
                "reasons": [
                    {
                        "code": "DISCOVERY_PERSONAL_SCORE",
                        "sourceMovieIds": [3],
                        "matchedGenreCodes": ["DRAMA"],
                    }
                ],
            }
        ],
    }

    assert publish(real_redis, 46, 0, vector, candidates, fence) == 1
    assert current_version(real_redis, 46, fence) == 1
    saved = json.loads(real_redis.get(user_keys(46)[2]))
    assert saved["taste"] == [
        {
            "movieId": 7,
            "score": 0.5,
            "reasons": [
                {
                    "code": "SHARED_GENRE",
                    "sourceMovieIds": [3],
                    "matchedGenreCodes": ["DRAMA"],
                }
            ],
        }
    ]
    assert saved["discovery"] == [
        {
            "movieId": 8,
            "score": 0.4,
            "reasons": [
                {
                    "code": "DISCOVERY_PERSONAL_SCORE",
                    "sourceMovieIds": [3],
                    "matchedGenreCodes": ["DRAMA"],
                }
            ],
        }
    ]


def test_real_redis_rejects_mixed_result_payload_generation(real_redis: redis.Redis) -> None:
    fence = claim_partition_fence(real_redis, "group", "topic", 0)
    with pytest.raises(redis.ResponseError, match="inconsistent result generation"):
        publish(
            real_redis,
            47,
            0,
            {"candidateVersion": 3, "generationId": "generation-a"},
            {"candidateVersion": 3, "generationId": "generation-b"},
            fence,
        )
    assert real_redis.mget(user_keys(47)) == [None, None, None]


def test_new_partition_owner_fences_old_snapshot_before_it_can_publish(
    real_redis: redis.Redis,
) -> None:
    old_fence = claim_partition_fence(real_redis, "group", "topic", 0)
    new_fence = claim_partition_fence(real_redis, "group", "topic", 0)

    with pytest.raises(StalePartitionFenceError, match="no longer current"):
        publish(
            real_redis,
            42,
            0,
            {"candidateVersion": 1, "snapshot": "old"},
            {"candidateVersion": 1, "snapshot": "old"},
            old_fence,
        )

    assert current_version(real_redis, 42, new_fence) == 0
    assert (
        publish(
            real_redis,
            42,
            0,
            {"candidateVersion": 1, "snapshot": "new"},
            {"candidateVersion": 1, "snapshot": "new"},
            new_fence,
        )
        == 1
    )
    assert real_redis.get(user_keys(42)[2]) == ('{"candidateVersion":1,"snapshot":"new"}')


def test_real_redis_wrong_type_or_invalid_json_is_atomically_recovered(real_redis: redis.Redis) -> None:
    version, vector, candidates = user_keys(43)
    real_redis.rpush(version, "wrong-type")
    real_redis.set(vector, "not-json")
    real_redis.set(candidates, '{"candidateVersion":1}')

    fence = claim_partition_fence(real_redis, "group", "topic", 0)
    assert current_version(real_redis, 43, fence) == 0
    assert real_redis.exists(version, vector, candidates) == 0


@pytest.mark.parametrize(
    ("corrupt_version", "vector_value", "candidates_value"),
    [("not-a-number", None, '{"candidateVersion":999}'), ("1", "not-json", '{"candidateVersion":1}')],
)
def test_old_fence_cannot_recover_corrupt_or_partial_three_key_state_after_new_claim(
    real_redis: redis.Redis, corrupt_version: str, vector_value: str | None, candidates_value: str
) -> None:
    old_fence = claim_partition_fence(real_redis, "group", "topic", 0)
    version, vector, candidates = user_keys(44)
    real_redis.set(version, corrupt_version)
    if vector_value is not None:
        real_redis.set(vector, vector_value)
    real_redis.set(candidates, candidates_value)
    before = real_redis.mget([version, vector, candidates])
    claim_partition_fence(real_redis, "group", "topic", 0)
    with pytest.raises(StalePartitionFenceError, match="no longer current"):
        current_version(real_redis, 44, old_fence)
    assert real_redis.mget([version, vector, candidates]) == before


def test_fence_key_loss_does_not_reuse_old_token_or_allow_old_publish(real_redis: redis.Redis) -> None:
    old_fence = claim_partition_fence(real_redis, "group", "topic", 0)
    real_redis.delete(old_fence[0])
    new_fence = claim_partition_fence(real_redis, "group", "topic", 0)
    assert new_fence[1] != old_fence[1]
    with pytest.raises(StalePartitionFenceError, match="no longer current"):
        publish(
            real_redis,
            45,
            0,
            {"candidateVersion": 1, "snapshot": "old"},
            {"candidateVersion": 1, "snapshot": "old"},
            old_fence,
        )
    assert real_redis.mget(user_keys(45)) == [None, None, None]
    assert (
        publish(
            real_redis,
            45,
            0,
            {"candidateVersion": 1, "snapshot": "new"},
            {"candidateVersion": 1, "snapshot": "new"},
            new_fence,
        )
        == 1
    )
