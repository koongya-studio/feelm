from feelm_consumer.postgres import load_profile, readonly_snapshot


class FakeCursor:
    def __init__(self) -> None:
        self.query = ""

    def execute(self, query: str, _params: tuple[int, ...]) -> None:
        self.query = query

    def fetchall(self) -> list[tuple[int, float | str]]:
        if "ratings" in self.query:
            return [(5, 4.5)]
        return [(4, "LIKE"), (6, "DISLIKE")]


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
    assert [(item.movie_id, item.score) for item in onboarding] == [(4, 5.0), (6, 1.0)]
    assert connection.commands == ["BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY", "COMMIT"]
    assert connection.closed
