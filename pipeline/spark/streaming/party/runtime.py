"""Runtime-only Redis connection helpers for the party streaming job."""

from __future__ import annotations


def redis_writer(redis_url: str):
    """Create and verify a UTF-8 Redis writer before consuming Kafka."""
    if not redis_url:
        raise ValueError("redis_url is required")
    try:
        import redis
    except ImportError as error:  # pragma: no cover - installation boundary
        raise RuntimeError("redis package is required for party streaming") from error
    writer = redis.Redis.from_url(
        redis_url,
        decode_responses=True,
        socket_connect_timeout=5,
        socket_timeout=5,
        health_check_interval=30,
    )
    writer.ping()
    return writer
