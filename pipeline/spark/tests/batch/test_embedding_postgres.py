from __future__ import annotations

import math

import pytest

from batch.embedding.e5 import DIMENSION
from batch.embedding.postgres import (
    _insert_staging,
    _validate_schema,
    _vector_literal,
    load,
    preflight,
)


class RecordingCursor:
    def __init__(self) -> None:
        self.batches: list[list[tuple[object, ...]]] = []

    def executemany(self, _query: str, params: list[tuple[object, ...]]) -> None:
        self.batches.append(params)


def _row(movie_id: int, vector: list[float] | None = None) -> tuple[object, ...]:
    return (movie_id, movie_id + 100, "sha256:mapping", "sha256:input", vector or _unit())


def _unit() -> list[float]:
    return [1.0] + [0.0] * (DIMENSION - 1)


def test_insert_staging_streams_rows_in_bounded_batches() -> None:
    cursor = RecordingCursor()
    _insert_staging(cursor, (_row(index) for index in range(1, 1_002)))

    assert [len(batch) for batch in cursor.batches] == [1_000, 1]
    assert cursor.batches[0][0][:2] == (1, 101)
    assert cursor.batches[1][0][:2] == (1_001, 1_101)


def test_insert_staging_rejects_empty_duplicate_and_invalid_rows() -> None:
    with pytest.raises(ValueError, match="must not be empty"):
        _insert_staging(RecordingCursor(), iter(()))
    with pytest.raises(ValueError, match="unique"):
        _insert_staging(RecordingCursor(), iter([_row(1), _row(1)]))
    with pytest.raises(ValueError, match="positive"):
        _insert_staging(RecordingCursor(), iter([_row(0)]))


def test_vector_literal_rejects_wrong_dimension_and_nonfinite_value() -> None:
    with pytest.raises(ValueError, match="768"):
        _vector_literal([0.0])
    invalid = _unit()
    invalid[0] = math.nan
    with pytest.raises(ValueError, match="finite"):
        _vector_literal(invalid)
    with pytest.raises(ValueError, match="L2-normalized"):
        _vector_literal([0.0] * DIMENSION)


VALID_COLUMNS = [
    ("id", "bigint", True),
    ("movie_id", "bigint", True),
    ("embedding", "vector(768)", True),
    ("model_version", "character varying(50)", True),
    ("updated_at", "timestamp with time zone", True),
]


class FakeDatabase:
    def __init__(
        self,
        counts: tuple[int, ...],
        columns: list[tuple[str, str, bool]] | None = None,
        constraints: tuple[bool, bool] = (True, True),
    ) -> None:
        self.results = iter([("movie_embeddings",), constraints, *((value,) for value in counts)])
        self.columns = VALID_COLUMNS if columns is None else columns
        self.queries: list[str] = []
        self.committed = False
        self.rolled_back = False

    def cursor(self):
        return self

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def execute(self, query, _params=None):
        self.queries.append(query)

    def executemany(self, query, _params):
        self.queries.append(query)

    def fetchone(self):
        return next(self.results)

    def fetchall(self):
        return self.columns

    def commit(self):
        self.committed = True

    def rollback(self):
        self.rolled_back = True


@pytest.mark.parametrize(
    ("counts", "message"),
    [
        ((1, 1, 0, 0, 1, 0, 0), "tmdb_id"),
        ((1, 1, 0, 0, 0, 1, 0), "different embedding artifact"),
        ((1, 1, 0, 0, 0, 0, 1), "different embedding artifact"),
    ],
)
def test_load_rolls_back_changed_mapping_or_same_version_artifact(counts, message) -> None:
    database = FakeDatabase(counts)
    with pytest.raises(ValueError, match=message):
        load(database, [_row(1)], "e5-base-d12875059715")
    assert database.rolled_back
    assert not database.committed
    assert not any("INSERT INTO movie_embeddings" in query for query in database.queries)


def test_load_accepts_exact_retry_without_replacing_vectors() -> None:
    database = FakeDatabase((1, 1, 0, 0, 0, 0, 0))
    report = load(database, [_row(1)], "e5-base-d12875059715")
    assert database.committed
    assert report.existing_rows == 1


@pytest.mark.parametrize(
    "columns",
    [
        [column for column in VALID_COLUMNS if column[0] != "model_version"],
        [(name, "integer" if name == "model_version" else kind, nullable)
         for name, kind, nullable in VALID_COLUMNS],
        [(name, "character varying(51)" if name == "model_version" else kind, nullable)
         for name, kind, nullable in VALID_COLUMNS],
        [(name, "text" if name == "updated_at" else kind, nullable)
         for name, kind, nullable in VALID_COLUMNS],
        [(name, kind, False if name == "embedding" else nullable)
         for name, kind, nullable in VALID_COLUMNS],
    ],
)
def test_schema_rejects_missing_or_mismatched_columns(columns) -> None:
    database = FakeDatabase((), columns=columns)
    with pytest.raises(ValueError, match="schema does not match"):
        _validate_schema(database)


@pytest.mark.parametrize("constraints", [(False, True), (True, False)])
def test_schema_rejects_missing_unique_or_foreign_key(constraints) -> None:
    database = FakeDatabase((), constraints=constraints)
    with pytest.raises(ValueError, match="UNIQUE and movies\\(id\\) FK"):
        preflight(database, [_row(1)], "e5-base-d12875059715")
    assert database.rolled_back
    assert not any("CREATE TEMP TABLE" in query for query in database.queries)
