from __future__ import annotations

import json
import struct
from dataclasses import dataclass
from datetime import UTC, datetime
from uuid import UUID


class EventValidationError(ValueError):
    pass


@dataclass(frozen=True)
class RatingEvent:
    event_id: UUID
    user_id: int
    action: str
    event_type: str
    occurred_at: datetime


def parse_event(raw: bytes, key: bytes | None) -> RatingEvent:
    try:
        payload = json.loads(raw.decode("utf-8"))
        event_id = UUID(str(payload["eventId"]))
        user_id = payload["userId"]
        event_type = payload["eventType"]
        body = payload["payload"]
        action = body["action"] if event_type == "RATING" else "ONBOARDING"
        occurred_at = datetime.fromisoformat(str(payload["occurredAt"]).replace("Z", "+00:00"))
    except (KeyError, TypeError, UnicodeDecodeError, ValueError, json.JSONDecodeError) as exc:
        raise EventValidationError("invalid rating event") from exc
    if key is None or len(key) != 8:
        raise EventValidationError("invalid partition key")
    decoded_key = struct.unpack(">q", key)[0]
    if isinstance(user_id, bool) or not isinstance(user_id, int) or not 0 < user_id < 2**63:
        raise EventValidationError("invalid userId")
    if action not in {"CREATE", "UPDATE", "DELETE", "ONBOARDING"} or event_type not in {"RATING", "ONBOARDING"}:
        raise EventValidationError("invalid event type or action")
    if event_type == "RATING":
        movie_id = body.get("movieId")
        if isinstance(movie_id, bool) or not isinstance(movie_id, int) or not 0 < movie_id < 2**63:
            raise EventValidationError("invalid movieId")
        score = body.get("score")
        if action == "DELETE":
            if score is not None:
                raise EventValidationError("DELETE must not carry score")
        elif (
            isinstance(score, bool)
            or not isinstance(score, (int, float))
            or not 0.5 <= float(score) <= 5.0
            or float(score) * 2 != round(float(score) * 2)
        ):
            raise EventValidationError("invalid score")
    else:
        count = body.get("preferenceCount")
        if isinstance(count, bool) or not isinstance(count, int) or count <= 0:
            raise EventValidationError("invalid preferenceCount")
    if occurred_at.tzinfo is None or occurred_at.utcoffset() != UTC.utcoffset(occurred_at):
        raise EventValidationError("occurredAt must be UTC")
    if decoded_key != user_id:
        raise EventValidationError("partition key does not match userId")
    return RatingEvent(event_id, user_id, action, event_type, occurred_at)
