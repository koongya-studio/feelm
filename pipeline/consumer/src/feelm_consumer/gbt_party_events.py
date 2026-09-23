"""742 party v2 adapter and the Java PartyInputId length-delimited digest."""

from __future__ import annotations

import hashlib
import json
from struct import pack

from feelm_consumer.gbt.errors import GbtError
from feelm_consumer.gbt_snapshot import valid_revision
from feelm_consumer.party_events import (
    PartyEventValidationError,
    PartyInputReference,
    PartyRefresh,
    parse_party_refresh,
)

MAX_REQUEST_BYTES = 256 * 1024
INPUT_SCHEMA_VERSION = "gbt-input-v1"
PartyRequest = PartyRefresh
MemberRef = PartyInputReference


def canonical(value: object) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)


def request_payload(request: PartyRefresh) -> dict:
    return {"schemaVersion": request.schema_version, "partyId": request.party_id, "inputId": request.input_id,
            "modelVersion": request.model_version, "servingRevision": request.serving_revision,
            "members": [{"userId": m.user_id, "generationId": m.generation_id,
                         "inputSchemaVersion": m.input_schema_version, "inputHash": m.input_hash,
                         "candidateMovieIds": list(m.candidate_movie_ids)} for m in request.members]}


def request_hash(request: PartyRefresh) -> str:
    """Exactly mirrors backend domain/party/model/PartyInputId.java for v2."""
    digest = hashlib.sha256(pack(">q", request.party_id))

    def update(value: str):
        encoded = value.encode("utf-8")
        digest.update(pack(">i", len(encoded)))
        digest.update(encoded)

    update(request.model_version)
    digest.update(pack(">i", request.schema_version))
    update(request.serving_revision)
    for member in request.members:
        digest.update(pack(">q", member.user_id))
        update(member.generation_id)
        update(member.input_schema_version)
        update(member.input_hash)
    return "sha256:" + digest.hexdigest()


def validate_request(request: PartyRefresh) -> None:
    if request.schema_version != 2 or not all(isinstance(m, PartyInputReference) for m in request.members):
        raise GbtError("INVALID_PARTY_REQUEST_VERSION")
    if not valid_revision(request.serving_revision):
        raise GbtError("INVALID_SERVING_REVISION")
    try:
        parsed = parse_party_refresh(canonical(request_payload(request)), pack(">q", request.party_id))
    except (PartyEventValidationError, ValueError, TypeError, OverflowError) as exc:
        raise GbtError("INVALID_PARTY_REQUEST") from exc
    if any(m.input_schema_version != INPUT_SCHEMA_VERSION for m in parsed.members):
        raise GbtError("UNSUPPORTED_PARTY_INPUT_SCHEMA")
    if request_hash(parsed) != parsed.input_id:
        raise GbtError("PARTY_INPUT_HASH_MISMATCH")


def _unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise GbtError("DUPLICATE_PARTY_FIELD")
        value[key] = item
    return value


def parse_versioned_request(raw: bytes | str, key: bytes | None) -> PartyRefresh:
    if not isinstance(raw, (bytes, str)):
        raise GbtError("INVALID_PARTY_JSON")
    try:
        encoded = raw.encode("utf-8") if isinstance(raw, str) else raw
        if len(encoded) > MAX_REQUEST_BYTES:
            raise GbtError("PARTY_REQUEST_TOO_LARGE")
        decoded = json.loads(encoded, object_pairs_hook=_unique_object)
        if isinstance(decoded, dict) and decoded.get("schemaVersion") == 2:
            fields = {"schemaVersion", "partyId", "inputId", "modelVersion", "servingRevision", "members"}
            member_fields = {"userId", "generationId", "inputSchemaVersion", "inputHash", "candidateMovieIds"}
            if set(decoded) != fields or not isinstance(decoded.get("members"), list):
                raise GbtError("INVALID_PARTY_FIELDS")
            if any(not isinstance(m, dict) or set(m) != member_fields for m in decoded["members"]):
                raise GbtError("INVALID_PARTY_MEMBER_FIELDS")
        return parse_party_refresh(canonical(decoded), key)
    except GbtError:
        raise
    except (UnicodeError, ValueError, TypeError, RecursionError) as exc:
        raise GbtError("INVALID_PARTY_JSON") from exc


def parse_request(raw: bytes | str, key: bytes | None) -> PartyRefresh:
    request = parse_versioned_request(raw, key)
    validate_request(request)
    return request
