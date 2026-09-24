"""742 fenced publication; only superseded generation snapshots expire."""

from __future__ import annotations

import json
import secrets

from feelm_consumer.gbt.errors import GbtError
from feelm_consumer.gbt.inputs import movie_id
from feelm_consumer.gbt_evidence import MAX_EVIDENCE_BYTES, EvidenceSnapshot, validate_evidence
from feelm_consumer.gbt_snapshot import (
    INPUT_SCHEMA,
    MODEL_VERSION,
    canonical,
    decode_snapshot,
    safe_identifier,
    valid_revision,
)

NAMESPACE = "feelm"
SHADOW_NAMESPACE = "feelm:shadow:gbt-v1"


class StaleWriter(RuntimeError):
    pass


class PublicationConflict(RuntimeError):
    pass


class PublicationVersion(int):
    """CAS captured before DB reads, including the per-user recovery epoch."""

    def __new__(cls, value: int, epoch: str):
        instance = super().__new__(cls, value)
        instance.epoch = epoch
        return instance


MODEL_GUARD = """
if redis.call('GET', KEYS[1]) ~= ARGV[1] then return -1 end
local ok, model = pcall(cjson.decode, redis.call('GET', KEYS[2]) or '')
if not ok or type(model) ~= 'table' or model['modelKind'] ~= 'GBT'
  or model['servingRevision'] ~= ARGV[2] or model['modelVersion'] ~= ARGV[3] then return -2 end
"""

READ_METADATA = """
local function object_at(key)
  if redis.call('TYPE', key).ok ~= 'string' then return nil end
  local ok, value = pcall(cjson.decode, redis.call('GET', key))
  if ok and type(value) == 'table' then return value end
  return nil
end
local function version(value)
  if type(value) ~= 'string' and type(value) ~= 'number' then return nil end
  local n = tonumber(value)
  if n and n >= 0 and n == math.floor(n) and n < 9007199254740991 then return n end
  return nil
end
local function current_version(head_key)
  local raw = nil
  if redis.call('TYPE', KEYS[3]).ok == 'string' then raw = redis.call('GET', KEYS[3]) end
  local head = object_at(head_key)
  -- The independent head preserves the monotonic CAS even if a formal key is lost.
  local formal = version(raw)
  local backup = version(head and head['publicationVersion'])
  return math.max(formal or 0, backup or 0), formal ~= nil or backup ~= nil
end
"""

VERSION_LUA = MODEL_GUARD + READ_METADATA + """
local current, known = current_version(KEYS[4])
local head = object_at(KEYS[4])
local epoch = head and head['publicationEpoch']
if not known or type(epoch) ~= 'string' or #epoch ~= 64 or not string.match(epoch, '^[a-f0-9]+$') then
  -- A new user epoch invalidates prepared writers across every topic/group fence.
  if not known and redis.call('EXISTS', KEYS[5], KEYS[6], KEYS[7]) > 0 then current = 1 end
  head = object_at(KEYS[5]) or object_at(KEYS[6]) or object_at(KEYS[7]) or {}
  head['publicationVersion'] = current
  head['publicationEpoch'] = ARGV[4]
  redis.call('SET', KEYS[4], cjson.encode(head))
  redis.call('SET', KEYS[3], current)
end
return {current, head['publicationEpoch']}
"""

