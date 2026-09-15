from __future__ import annotations

import json
import secrets
from collections.abc import Mapping

import redis as redis_lib


class StalePartitionFenceError(RuntimeError):
    """The Kafka partition was assigned to a newer Consumer generation."""

PUBLISH_LUA = """
local version_key = KEYS[1]
local vector_key = KEYS[2]
local candidates_key = KEYS[3]
local fence_key = KEYS[4]
local expected = tonumber(ARGV[1])
local fence_token = ARGV[2]
if not expected or expected < 0 or expected ~= math.floor(expected) then
  return {err='invalid expected version'}
end
if not fence_token or string.len(fence_token) == 0 then
  return {err='invalid partition fence'}
end
if redis.call('GET', fence_key) ~= fence_token then return -1 end
local version_type = redis.call('TYPE', version_key).ok
local vector_type = redis.call('TYPE', vector_key).ok
local candidates_type = redis.call('TYPE', candidates_key).ok
local current = version_type == 'string' and redis.call('GET', version_key) or nil
local vector = vector_type == 'string' and redis.call('GET', vector_key) or nil
local candidates = candidates_type == 'string' and redis.call('GET', candidates_key) or nil
local current_number = current and tonumber(current) or nil
local vector_json_ok, vector_json = pcall(cjson.decode, vector or '')
local candidates_json_ok, candidates_json = pcall(cjson.decode, candidates or '')
local healthy = current_number and current_number >= 1 and current_number == math.floor(current_number)
  and vector_json_ok and candidates_json_ok
  and type(vector_json) == 'table' and type(candidates_json) == 'table'
  and vector_json['candidateVersion'] == current_number and candidates_json['candidateVersion'] == current_number
if (version_type ~= 'none' or vector_type ~= 'none' or candidates_type ~= 'none') and not healthy then
  -- A version key without both results is never safe to serve.  Do the
  -- cleanup in this script so a concurrent writer cannot observe a half reset.
  redis.call('DEL', version_key, vector_key, candidates_key)
  return {err='recovery required'}
end
if current_number and current_number ~= expected then return {err='stale version'} end
if not current_number and expected ~= 0 then return {err='stale version'} end
local next_version = expected + 1
redis.call('SET', version_key, next_version)
redis.call('SET', vector_key, ARGV[3])
redis.call('SET', candidates_key, ARGV[4])
return next_version
"""

VERSION_STATE_LUA = """
local version_key = KEYS[1]
local vector_key = KEYS[2]
local candidates_key = KEYS[3]
local fence_key = KEYS[4]
local fence_token = ARGV[1]
if not fence_token or string.len(fence_token) == 0 then return {err='invalid partition fence'} end
if redis.call('GET', fence_key) ~= fence_token then return -1 end
local version_type = redis.call('TYPE', version_key).ok
local vector_type = redis.call('TYPE', vector_key).ok
local candidates_type = redis.call('TYPE', candidates_key).ok
if version_type == 'none' and vector_type == 'none' and candidates_type == 'none' then return 0 end
if version_type ~= 'string' or vector_type ~= 'string' or candidates_type ~= 'string' then
  redis.call('DEL', version_key, vector_key, candidates_key)
  return 0
end
local current = redis.call('GET', version_key)
local vector = redis.call('GET', vector_key)
local candidates = redis.call('GET', candidates_key)
local number = current and tonumber(current) or nil
local vector_json_ok, vector_json = pcall(cjson.decode, vector or '')
local candidates_json_ok, candidates_json = pcall(cjson.decode, candidates or '')
if number and number >= 1 and number == math.floor(number)
  and vector_json_ok and candidates_json_ok
  and type(vector_json) == 'table' and type(candidates_json) == 'table'
  and vector_json['candidateVersion'] == number and candidates_json['candidateVersion'] == number then return number end
redis.call('DEL', version_key, vector_key, candidates_key)
return 0
"""


def user_keys(user_id: int) -> tuple[str, str, str]:
    base = f"feelm:user:{user_id}"
    return (f"{base}:candidate-version", f"{base}:vector", f"{base}:candidates")


def partition_fence_key(kafka_group_id: str, topic: str, partition: int) -> str:
    return f"feelm:consumer-fence:{kafka_group_id}:{topic}:{partition}"


def claim_partition_fence(redis: object, kafka_group_id: str, topic: str, partition: int) -> tuple[str, str]:
    """Install a fresh, non-reusable opaque fence token for one assignment."""
    key = partition_fence_key(kafka_group_id, topic, partition)
    token = secrets.token_urlsafe(32)
    if not token:
        raise ValueError("invalid partition fence token")
    redis.set(key, token)
    return key, token


def publish(
    redis: object,
    user_id: int,
    expected_version: int,
    vector: Mapping[str, object],
    candidates: Mapping[str, object],
    partition_fence: tuple[str, str],
) -> int:
    fence_key, fence_token = partition_fence
    keys = (*user_keys(user_id), fence_key)
    try:
        result = redis.eval(
            PUBLISH_LUA,
            len(keys),
            *keys,
            expected_version,
            fence_token,
            json.dumps(vector, separators=(",", ":")),
            json.dumps(candidates, separators=(",", ":")),
        )
    except redis_lib.RedisError:
        # The server might have applied the script before the reply was lost.
        # Without a writer fence the next version cannot prove this writer
        # owns it, so leave the offset uncommitted and replay from PostgreSQL.
        raise
    next_version = int(result)
    if next_version == -1:
        raise StalePartitionFenceError("partition fence is no longer current")
    return next_version


def current_version(redis: object, user_id: int, partition_fence: tuple[str, str]) -> int:
    """Return a usable version, fencing corrupt/partial-state cleanup as well."""
    fence_key, fence_token = partition_fence
    result = redis.eval(VERSION_STATE_LUA, 4, *user_keys(user_id), fence_key, fence_token)
    version = int(result)
    if version == -1:
        raise StalePartitionFenceError("partition fence is no longer current")
    if version < 0:
        raise ValueError("invalid candidate version response")
    return version
