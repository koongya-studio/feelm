"""Transactional pgvector loader for validated E5 embedding artifacts."""

from __future__ import annotations

import math
from collections.abc import Iterable, Iterator, Sequence
from dataclasses import dataclass
from typing import Protocol

from batch.embedding.e5 import DIMENSION


class Cursor(Protocol):
    def execute(self, query: str, params: tuple[object, ...] | None = None) -> None: ...

    def executemany(
        self, query: str, params_seq: Sequence[tuple[object, ...]]
    ) -> None: ...

    def fetchall(self) -> list[tuple[object, ...]]: ...

    def fetchone(self) -> tuple[object, ...] | None: ...

    def __enter__(self) -> Cursor: ...

    def __exit__(self, *args: object) -> None: ...


class Connection(Protocol):
    def cursor(self) -> Cursor: ...

    def commit(self) -> None: ...

    def rollback(self) -> None: ...

    def close(self) -> None: ...


@dataclass(frozen=True)
class LoadReport:
    rows: int
    existing_rows: int
    missing_movies: int
    other_model_rows: int
    mismatched_movie_ids: int
    different_vectors: int
    outside_rows: int


def preflight(
    connection: Connection, rows: Iterable[tuple[object, ...]], model_version: str
) -> LoadReport:
    """Validate the target schema and artifact rows without modifying PostgreSQL."""
    _validate_model_version(model_version)
    try:
        with connection.cursor() as cursor:
            _validate_schema(cursor)
            _create_staging(cursor)
            _insert_staging(cursor, rows)
            report = _report(cursor, model_version)
        return report
    finally:
        # The dry-run must leave neither a temp-table transaction nor a
        # partially staged row behind, even when artifact validation fails.
        connection.rollback()


def load(
    connection: Connection, rows: Iterable[tuple[object, ...]], model_version: str
) -> LoadReport:
    """Atomically upsert one homogeneous embedding version or leave DB unchanged."""
    _validate_model_version(model_version)
    try:
        with connection.cursor() as cursor:
            _validate_schema(cursor)
            cursor.execute("LOCK TABLE movie_embeddings IN SHARE ROW EXCLUSIVE MODE")
            _create_staging(cursor)
            _insert_staging(cursor, rows)
            report = _report(cursor, model_version)
            if report.missing_movies:
                raise ValueError("embedding artifact references movies absent from PostgreSQL")
            if report.other_model_rows:
                raise ValueError("movie_embeddings contains a different model_version")
            if report.mismatched_movie_ids:
                raise ValueError("embedding artifact movie IDs no longer match movies.tmdb_id")
            if report.different_vectors or report.outside_rows or (
                report.existing_rows and report.existing_rows != report.rows
            ):
                raise ValueError("model_version already identifies a different embedding artifact")
            cursor.execute(
                """
                INSERT INTO movie_embeddings (movie_id, embedding, model_version)
                SELECT movie_id, embedding, %s
                FROM embedding_load_stage
                ON CONFLICT (movie_id) DO UPDATE
                SET embedding = EXCLUDED.embedding,
                    model_version = EXCLUDED.model_version,
                    updated_at = now()
                WHERE movie_embeddings.embedding IS DISTINCT FROM EXCLUDED.embedding
                   OR movie_embeddings.model_version IS DISTINCT FROM EXCLUDED.model_version
                """,
                (model_version,),
            )
        connection.commit()
        return report
    except BaseException:
        connection.rollback()
        raise


def _validate_schema(cursor: Cursor) -> None:
    cursor.execute("SELECT to_regclass('public.movie_embeddings')")
    row = cursor.fetchone()
    if row is None or row[0] is None:
        raise ValueError("movie_embeddings table is not present")
    cursor.execute(
        """
        SELECT attname, format_type(atttypid, atttypmod), attnotnull
        FROM pg_attribute
        WHERE attrelid = 'public.movie_embeddings'::regclass
          AND attnum > 0 AND NOT attisdropped
        """
    )
    columns = {name: (type_name, not_null) for name, type_name, not_null in cursor.fetchall()}
    expected = {
        "id": "bigint",
        "movie_id": "bigint",
        "embedding": f"vector({DIMENSION})",
        "model_version": "character varying(50)",
        "updated_at": "timestamp with time zone",
    }
    for name, type_name in expected.items():
        if columns.get(name) != (type_name, True):
            raise ValueError(f"movie_embeddings schema does not match {name} {type_name} NOT NULL")
    cursor.execute(
        """
        SELECT
            EXISTS (
                SELECT 1 FROM pg_constraint constraint_row
                WHERE constraint_row.conrelid = 'public.movie_embeddings'::regclass
                  AND constraint_row.contype = 'u'
                  AND constraint_row.conkey = ARRAY[
                      (SELECT attnum FROM pg_attribute
                       WHERE attrelid = 'public.movie_embeddings'::regclass
                         AND attname = 'movie_id' AND NOT attisdropped)
                  ]::smallint[]
            ),
            EXISTS (
                SELECT 1 FROM pg_constraint constraint_row
                WHERE constraint_row.conrelid = 'public.movie_embeddings'::regclass
                  AND constraint_row.contype = 'f'
                  AND constraint_row.conkey = ARRAY[
                      (SELECT attnum FROM pg_attribute
                       WHERE attrelid = 'public.movie_embeddings'::regclass
                         AND attname = 'movie_id' AND NOT attisdropped)
                  ]::smallint[]
                  AND constraint_row.confrelid = 'public.movies'::regclass
                  AND constraint_row.confkey = ARRAY[
                      (SELECT attnum FROM pg_attribute
                       WHERE attrelid = 'public.movies'::regclass
                         AND attname = 'id' AND NOT attisdropped)
                  ]::smallint[]
                  AND constraint_row.confdeltype = 'c'
            )
        """
    )
    constraints = cursor.fetchone()
    if constraints is None or constraints != (True, True):
        raise ValueError(
            "movie_embeddings schema requires movie_id UNIQUE and movies(id) FK CASCADE"
        )


