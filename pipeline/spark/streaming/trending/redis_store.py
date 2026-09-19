"""Atomic Redis publication for API-064 trending snapshots."""

from __future__ import annotations

import json
from collections.abc import Mapping

CURRENT_KEY = "feelm:trending:current"
LAST_GOOD_KEY = "feelm:trending:last-good"
CURRENT_TTL_SECONDS = 15 * 60


def read_snapshot(redis: object) -> dict[str, object] | None:
    """Read the latest nonempty VIEW result, including the retained result."""
    for key in (CURRENT_KEY, LAST_GOOD_KEY):
        raw = redis.get(key)
        if raw is None:
            continue
        try:
            value = json.loads(raw)
        except (TypeError, json.JSONDecodeError):
            continue
        if isinstance(value, dict) and isinstance(value.get("items"), list) and value["items"]:
            return value
    return None


def publish(redis: object, payload: Mapping[str, object]) -> None:
    """Publish a nonempty result and retain it until a newer one replaces it."""
    if not payload.get("items"):
        raise ValueError("only nonempty VIEW results can replace the retained result")
    encoded = json.dumps(payload, separators=(",", ":"), ensure_ascii=False, allow_nan=False)
    with redis.pipeline(transaction=True) as pipe:
        pipe.set(CURRENT_KEY, encoded, ex=CURRENT_TTL_SECONDS)
        pipe.set(LAST_GOOD_KEY, encoded)
        pipe.execute()
