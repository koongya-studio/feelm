"""742 one-record processor with durable quarantine and acknowledged recovery.

Callers may acknowledge only a returned receipt with ``commit_allowed=True``.
Store/notification exceptions propagate so a partial attempt is retried. An
invalid request gets a durable, input-free quarantine record before completion.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, replace
from datetime import UTC, datetime, timedelta

from feelm_consumer.gbt.errors import GbtError
from feelm_consumer.gbt_party import ALGORITHM_VERSION, calculate_party, resolve_party_input, score_party
from feelm_consumer.gbt_party_events import INPUT_SCHEMA_VERSION, parse_versioned_request, validate_request
from feelm_consumer.gbt_party_store import PublishRejected


@dataclass(frozen=True)
class RecordSource:
    topic: str
    partition: int
    offset: int
    timestamp_ms: int

    def __post_init__(self):
        if (not isinstance(self.topic, str) or not re.fullmatch(r"[a-zA-Z0-9._-]{1,249}", self.topic)
                or type(self.partition) is not int or not 0 <= self.partition < 2**31
                or type(self.offset) is not int or not 0 <= self.offset < 2**63
                or type(self.timestamp_ms) is not int or not 0 <= self.timestamp_ms <= 253402300799999):
            raise GbtError("INVALID_PARTY_SOURCE")

    @property
    def generated_at(self) -> str:
        value = datetime(1970, 1, 1, tzinfo=UTC) + timedelta(milliseconds=self.timestamp_ms)
        return value.isoformat(timespec="milliseconds").replace("+00:00", "Z")

    def to_payload(self) -> dict:
        # Offsets exceed Redis Lua's exact double range; compare decimal strings.
        return {"topic": self.topic, "partition": self.partition, "offset": str(self.offset),
                "timestampMs": self.timestamp_ms}


@dataclass(frozen=True)
class ProcessingReceipt:
    disposition: str
    result_status: str | None
    commit_allowed: bool


def process_record(*, scorer, snapshot_reader, outcome_store, raw: bytes | str, key: bytes | None,
                   source: RecordSource, partition_fence: tuple[str, str], notify=None,
                   requeue_member=None, is_still_owned=None) -> ProcessingReceipt:
    """Persist semantic failures; never convert dependency outages to empty success."""
    if is_still_owned is not None and not is_still_owned():
        return ProcessingReceipt("FENCED", None, False)
    request = None
    payload = {"source": source.to_payload(), "generatedAt": source.generated_at,
               "algorithmVersion": ALGORITHM_VERSION}
    failure_identity = payload.copy()
    try:
        try:
            request = parse_versioned_request(raw, key)
            payload.update(partyId=request.party_id, inputId=request.input_id,
                           modelVersion=request.model_version, servingRevision=request.serving_revision)
            failure_identity = payload.copy()
            if request.schema_version == 1:
                raise GbtError("LATE_LEGACY_REQUEST")
            # A stale loaded process must not consume a valid request as terminal failure.
            outcome_store.validate_model(scorer.bundle, partition_fence)
            if any(member.input_schema_version != INPUT_SCHEMA_VERSION for member in request.members):
                raise GbtError("UNSUPPORTED_PARTY_INPUT_SCHEMA")
            snapshots = None
            if request.model_version is None:
                saved = outcome_store.replay_metadata(payload, partition_fence)
                if saved is not None:
                    request = replace(request, model_version=saved.get("modelVersion"),
                                      serving_revision=saved.get("servingRevision"))
                else:
                    request, snapshots = resolve_party_input(request, snapshot_reader)
                payload.update(modelVersion=request.model_version, servingRevision=request.serving_revision)
            validate_request(request)
            if (scorer.bundle.serving_revision != request.serving_revision
                    or scorer.bundle.model_version != request.model_version):
                raise GbtError("STALE_REQUEST_MODEL")
            replayed = outcome_store.replay(payload, partition_fence)
            if replayed is not None:
                return _notify_receipt(replayed, "IDEMPOTENT", notify, is_still_owned)
            result = (score_party(scorer, request, snapshots) if snapshots is not None
                      else calculate_party(scorer, request, snapshot_reader))
            if result.status != "OK":
                raise GbtError(result.status)
            payload.update(status="OK", populationCount=result.population_count,
                           activeMemberIds=list(result.active_member_ids),
                           candidates=[candidate.to_payload() for candidate in result.candidates])
        except GbtError as exc:
            payload = {**failure_identity, "status": "QUARANTINED", "errorCode": exc.code}
            if is_still_owned is not None and not is_still_owned():
                return ProcessingReceipt("FENCED", exc.code, False)
            payload = outcome_store.quarantine(payload, partition_fence)
            code = payload["errorCode"]
            # Empty populations are deliberately unsupported until483; do not create a retry loop.
            recover = request is not None and code not in {"NEEDS_COLD_START", "EXHAUSTED"}
            if recover and not outcome_store.recovery_done(payload, partition_fence):
                if requeue_member is None:
                    return ProcessingReceipt("RECOVERY_REQUIRED", code, False)
                for member in request.members:
                    if is_still_owned is not None and not is_still_owned():
                        return ProcessingReceipt("FENCED", code, False)
                    requeue_member(member.user_id)
                outcome_store.recovery_done(payload, partition_fence, mark=True)
            if is_still_owned is not None and not is_still_owned():
                return ProcessingReceipt("FENCED", code, False)
            return ProcessingReceipt("QUARANTINED", code, True)
        if is_still_owned is not None and not is_still_owned():
            return ProcessingReceipt("FENCED", payload["status"], False)
        disposition = outcome_store.publish(payload, partition_fence)
    except PublishRejected as exc:
        return ProcessingReceipt(exc.code, payload.get("status"), exc.code == "STALE_SOURCE")
    return _notify_receipt(payload, disposition, notify, is_still_owned)


def _notify_receipt(payload, disposition, notify, is_still_owned):
    if is_still_owned is not None and not is_still_owned():
        return ProcessingReceipt("FENCED", payload["status"], False)
    if notify is not None:
        notify(payload)  # Must synchronously confirm ACK; replay may notify again.
    if is_still_owned is not None and not is_still_owned():
        return ProcessingReceipt("FENCED", payload["status"], False)
    return ProcessingReceipt(disposition, payload["status"], True)
