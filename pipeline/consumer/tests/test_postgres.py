from feelm_consumer.postgres import (
    UserSnapshot,
    load_profile,
    load_user_snapshot,
    merge_profile_inputs,
    readonly_snapshot,
)
from feelm_consumer.profile import ProfileInput


class FakeCursor:
    def __init__(self) -> None:
        self.query = ""
        self.queries: list[str] = []

    def execute(self, query: str, _params: tuple[int, ...]) -> None:
        self.query = query
        self.queries.append(query)

    def fetchall(self) -> list[tuple[int, float | str]]:
        if "ratings" in self.query:
            return [(5, 4.5)]
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
        ratings = load_profile(snapshot, 7)

    assert [(item.movie_id, item.score) for item in ratings] == [(5, 4.5)]
    assert all("onboarding_preferences" not in query for query in connection.cursor_value.queries)
    assert connection.commands == ["BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY", "COMMIT"]
    assert connection.closed


def test_user_snapshot_reads_confirmed_watches_and_dismissals() -> None:
    connection = FakeConnection()

    snapshot = load_user_snapshot(connection, 7)

    assert snapshot.watched_movie_ids == frozenset({7, 8})
    assert snapshot.dismissed_movie_ids == frozenset({9})
    assert [(item.movie_id, item.score, item.source) for item in snapshot.ratings] == [(5, 4.5, "RATING")]
    assert all("onboarding_preferences" not in query for query in connection.cursor_value.queries)


def test_profile_inputs_keep_real_rating_and_add_only_unrated_dismissals() -> None:
    snapshot = UserSnapshot(
        [ProfileInput(5, 1.0, "RATING")],
        frozenset(),
        frozenset({5, 9}),
    )

    inputs = merge_profile_inputs(snapshot, 1.0)

    assert [(item.movie_id, item.score, item.source) for item in inputs] == [
        (5, 1.0, "RATING"),
        (9, 1.0, "DISMISS"),
    ]
