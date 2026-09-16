from feelm_consumer.postgres import (
    ONBOARDING_DISLIKE_SCORE,
    ONBOARDING_LIKE_SCORE,
    load_profile,
    load_user_snapshot,
    readonly_snapshot,
)


class FakeCursor:
    def __init__(self) -> None:
        self.query = ""

    def execute(self, query: str, _params: tuple[int, ...]) -> None:
        self.query = query

    def fetchall(self) -> list[tuple[int, float | str]]:
        if "ratings" in self.query:
            return [(5, 4.5)]
        if "onboarding_preferences" in self.query:
            return [(4, "LIKE"), (6, "DISLIKE")]
        if "watch_records" in self.query:
            return [(7,), (8,)]  # type: ignore[list-item]
        return [(9,)]  # type: ignore[list-item]


class FakeConnection:
    def __init__(self) -> None:
        self.commands: list[str] = []
        self.cursor_value = FakeCursor()
        self.closed = False

    def execute(self, query: str) -> None:
        self.commands.append(query)

    def cursor(self) -> FakeCursor:
        return self.cursor_value

    def close(self) -> None:
        self.closed = True


def test_readonly_snapshot_and_profile_load() -> None:
    connection = FakeConnection()
    with readonly_snapshot(lambda: connection) as snapshot:
        ratings, onboarding = load_profile(snapshot, 7)

    assert [(item.movie_id, item.score) for item in ratings] == [(5, 4.5)]
    assert [(item.movie_id, item.score) for item in onboarding] == [
        (4, ONBOARDING_LIKE_SCORE),
        (6, ONBOARDING_DISLIKE_SCORE),
    ]
    assert connection.commands == ["BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY", "COMMIT"]
    assert connection.closed


def test_user_snapshot_reads_confirmed_watches_and_dismissals() -> None:
    connection = FakeConnection()

    snapshot = load_user_snapshot(connection, 7)

    assert snapshot.watched_movie_ids == frozenset({7, 8})
    assert snapshot.dismissed_movie_ids == frozenset({9})
    assert snapshot.onboarding[0].score == ONBOARDING_LIKE_SCORE
