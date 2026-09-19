"""Pure event-time policies for the API-064 trending snapshot."""

from __future__ import annotations

import json
import uuid
from collections import defaultdict
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

WINDOW = timedelta(minutes=60)
MIN_UNIQUE_VIEWERS = 3
MAX_ITEMS = 30
PUBLISH_INTERVAL = timedelta(minutes=5)


@dataclass(frozen=True)
class ViewEvent:
    event_id: str
    user_id: int
    movie_id: int
    occurred_at: datetime


@dataclass(frozen=True)
class TrendingItem:
    movie_id: int
    rank: int
    rank_delta: int | None
    trend_score: float


def parse_view_event(raw: str | bytes) -> ViewEvent:
    """Parse the common Kafka envelope and its VIEW payload."""
    try:
        value = json.loads(raw)
    except (TypeError, json.JSONDecodeError) as error:
        raise ValueError("VIEW event must be JSON") from error
    if not isinstance(value, dict):
        raise ValueError("VIEW event must be an object")
    try:
        if value["eventType"] != "VIEW":
            raise ValueError("eventType must be VIEW")
        event_id = str(uuid.UUID(value["eventId"]))
        user_id = _positive_int(value["userId"], "userId")
        occurred_at = _parse_time(value["occurredAt"])
        payload = value["payload"]
        if not isinstance(payload, dict):
            raise ValueError("VIEW payload must be an object")
        movie_id = _positive_int(payload["movieId"], "payload.movieId")
    except (KeyError, TypeError, ValueError) as error:
        raise ValueError("invalid VIEW event") from error
    return ViewEvent(event_id, user_id, movie_id, occurred_at)


def deduplicate(events: Sequence[ViewEvent]) -> tuple[ViewEvent, ...]:
    """Keep the first occurrence of each event ID deterministically."""
    unique: dict[str, ViewEvent] = {}
    for event in events:
        unique.setdefault(event.event_id, event)
    return tuple(unique.values())


def align_publish_time(value: datetime) -> datetime:
    """Return the UTC five-minute boundary at or before ``value``."""
    value = _utc(value)
    minute = value.minute - value.minute % 5
    return value.replace(minute=minute, second=0, microsecond=0)


def unique_viewers(
    events: Sequence[ViewEvent], *, start: datetime, end: datetime
) -> Mapping[int, int]:
    """Count distinct viewers per movie for the half-open event-time interval."""
    start, end = _utc(start), _utc(end)
    viewers: dict[int, set[int]] = defaultdict(set)
    for event in events:
        if start <= _utc(event.occurred_at) < end:
            viewers[event.movie_id].add(event.user_id)
    return {movie_id: len(users) for movie_id, users in viewers.items()}


def build_snapshot(
    events: Sequence[ViewEvent],
    *,
    to: datetime,
    previous_items: Sequence[TrendingItem] = (),
) -> dict[str, object]:
    """Build the canonical Redis snapshot for one 60-minute comparison."""
    to = align_publish_time(to)
    current = unique_viewers(events, start=to - WINDOW, end=to)
    previous = unique_viewers(events, start=to - WINDOW * 2, end=to - WINDOW)
    candidates = [
        (movie_id, count, count - previous.get(movie_id, 0))
        for movie_id, count in current.items()
        if count >= MIN_UNIQUE_VIEWERS and count - previous.get(movie_id, 0) > 0
    ]
    candidates.sort(key=lambda row: (-row[2], -row[1], row[0]))
    candidates = candidates[:MAX_ITEMS]
    max_delta = candidates[0][2] if candidates else 0
    old_ranks = {item.movie_id: item.rank for item in previous_items}
    items = [
        TrendingItem(
            movie_id=movie_id,
            rank=index,
            rank_delta=(old_ranks.get(movie_id) - index if movie_id in old_ranks else None),
            trend_score=delta / max_delta,
        )
        for index, (movie_id, _, delta) in enumerate(candidates, start=1)
    ]
    return {
        "schemaVersion": 1,
        "period": {
            "from": _format(to - WINDOW),
            "to": _format(to),
        },
        "updatedAt": _format(to),
        "items": [
            {
                "movieId": item.movie_id,
                "rank": item.rank,
                "rankDelta": item.rank_delta,
                "trendScore": item.trend_score,
            }
            for item in items
        ],
    }


def items_from_snapshot(value: Mapping[str, object] | None) -> tuple[TrendingItem, ...]:
    """Read only the ranking portion of a previous snapshot."""
    if not value or not isinstance(value.get("items"), list):
        return ()
    result: list[TrendingItem] = []
    for item in value["items"]:
        if not isinstance(item, Mapping):
            continue
        try:
            result.append(
                TrendingItem(
                    movie_id=_positive_int(item["movieId"], "movieId"),
                    rank=_positive_int(item["rank"], "rank"),
                    rank_delta=item.get("rankDelta"),
                    trend_score=float(item["trendScore"]),
                )
            )
        except (KeyError, TypeError, ValueError):
            continue
    return tuple(result)


def _positive_int(value: object, field: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ValueError(f"{field} must be a positive integer")
    return value


def _parse_time(value: object) -> datetime:
    if not isinstance(value, str):
        raise ValueError("occurredAt must be an ISO-8601 string")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("occurredAt must include a timezone")
    return _utc(parsed)


def _utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        raise ValueError("datetime must include a timezone")
    return value.astimezone(timezone.utc)


def _format(value: datetime) -> str:
    return _utc(value).replace(microsecond=0).isoformat().replace("+00:00", "Z")
