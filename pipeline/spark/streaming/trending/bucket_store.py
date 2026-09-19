"""Bounded five-minute VIEW buckets used by the trending publisher."""

from __future__ import annotations

import hashlib
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import datetime, timedelta

from streaming.trending.aggregation import PUBLISH_INTERVAL, ViewEvent, align_publish_time

BUCKET_TTL_SECONDS = 150 * 60
ACTIVE_HOUR_TTL_SECONDS = 4 * 60 * 60
BUCKET_PREFIX = "feelm:trending:bucket"


class BucketIntegrityError(RuntimeError):
    """A Redis bucket no longer matches the state committed by the writer."""


@dataclass(frozen=True)
class ViewerBucket:
    start: datetime
    movie_id: int
    user_ids: frozenset[int]


def _stamp(start: datetime) -> str:
    return align_publish_time(start).strftime("%Y%m%dT%H%MZ")


def _index_key(start: datetime) -> str:
    return f"{BUCKET_PREFIX}:{_stamp(start)}:movies"


def _viewers_key(start: datetime, movie_id: int) -> str:
    return f"{BUCKET_PREFIX}:{_stamp(start)}:movie:{movie_id}:users"


def _expected_movies_key(start: datetime) -> str:
    return f"{BUCKET_PREFIX}:{_stamp(start)}:expected-movies"


def _expected_count_key(start: datetime, movie_id: int) -> str:
    return f"{BUCKET_PREFIX}:{_stamp(start)}:movie:{movie_id}:expected-count"


def _viewer_digest_key(start: datetime, movie_id: int) -> str:
    return f"{BUCKET_PREFIX}:{_stamp(start)}:movie:{movie_id}:viewer-digest"


def _viewer_digest(user_ids: Iterable[object]) -> str:
    canonical = ",".join(str(user_id) for user_id in sorted(int(value) for value in user_ids))
    return hashlib.sha256(canonical.encode("ascii")).hexdigest()


def _text(value: object) -> str | None:
    if value is None:
        return None
    return value.decode("ascii") if isinstance(value, bytes) else str(value)


def _active_hour_key(start: datetime) -> str:
    return f"{BUCKET_PREFIX}:active-hour:{start.strftime('%Y%m%dT%HZ')}"


def _committed_hour_key(start: datetime) -> str:
    return f"{BUCKET_PREFIX}:committed-hour:{start.strftime('%Y%m%dT%HZ')}"


