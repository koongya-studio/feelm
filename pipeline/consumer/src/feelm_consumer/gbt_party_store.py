"""742 publication adapter: fenced successes, durable failures, bounded live result."""

from __future__ import annotations

import hashlib
import json

from feelm_consumer.gbt.errors import GbtError
from feelm_consumer.gbt_party_events import canonical

NAMESPACE = "feelm"


class PublishRejected(RuntimeError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


MODEL_GUARD = """
if redis.call('GET', KEYS[3]) ~= ARGV[1] then return -1 end
local value = cjson.decode(ARGV[2])
local ok, current = pcall(cjson.decode, redis.call('GET', KEYS[2]) or '')
if not ok or type(current) ~= 'table' or current['modelKind'] ~= 'GBT'
  or current['modelVersion'] ~= value['modelVersion']
  or current['servingRevision'] ~= value['servingRevision'] then return -2 end
"""

SOURCE_GUARD = """
local live_raw = redis.call('GET', KEYS[4])
local live_ok, live = pcall(cjson.decode, live_raw or '')
local live_valid = live_ok and type(live) == 'table' and live['status'] == 'OK'
  and type(live['source']) == 'table' and type(live['source']['offset']) == 'string'
if live_valid then
  local a = live['source']
  local b = value['source']
  if a['topic'] ~= b['topic'] or a['partition'] ~= b['partition'] then return -5 end
  if #a['offset'] > #b['offset'] or (#a['offset'] == #b['offset'] and a['offset'] > b['offset']) then
    return -4
  end
  if a['offset'] == b['offset'] and live['inputId'] ~= value['inputId'] then return -7 end
end
local old = redis.call('GET', KEYS[1])
-- Either side can be lost independently; neither may erase the other side's source high-water mark.
if not old and live_valid then old = live_raw end
if old then
  local ok, previous = pcall(cjson.decode, old)
  if not ok or type(previous) ~= 'table' or type(previous['source']) ~= 'table' then return -6 end
  local a = previous['source']
  local b = value['source']
  if a['topic'] ~= b['topic'] or a['partition'] ~= b['partition'] then return -5 end
  local left = a['offset']
  local right = b['offset']
  if type(left) ~= 'string' or type(right) ~= 'string' then return -6 end
  if #left > #right or (#left == #right and left > right) then return -4 end
  if left == right then
    for _, field in ipairs({'partyId', 'inputId', 'modelVersion', 'servingRevision',
                           'algorithmVersion', 'generatedAt'}) do
      if previous[field] ~= value[field] then return -7 end
    end
    if a['timestampMs'] ~= b['timestampMs'] then return -7 end
    if previous['status'] ~= 'OK' or type(previous['candidates']) ~= 'table'
      or #previous['candidates'] < 1 or #previous['candidates'] > 500 then return -6 end
    -- Repairs are protected by the same model, ownership and both source guards as publication.
    redis.call('SET', KEYS[1], old)
    redis.call('SET', KEYS[4], old)
    return old
  end
end
"""

REPLAY_LUA = MODEL_GUARD + SOURCE_GUARD + "return false"
PUBLISH_LUA = MODEL_GUARD + SOURCE_GUARD + """
if value['status'] ~= 'OK' or type(value['candidates']) ~= 'table'
  or #value['candidates'] < 1 or #value['candidates'] > 500 then return -6 end
redis.call('SET', KEYS[1], ARGV[2])
redis.call('SET', KEYS[4], ARGV[2])
return 1
"""

QUARANTINE_LUA = """
if redis.call('GET', KEYS[2]) ~= ARGV[1] then return -1 end
local old = redis.call('GET', KEYS[1])
if old then
  if old == ARGV[2] then return old end
  local ok, prior = pcall(cjson.decode, old)
  local current = cjson.decode(ARGV[2])
  if not ok or type(prior) ~= 'table' or prior['status'] ~= 'QUARANTINED'
    or type(prior['source']) ~= 'table' then return -7 end
  for _, field in ipairs({'partyId', 'inputId', 'modelVersion', 'servingRevision',
                         'algorithmVersion', 'generatedAt'}) do
    if prior[field] ~= current[field] then return -7 end
  end
  for _, field in ipairs({'topic', 'partition', 'offset', 'timestampMs'}) do
    if prior['source'][field] ~= current['source'][field] then return -7 end
  end
  -- Recovery can outlive a model deployment: resume the original failure, never reclassify its identity.
  return old
end
redis.call('SET', KEYS[1], ARGV[2])
return ARGV[2]
"""

RECOVERY_LUA = """
if redis.call('GET', KEYS[2]) ~= ARGV[1] then return -1 end
if ARGV[2] == 'ACKED' then redis.call('SET', KEYS[1], 'ACKED') end
if redis.call('GET', KEYS[1]) == 'ACKED' then return 1 end
return 0
"""


class PartyStore:
    def __init__(self, client):
        self.client = client

    @staticmethod
    def _fence(partition_fence):
        if (not isinstance(partition_fence, tuple) or len(partition_fence) != 2
                or not isinstance(partition_fence[0], str)
                or not partition_fence[0].startswith("feelm:party-consumer-fence:")
                or not isinstance(partition_fence[1], str) or not partition_fence[1]):
            raise GbtError("INVALID_PARTY_FENCE")
        return partition_fence

    def validate_model(self, bundle, partition_fence):
        key, token = self._fence(partition_fence)
        result = self.client.eval(MODEL_GUARD + "return 1", 3, "unused", "feelm:model:current", key, token,
                                  canonical({"modelVersion": bundle.model_version,
                                             "servingRevision": bundle.serving_revision}))
        self._result(result)

    def publish(self, payload: dict, partition_fence: tuple[str, str]) -> str:
        key, token = self._fence(partition_fence)
        party_id = payload["partyId"]
        result = self.client.eval(PUBLISH_LUA, 4, f"feelm:party:{party_id}:gbt-outcome",
                                  "feelm:model:current", key, f"feelm:party:{party_id}:candidates",
                                  token, canonical(payload))
        if isinstance(result, (str, bytes)):
            if json.loads(result) != payload:
                raise PublishRejected("REPLAY_CONFLICT")
            return "IDEMPOTENT"
        return self._result(result)

    def replay(self, identity: dict, partition_fence: tuple[str, str]) -> dict | None:
        key, token = self._fence(partition_fence)
        result = self.client.eval(REPLAY_LUA, 4, f"feelm:party:{identity['partyId']}:gbt-outcome",
                                  "feelm:model:current", key, f"feelm:party:{identity['partyId']}:candidates",
                                  token, canonical(identity))
        if result is None:
            return None
        if isinstance(result, int):
            self._result(result)
            raise RuntimeError("UNKNOWN_PARTY_REPLAY_RESULT")
        return json.loads(result)

    def quarantine(self, payload: dict, partition_fence: tuple[str, str]) -> dict:
        key, token = self._fence(partition_fence)
        source = payload["source"]
        target = f"feelm:party-consumer:quarantine:{source['topic']}:{source['partition']}:{source['offset']}"
        result = self.client.eval(QUARANTINE_LUA, 2, target, key, token, canonical(payload))
        if isinstance(result, int):
            self._result(result)
            raise RuntimeError("UNKNOWN_PARTY_QUARANTINE_RESULT")
        return json.loads(result)

    def recovery_done(self, payload, partition_fence, *, mark=False):
        key, token = self._fence(partition_fence)
        identity = {name: payload.get(name) for name in ("partyId", "inputId", "errorCode")}
        fingerprint = hashlib.sha256(canonical(identity).encode()).hexdigest()
        target = f"feelm:party-consumer:recovery:{fingerprint}"
        result = int(self.client.eval(RECOVERY_LUA, 2, target, key, token, "ACKED" if mark else "READ"))
        if result == -1:
            raise PublishRejected("FENCED")
        return result == 1

    @staticmethod
    def _result(result):
        errors = {-1: "FENCED", -2: "STALE_MODEL", -4: "STALE_SOURCE", -5: "SOURCE_PARTITION_CHANGED",
                  -6: "CORRUPT_PREVIOUS_OUTCOME", -7: "REPLAY_CONFLICT"}
        result = int(result)
        if result in errors:
            raise PublishRejected(errors[result])
        if result not in (0, 1):
            raise RuntimeError("UNKNOWN_PARTY_PUBLISH_RESULT")
        return "PUBLISHED" if result else "IDEMPOTENT"