PUBLISH_LUA = MODEL_GUARD + READ_METADATA + """
local current, known = current_version(KEYS[7])
if not known and redis.call('EXISTS', KEYS[5], KEYS[6], KEYS[9]) > 0 then return -3 end
local head = object_at(KEYS[7])
if not head or head['publicationEpoch'] ~= ARGV[12] then return -3 end
if current ~= tonumber(ARGV[4]) then return -3 end
local prior = redis.call('GET', KEYS[4])
if prior and prior ~= ARGV[5] then return -4 end
local prior_evidence = redis.call('GET', KEYS[8])
if prior_evidence and prior_evidence ~= ARGV[13] then return -4 end
local identity = cjson.decode(ARGV[11])
local old_keys = {}
for _, key in ipairs({KEYS[7], KEYS[5], KEYS[6], KEYS[9]}) do
  local previous = object_at(key)
  if previous then
    local generation = previous['generationId']
    if type(generation) == 'string' and #generation >= 1 and #generation <= 100
      and string.match(generation, '^[%w][%w._-]*$') then
      if generation == identity['generationId'] then
        for _, field in ipairs({'inputHash', 'inputSchemaVersion', 'modelVersion', 'servingRevision'}) do
          if previous[field] ~= identity[field] then return -4 end
        end
      else
        old_keys[ARGV[10] .. generation] = true
        old_keys[ARGV[14] .. generation] = true
      end
    end
  end
end
-- Current state/candidates/input/evidence remain readable; superseded snapshots expire.
for old_key, _ in pairs(old_keys) do
  if redis.call('PTTL', old_key) == -1 then redis.call('EXPIRE', old_key, ARGV[8]) end
end
redis.call('SET', KEYS[4], ARGV[5])
redis.call('SET', KEYS[5], ARGV[6])
if ARGV[9] == '1' then redis.call('SET', KEYS[6], ARGV[7])
else redis.call('DEL', KEYS[6]) end
redis.call('SET', KEYS[8], ARGV[13])
redis.call('SET', KEYS[3], current + 1)
identity['publicationVersion'] = current + 1
identity['publicationEpoch'] = ARGV[12]
redis.call('SET', KEYS[7], cjson.encode(identity))
redis.call('SET', KEYS[9], cjson.encode(identity))
return current + 1
"""

FAILURE_LUA = MODEL_GUARD + """
local prior = redis.call('GET', KEYS[3])
if prior and prior ~= ARGV[4] then return -3 end
if not prior then redis.call('SET', KEYS[3], ARGV[4], 'EX', ARGV[5]) end
return 1
"""


