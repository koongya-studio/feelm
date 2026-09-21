from __future__ import annotations

from collections.abc import Callable
from contextlib import contextmanager
from dataclasses import dataclass
from typing import Protocol

from feelm_consumer.profile import ProfileInput


@dataclass(frozen=True)
class UserSnapshot:
    """Immutable profile and exclusion inputs read in one PostgreSQL snapshot."""

    ratings: list[ProfileInput]
    watched_movie_ids: frozenset[int]
    dismissed_movie_ids: frozenset[int]


class Cursor(Protocol):
    def execute(self, query: str, params: tuple[int, ...]) -> None: ...

    def fetchall(self) -> list[tuple[int, float | str]]: ...


class Connection(Protocol):
    def execute(self, query: str) -> None: ...

    def cursor(self) -> Cursor: ...

    def close(self) -> None: ...


@contextmanager
def readonly_snapshot(connect: Callable[[], Connection]):
    connection = connect()
    try:
        connection.execute("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
        yield connection
        connection.execute("COMMIT")
    except BaseException:
        try:
            connection.execute("ROLLBACK")
        finally:
            connection.close()
        raise
    else:
        connection.close()


def load_profile(connection: Connection, user_id: int) -> list[ProfileInput]:
    cursor = connection.cursor()
    cursor.execute(
        "SELECT movie_id, score FROM ratings WHERE user_id = %s ORDER BY movie_id",
        (user_id,),
    )
    ratings: list[ProfileInput] = []
    for movie_id, score in cursor.fetchall():
        if isinstance(movie_id, bool) or not isinstance(movie_id, int) or movie_id <= 0:
            raise ValueError("invalid rating movie_id from PostgreSQL")
        value = float(score)
        if not 0.5 <= value <= 5.0 or value * 2 != round(value * 2):
            raise ValueError("invalid rating score from PostgreSQL")
        ratings.append(ProfileInput(movie_id, value, "RATING"))
    return ratings


def load_user_snapshot(connection: Connection, user_id: int) -> UserSnapshot:
    """Read profile, confirmed watches, and dismissals from one repeatable snapshot."""
    ratings = load_profile(connection, user_id)
    cursor = connection.cursor()
    cursor.execute(
        "SELECT DISTINCT movie_id FROM watch_records "
        "WHERE user_id = %s AND status IN ('WATCHED_UNRATED', 'RATED') ORDER BY movie_id",
        (user_id,),
    )
    watched = _positive_movie_ids(cursor.fetchall(), "watch record")
    cursor.execute(
        "SELECT movie_id FROM user_movie_dismissals WHERE user_id = %s ORDER BY movie_id",
        (user_id,),
    )
    dismissed = _positive_movie_ids(cursor.fetchall(), "dismissal")
    return UserSnapshot(ratings, frozenset(watched), frozenset(dismissed))


def merge_profile_inputs(snapshot: UserSnapshot, dismissal_score: float) -> list[ProfileInput]:
    """Add current dismissals as virtual negatives without overriding real ratings."""
    rated_movie_ids = {item.movie_id for item in snapshot.ratings}
    dismissals = [
        ProfileInput(movie_id, dismissal_score, "DISMISS")
        for movie_id in sorted(snapshot.dismissed_movie_ids - rated_movie_ids)
    ]
    return [*snapshot.ratings, *dismissals]


def _positive_movie_ids(rows: list[tuple[int, ...]], label: str) -> set[int]:
    result: set[int] = set()
    for row in rows:
        movie_id = row[0]
        if isinstance(movie_id, bool) or not isinstance(movie_id, int) or movie_id <= 0:
            raise ValueError(f"invalid {label} movie_id from PostgreSQL")
        result.add(movie_id)
    return result
