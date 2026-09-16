from __future__ import annotations

from contextlib import AbstractContextManager
from types import SimpleNamespace

import numpy as np
import pytest

from feelm_consumer.flavor_assignments import (
    FlavorAssignmentError,
    sync_flavor_assignments,
    validate_release_bundle,
)


def _bundle(*, rows: int = 2, mapped: tuple[bool, ...] = (True, False)):
    return SimpleNamespace(
        group_version="groups-v1",
        catalog_version="catalog-v1",
        mapping_revision="sha256:" + "a" * 64,
        service_movie_ids=np.arange(1, rows + 1, dtype=np.int64),
        tmdb_ids=np.arange(101, 101 + rows, dtype=np.int64),
        taste_ids=np.arange(rows, dtype=np.int32) % 8,
        group_mapped=np.asarray(mapped, dtype=np.bool_),
    )


class _Context(AbstractContextManager):
    def __init__(self, target, *, suppress: bool = False) -> None:
        self.target = target
        self.suppress = suppress

    def __enter__(self):
        return self.target

    def __exit__(self, exc_type, exc, traceback):
        return self.suppress


class _Copy:
    def __init__(self) -> None:
        self.rows: list[tuple[int, int, int, bool]] = []

    def write_row(self, row) -> None:
        self.rows.append(tuple(row))


class _Cursor:
    def __init__(self, *, rebound=(), summary=(2, 2, 1, 1, 0, 0, 1, 0)) -> None:
        self.rebound = list(rebound)
        self.summary = summary
        self.last_sql = ""
        self.rowcount = -1
        self.copy_target = _Copy()
        self.executed: list[str] = []

    def execute(self, sql: str) -> None:
        self.last_sql = sql
        self.executed.append(sql)
        if sql.lstrip().startswith("UPDATE public.movies m\nSET representative_flavor_id = f.id"):
            self.rowcount = self.summary[6]
        elif sql.lstrip().startswith("UPDATE public.movies m\nSET representative_flavor_id = NULL"):
            self.rowcount = self.summary[7]

    def fetchall(self):
        if "FROM public.popcorn_flavors" in self.last_sql and "ORDER BY cluster_id" in self.last_sql:
            return [(f"FLAVOR_{chr(65 + index)}", index, index + 1) for index in range(8)]
        return self.rebound

    def fetchone(self):
        return self.summary

    def copy(self, _sql: str):
        return _Context(self.copy_target)


class _Connection:
    def __init__(self, cursor: _Cursor) -> None:
        self.cursor_value = cursor
        self.rolled_back = False
        self.committed = False

    def cursor(self):
        return _Context(self.cursor_value)

    def transaction(self):
        connection = self

        class Transaction(AbstractContextManager):
            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, traceback):
                if exc_type is None:
                    connection.committed = True
                else:
                    connection.rolled_back = True
                return False

        return Transaction()


def test_dry_run_stages_all_rows_and_forces_rollback() -> None:
    cursor = _Cursor()
    connection = _Connection(cursor)

    report = sync_flavor_assignments(
        connection, _bundle(), apply=False, enforce_release_counts=False
    )

    assert report.applied is False
    assert report.flavors_to_set == 1
    assert report.unchanged_rows == 1
    assert cursor.copy_target.rows == [(1, 101, 0, True), (2, 102, 1, False)]
    assert connection.rolled_back is True
    assert connection.committed is False
    assert not any(sql.lstrip().startswith("UPDATE") for sql in cursor.executed)


def test_apply_updates_and_commits_only_after_counts_match() -> None:
    cursor = _Cursor(summary=(2, 2, 1, 1, 3, 0, 1, 1))
    connection = _Connection(cursor)

    report = sync_flavor_assignments(
        connection, _bundle(), apply=True, enforce_release_counts=False
    )

    assert report.applied is True
    assert report.database_only_rows == 3
    assert report.flavors_to_clear == 1
    assert connection.committed is True
    assert connection.rolled_back is False
    assert sum(sql.lstrip().startswith("UPDATE") for sql in cursor.executed) == 2
    lock_index = next(
        index for index, sql in enumerate(cursor.executed) if "LOCK TABLE public.movies" in sql
    )
    flavor_read_index = next(
        index for index, sql in enumerate(cursor.executed) if "FROM public.popcorn_flavors" in sql
    )
    assert lock_index < flavor_read_index


def test_rebinding_aborts_before_summary_or_updates() -> None:
    cursor = _Cursor(rebound=[(1, 101, 1, 999)])
    connection = _Connection(cursor)

    with pytest.raises(FlavorAssignmentError, match="rebinding detected"):
        sync_flavor_assignments(
            connection, _bundle(), apply=True, enforce_release_counts=False
        )

    assert connection.rolled_back is True
    assert not any(sql.lstrip().startswith("UPDATE") for sql in cursor.executed)


def test_release_count_guard_rejects_non439_bundle() -> None:
    with pytest.raises(FlavorAssignmentError, match="unexpected S15P21E106-439"):
        validate_release_bundle(_bundle())


def test_inconsistent_update_count_rolls_back() -> None:
    cursor = _Cursor(summary=(2, 2, 1, 1, 0, 0, 1, 0))
    connection = _Connection(cursor)
    original_execute = cursor.execute

    def execute_with_changed_count(sql: str) -> None:
        original_execute(sql)
        if sql.lstrip().startswith("UPDATE public.movies m\nSET representative_flavor_id = f.id"):
            cursor.rowcount = 0

    cursor.execute = execute_with_changed_count  # type: ignore[method-assign]

    with pytest.raises(FlavorAssignmentError, match="update counts changed"):
        sync_flavor_assignments(
            connection, _bundle(), apply=True, enforce_release_counts=False
        )

    assert connection.rolled_back is True
