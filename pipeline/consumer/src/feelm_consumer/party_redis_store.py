"""Fenced atomic publication of a complete party candidate snapshot."""

from __future__ import annotations

import json
import secrets
from collections.abc import Mapping

import redis as redis_lib


class StalePartyPartitionFenceError(RuntimeError):
    pass


class StalePartyModelError(RuntimeError):
    pass


PUBLISH_PARTY_LUA = """
local result_key = KEYS[1]
local model_key = KEYS[2]
local fence_key = KEYS[3]
local expected_model = ARGV[1]
local fence_token = ARGV[2]
local payload_text = ARGV[3]
if redis.call('GET', fence_key) ~= fence_token then return -1 end
local model_ok, model = pcall(cjson.decode, redis.call('GET', model_key) or '')
if not model_ok or type(model) ~= 'table' or model['modelVersion'] ~= expected_model then return -2 end
local payload_ok, payload = pcall(cjson.decode, payload_text)
if not payload_ok or type(payload) ~= 'table'
  or payload['modelVersion'] ~= expected_model
  or type(payload['candidates']) ~= 'table'
  or #payload['candidates'] < 1 or #payload['candidates'] > 500 then
  return {err='invalid party result payload'}
end
redis.call('SET', result_key, payload_text)
return 1
"""


def party_candidates_key(party_id: int) -> str:
    if party_id <= 0:
        raise ValueError("party ID must be positive")
    return f"feelm:party:{party_id}:candidates"


def party_partition_fence_key(group_id: str, topic: str, partition: int) -> str:
    return f"feelm:party-consumer-fence:{group_id}:{topic}:{partition}"


def claim_party_partition_fence(store: object, group_id: str, topic: str, partition: int) -> tuple[str, str]:
    key = party_partition_fence_key(group_id, topic, partition)
    token = secrets.token_urlsafe(32)
    if not token:
        raise ValueError("invalid partition fence token")
    store.set(key, token)
    return key, token


def publish_party(
    store: object,
    party_id: int,
    model_version: str,
    payload: Mapping[str, object],
    partition_fence: tuple[str, str],
) -> None:
    if payload.get("partyId") != party_id:
        raise ValueError("payload party ID does not match Redis key")
    if payload.get("modelVersion") != model_version:
        raise ValueError("payload model version does not match loaded model")
    fence_key, fence_token = partition_fence
    result = store.eval(
        PUBLISH_PARTY_LUA,
        3,
        party_candidates_key(party_id),
        "feelm:model:current",
        fence_key,
        model_version,
        fence_token,
        json.dumps(payload, separators=(",", ":"), ensure_ascii=False, allow_nan=False),
    )
    result = int(result)
    if result == -1:
        raise StalePartyPartitionFenceError("partition fence is no longer current")
    if result == -2:
        raise StalePartyModelError("loaded model is no longer current")
    if result != 1:
        raise redis_lib.RedisError("unexpected party publish response")