class GbtStore:
    def __init__(self, client, *, serving_revision: str, model_version: str,
                 snapshot_ttl_seconds: int, max_snapshot_bytes: int, shadow: bool = False):
        if not valid_revision(serving_revision):
            raise GbtError("INVALID_SERVING_REVISION")
        if not isinstance(model_version, str) or not MODEL_VERSION.fullmatch(model_version):
            raise GbtError("INVALID_MODEL_VERSION")
        if type(snapshot_ttl_seconds) is not int or not 1 <= snapshot_ttl_seconds <= 2**31 - 1:
            raise ValueError("superseded snapshot retention must be explicit positive seconds")
        if type(max_snapshot_bytes) is not int or not 1 <= max_snapshot_bytes <= 64 * 1024**2:
            raise ValueError("snapshot byte budget must be explicit (maximum 64 MiB)")
        self.client, self.serving_revision, self.model_version = client, serving_revision, model_version
        self.snapshot_ttl_seconds, self.max_snapshot_bytes = snapshot_ttl_seconds, max_snapshot_bytes
        self.namespace = SHADOW_NAMESPACE if shadow else NAMESPACE
        self.revision_key = f"{self.namespace}:model:current"

    def claim(self, group: str, topic: str, partition: int) -> tuple[str, str]:
        safe_identifier(group)
        safe_identifier(topic)
        if type(partition) is not int or partition < 0:
            raise ValueError("invalid partition")
        key, token = f"{self.namespace}:consumer-fence:{group}:{topic}:{partition}", secrets.token_urlsafe(32)
        self.client.set(key, token)
        return key, token

    def _fence(self, fence: tuple[str, str]) -> tuple[str, str]:
        key, token = fence
        if not key.startswith(f"{self.namespace}:consumer-fence:") or not token:
            raise ValueError("GBT partition fence required")
        return key, token

    @staticmethod
    def _checked(value) -> int:
        value = int(value)
        if value in {-1, -2}:
            raise StaleWriter("partition or serving revision changed")
        if value < 0:
            raise PublicationConflict("publication version or immutable generation conflict")
        return value

    def current_version(self, user_id: int, fence: tuple[str, str]) -> PublicationVersion:
        key, token = self._fence(fence)
        base = f"{self.namespace}:user:{movie_id(user_id)}"
        result = self.client.eval(VERSION_LUA, 7, key, self.revision_key,
                                  f"{base}:candidate-version", f"{base}:input-current",
                                  f"{base}:recommendation-state", f"{base}:candidates",
                                  f"{base}:gbt-evidence-current",
                                  token, self.serving_revision, self.model_version, secrets.token_hex(32))
        if not isinstance(result, list):
            self._checked(result)
            raise PublicationConflict("missing publication epoch")
        number, epoch = result
        return PublicationVersion(self._checked(number), epoch.decode() if isinstance(epoch, bytes) else epoch)

    def publish(self, result, expected_version: int, fence: tuple[str, str]) -> int:
        key, token = self._fence(fence)
        if not isinstance(expected_version, PublicationVersion) or not 0 <= expected_version < 2**53 - 1:
            raise ValueError("publication version must be captured by current_version before reading DB")
        snapshot = result.snapshot
        if (snapshot.serving_revision != self.serving_revision or snapshot.model_version != self.model_version):
            raise GbtError("STORE_REVISION_MISMATCH")
        encoded = canonical(snapshot.to_payload())
        if len(encoded) > self.max_snapshot_bytes:
            raise GbtError("SNAPSHOT_TOO_LARGE")
        identity = {"userId": snapshot.user_id, "generationId": snapshot.generation_id,
                    "servingRevision": snapshot.serving_revision, "modelVersion": snapshot.model_version,
                    "inputSchemaVersion": INPUT_SCHEMA, "inputHash": snapshot.input_hash}
        if any(payload.get(k) != v for payload in (result.state, result.candidates) for k, v in identity.items()):
            raise GbtError("PUBLICATION_GENERATION_MISMATCH")
        if (result.state.get("stateVersion") != 1 or result.state.get("modelKind") != "GBT"
                or result.candidates.get("candidateVersion") != 6):
            raise GbtError("PUBLICATION_SCHEMA_MISMATCH")
        if not isinstance(result.evidence, EvidenceSnapshot) or not result.evidence.sealed_for_publication():
            raise GbtError("UNTRUSTED_EVIDENCE_RESULT")
        evidence_payload = result.evidence.to_payload()
        taste = result.candidates.get("taste")
        if not isinstance(taste, list):
            raise GbtError("PUBLICATION_SCHEMA_MISMATCH")
        evidence_taste = taste if result.state.get("supportedInputCount", 0) > 0 else []
        validate_evidence(
            evidence_payload,
            snapshot=snapshot,
            candidates=[item.get("movieId") for item in evidence_taste],
            scores=[item.get("score") for item in evidence_taste],
        )
        encoded_evidence = canonical(evidence_payload)
        if len(encoded_evidence) > MAX_EVIDENCE_BYTES:
            raise GbtError("EVIDENCE_TOO_LARGE")
        base = f"{self.namespace}:user:{snapshot.user_id}"
        return self._checked(self.client.eval(
            PUBLISH_LUA, 9, key, self.revision_key, f"{base}:candidate-version",
            f"{base}:input:{snapshot.generation_id}", f"{base}:recommendation-state", f"{base}:candidates",
            f"{base}:input-current", f"{base}:gbt-evidence:{snapshot.generation_id}",
            f"{base}:gbt-evidence-current",
            token, self.serving_revision, self.model_version, expected_version, encoded,
            canonical(result.state), canonical(result.candidates), self.snapshot_ttl_seconds,
            int(result.state["status"] == "READY"), f"{base}:input:", canonical(identity), expected_version.epoch,
            encoded_evidence, f"{base}:gbt-evidence:",
        ))

    def read_snapshot(self, user_id: int, generation_id: str, *, serving_revision: str,
                      model_version: str, input_hash: str):
        key = f"{self.namespace}:user:{movie_id(user_id)}:input:{safe_identifier(generation_id)}"
        return decode_snapshot(self.client.get(key), user_id=user_id, generation_id=generation_id,
                               serving_revision=serving_revision, model_version=model_version,
                               input_hash=input_hash, max_bytes=self.max_snapshot_bytes)

    def read_evidence(self, user_id: int, generation_id: str):
        key = f"{self.namespace}:user:{movie_id(user_id)}:gbt-evidence:{safe_identifier(generation_id)}"
        return self.client.get(key)

    def record_failure(self, *, topic: str, partition: int, offset: int, code: str, fence: tuple[str, str]) -> None:
        safe_identifier(topic)
        safe_identifier(code)
        if type(partition) is not int or partition < 0 or type(offset) is not int or offset < 0:
            raise ValueError("invalid source position")
        key, token = self._fence(fence)
        payload = {"topic": topic, "partition": partition, "offset": offset,
                   "code": code, "servingRevision": self.serving_revision}
        self._checked(self.client.eval(
            FAILURE_LUA, 3, key, self.revision_key,
            f"{self.namespace}:gbt-failure:{topic}:{partition}:{offset}",
            token, self.serving_revision, self.model_version,
            json.dumps(payload, sort_keys=True, separators=(",", ":")), self.snapshot_ttl_seconds,
        ))