def _create_staging(cursor: Cursor) -> None:
    cursor.execute(
        f"""
        CREATE TEMP TABLE embedding_load_stage (
            movie_id bigint PRIMARY KEY,
            tmdb_id integer NOT NULL,
            embedding vector({DIMENSION}) NOT NULL
        ) ON COMMIT DROP
        """
    )


def _insert_staging(cursor: Cursor, rows: Iterable[tuple[object, ...]]) -> None:
    """Validate while streaming, retaining only movie IDs and one DB batch."""
    seen_movie_ids: set[int] = set()
    inserted = 0
    row_iter = iter(rows)
    while batch := list(_take(row_iter, 1_000)):
        parameters: list[tuple[object, ...]] = []
        for row in batch:
            movie_id, tmdb_id, embedding = _validated_row(row, seen_movie_ids)
            parameters.append((movie_id, tmdb_id, _vector_literal(embedding)))
            inserted += 1
        cursor.executemany(
            "INSERT INTO embedding_load_stage (movie_id, tmdb_id, embedding) "
            "VALUES (%s, %s, %s::vector)",
            parameters,
        )
    if inserted == 0:
        raise ValueError("embedding load rows must not be empty")


def _report(cursor: Cursor, model_version: str) -> LoadReport:
    cursor.execute("SELECT count(*) FROM embedding_load_stage")
    rows = int(cursor.fetchone()[0])
    cursor.execute(
        """
        SELECT count(*)
        FROM embedding_load_stage stage
        JOIN movie_embeddings target ON target.movie_id = stage.movie_id
        """
    )
    existing_rows = int(cursor.fetchone()[0])
    cursor.execute(
        """
        SELECT count(*)
        FROM embedding_load_stage stage
        LEFT JOIN movies movie ON movie.id = stage.movie_id
        WHERE movie.id IS NULL
        """
    )
    missing_movies = int(cursor.fetchone()[0])
    cursor.execute(
        "SELECT count(*) FROM movie_embeddings WHERE model_version <> %s", (model_version,)
    )
    other_model_rows = int(cursor.fetchone()[0])
    cursor.execute(
        """
        SELECT count(*) FROM embedding_load_stage stage
        JOIN movies movie ON movie.id = stage.movie_id
        WHERE movie.tmdb_id <> stage.tmdb_id
        """
    )
    mismatched_movie_ids = int(cursor.fetchone()[0])
    cursor.execute(
        """
        SELECT count(*) FROM embedding_load_stage stage
        JOIN movie_embeddings target ON target.movie_id = stage.movie_id
        WHERE target.embedding::text IS DISTINCT FROM stage.embedding::text
        """
    )
    different_vectors = int(cursor.fetchone()[0])
    cursor.execute(
        """
        SELECT count(*) FROM movie_embeddings target
        LEFT JOIN embedding_load_stage stage ON stage.movie_id = target.movie_id
        WHERE stage.movie_id IS NULL
        """
    )
    outside_rows = int(cursor.fetchone()[0])
    return LoadReport(
        rows, existing_rows, missing_movies, other_model_rows,
        mismatched_movie_ids, different_vectors, outside_rows,
    )


def _validate_model_version(value: str) -> None:
    if not value or len(value) > 50:
        raise ValueError("model_version must be a non-empty string of at most 50 characters")


def _take(rows: Iterator[tuple[object, ...]], size: int) -> Iterator[tuple[object, ...]]:
    for _ in range(size):
        try:
            yield next(rows)
        except StopIteration:
            return


def _validated_row(
    row: tuple[object, ...], seen_movie_ids: set[int]
) -> tuple[int, int, object]:
    if len(row) != 5:
        raise ValueError("embedding load rows must contain the artifact's five columns")
    movie_id = row[0]
    if isinstance(movie_id, bool) or not isinstance(movie_id, int) or movie_id <= 0:
        raise ValueError("embedding load rows must have positive movie IDs")
    if movie_id in seen_movie_ids:
        raise ValueError("embedding load rows must have unique movie IDs")
    seen_movie_ids.add(movie_id)
    tmdb_id = row[1]
    if (
        isinstance(tmdb_id, bool)
        or not isinstance(tmdb_id, int)
        or not 0 < tmdb_id <= 2_147_483_647
    ):
        raise ValueError("embedding load rows must have positive integer TMDb IDs")
    return movie_id, tmdb_id, row[4]


def _vector_literal(vector: object) -> str:
    if not isinstance(vector, list) or len(vector) != DIMENSION:
        raise ValueError(f"embedding load vector must contain {DIMENSION} values")
    values = [float(value) for value in vector]
    if any(not math.isfinite(value) for value in values):
        raise ValueError("embedding load vector values must be finite")
    norm = math.sqrt(sum(value * value for value in values))
    if abs(norm - 1.0) > 1e-5:
        raise ValueError("embedding load vector must be L2-normalized")
    return "[" + ",".join(str(value) for value in values) + "]"