def write_buckets(redis: object, buckets: Iterable[ViewerBucket]) -> None:
    """Reject incompatible keys before queuing an idempotent micro-batch."""
    merged: dict[tuple[datetime, int], set[int]] = {}
    for bucket in buckets:
        if bucket.user_ids:
            merged.setdefault((bucket.start, bucket.movie_id), set()).update(bucket.user_ids)
    if not merged:
        return
    keys = list(dict.fromkeys(
        key
        for start, movie_id in merged
        for key in (
            _index_key(start), _expected_movies_key(start),
            _viewers_key(start, movie_id), _expected_count_key(start, movie_id),
            _viewer_digest_key(start, movie_id),
            _active_hour_key(start), _committed_hour_key(start),
        )
    ))
    with redis.pipeline(transaction=True) as pipe:
        # Redis EXEC does not roll back a WRONGTYPE command. WATCH also prevents
        # another writer from changing a checked key before EXEC.
        pipe.watch(*keys)
        for key in keys:
            key_type = pipe.type(key)
            expected = (
                ("none", "string", b"none", b"string")
                if key.endswith((":expected-count", ":viewer-digest"))
                else ("none", "set", b"none", b"set")
            )
            if key_type not in expected:
                raise TypeError(f"trending bucket key has incompatible type: {key}")
        expected_counts: dict[tuple[datetime, int], int] = {}
        expected_digests: dict[tuple[datetime, int], str] = {}
        for (start, movie_id), user_ids in merged.items():
            indexed = set(pipe.smembers(_index_key(start)))
            expected_movies = set(pipe.smembers(_expected_movies_key(start)))
            active = set(pipe.smembers(_active_hour_key(start)))
            committed = set(pipe.smembers(_committed_hour_key(start)))
            existing_users = set(pipe.smembers(_viewers_key(start, movie_id)))
            old_count = pipe.get(_expected_count_key(start, movie_id))
            old_digest = _text(pipe.get(_viewer_digest_key(start, movie_id)))
            if indexed != expected_movies or bool(indexed) != (_stamp(start) in active) or (
                bool(indexed) != (_stamp(start) in committed)
            ) or (
                old_count is None and (
                    old_digest is not None or str(movie_id) in indexed or existing_users
                )
            ) or (old_count is not None and (
                str(movie_id) not in indexed
                or len(existing_users) != int(old_count)
                or old_digest != _viewer_digest(existing_users)
            )):
                raise BucketIntegrityError(
                    f"trending bucket is incomplete before write: {_stamp(start)}:{movie_id}"
                )
            next_users = existing_users | {str(user_id) for user_id in user_ids}
            expected_counts[(start, movie_id)] = len(next_users)
            expected_digests[(start, movie_id)] = _viewer_digest(next_users)
        pipe.multi()
        for (start, movie_id), user_ids in merged.items():
            index = _index_key(start)
            expected_movies = _expected_movies_key(start)
            users = _viewers_key(start, movie_id)
            pipe.sadd(index, str(movie_id))
            pipe.expire(index, BUCKET_TTL_SECONDS)
            pipe.sadd(expected_movies, str(movie_id))
            pipe.expire(expected_movies, BUCKET_TTL_SECONDS)
            pipe.sadd(_active_hour_key(start), _stamp(start))
            pipe.expire(_active_hour_key(start), ACTIVE_HOUR_TTL_SECONDS)
            pipe.sadd(_committed_hour_key(start), _stamp(start))
            pipe.expire(_committed_hour_key(start), ACTIVE_HOUR_TTL_SECONDS)
            pipe.sadd(users, *(str(user_id) for user_id in sorted(user_ids)))
            pipe.expire(users, BUCKET_TTL_SECONDS)
            pipe.set(
                _expected_count_key(start, movie_id),
                str(expected_counts[(start, movie_id)]),
                ex=BUCKET_TTL_SECONDS,
            )
            pipe.set(
                _viewer_digest_key(start, movie_id),
                expected_digests[(start, movie_id)],
                ex=BUCKET_TTL_SECONDS,
            )
        pipe.execute()


def read_window_events(redis: object, *, to: datetime) -> list[ViewEvent]:
    """Read exactly the 24 buckets in the two comparison hours."""
    to = align_publish_time(to)
    events: list[ViewEvent] = []
    for index in range(24):
        start = to - timedelta(hours=2) + index * PUBLISH_INTERVAL
        movie_ids = redis.smembers(_index_key(start))
        active = redis.smembers(_active_hour_key(start))
        committed = redis.smembers(_committed_hour_key(start))
        if movie_ids != redis.smembers(_expected_movies_key(start)) or bool(movie_ids) != (
            _stamp(start) in active
        ) or bool(movie_ids) != (_stamp(start) in committed):
            raise BucketIntegrityError(
                f"trending bucket movie index is incomplete: {_stamp(start)}"
            )
        for raw_movie_id in movie_ids:
            movie_id = int(raw_movie_id)
            user_ids = redis.smembers(_viewers_key(start, movie_id))
            expected_count = redis.get(_expected_count_key(start, movie_id))
            expected_digest = _text(redis.get(_viewer_digest_key(start, movie_id)))
            if (
                expected_count is None
                or len(user_ids) != int(expected_count)
                or expected_digest != _viewer_digest(user_ids)
            ):
                raise BucketIntegrityError(
                    f"trending bucket viewers are incomplete: {_stamp(start)}:{movie_id}"
                )
            for raw_user_id in user_ids:
                user_id = int(raw_user_id)
                events.append(
                    ViewEvent(
                        event_id=f"bucket:{_stamp(start)}:{movie_id}:{user_id}",
                        user_id=user_id,
                        movie_id=movie_id,
                        occurred_at=start,
                    )
                )
    return events
