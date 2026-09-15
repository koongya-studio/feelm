from __future__ import annotations

from collections.abc import Callable
from contextlib import contextmanager
from typing import Protocol

from feelm_consumer.profile import ProfileInput


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


def load_profile(connection: Connection, user_id: int) -> tuple[list[ProfileInput], list[ProfileInput]]:
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
        ratings.append(ProfileInput(movie_id, value, "rating"))
    cursor.execute(
        "SELECT movie_id, preference FROM onboarding_preferences WHERE user_id = %s ORDER BY movie_id",
        (user_id,),
    )
    onboarding = []
    for movie_id, preference in cursor.fetchall():
        if isinstance(movie_id, bool) or not isinstance(movie_id, int) or movie_id <= 0:
            raise ValueError("invalid onboarding movie_id from PostgreSQL")
        if preference not in {"LIKE", "DISLIKE"}:
            raise ValueError("invalid onboarding preference from PostgreSQL")
        onboarding.append(ProfileInput(movie_id, 5.0 if preference == "LIKE" else 1.0, "onboarding"))
    return ratings, onboarding
