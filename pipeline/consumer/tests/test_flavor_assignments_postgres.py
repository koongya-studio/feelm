from __future__ import annotations

import os
import threading
import time
from types import SimpleNamespace

import numpy as np
import psycopg
import pytest

from feelm_consumer.flavor_assignments import FlavorAssignmentError, sync_flavor_assignments

TEST_DSN = os.environ.get("FLAVOR_ASSIGNMENT_TEST_DSN")
pytestmark = pytest.mark.skipif(not TEST_DSN, reason="FLAVOR_ASSIGNMENT_TEST_DSN is not configured")


def _bundle(
    movie_ids: list[int],
    tmdb_ids: list[int],
    taste_ids: list[int],
    mapped: list[bool],
):
    return SimpleNamespace(
        group_version="groups-test",
        catalog_version="catalog-test",
        mapping_revision="sha256:" + "a" * 64,
        service_movie_ids=np.asarray(movie_ids, dtype=np.int64),
        tmdb_ids=np.asarray(tmdb_ids, dtype=np.int64),
        taste_ids=np.asarray(taste_ids, dtype=np.int32),
        group_mapped=np.asarray(mapped, dtype=np.bool_),
    )


def _movie_flavors(connection: psycopg.Connection) -> list[tuple[int, int | None]]:
    return connection.execute(
        "SELECT id, representative_flavor_id FROM movies ORDER BY id"
    ).fetchall()


def _wait_until_loader_is_blocked(observer: psycopg.Connection) -> None:
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        row = observer.execute(
            """
            SELECT wait_event_type
            FROM pg_stat_activity
            WHERE application_name = 'flavor-assignment-test-loader'
            """
        ).fetchone()
        if row == ("Lock",):
            return
        time.sleep(0.05)
    raise AssertionError("assignment loader did not wait for the in-flight movie writer")


def _start_loader(bundle, outcomes: list[object]) -> threading.Thread:
    def run() -> None:
        try:
            with psycopg.connect(
                TEST_DSN, application_name="flavor-assignment-test-loader"
            ) as loader_connection:
                outcomes.append(
                    sync_flavor_assignments(
                        loader_connection,
                        bundle,
                        apply=True,
                        enforce_release_counts=False,
                    )
                )
        except Exception as error:  # noqa: BLE001 - assertion inspects the exact failure
            outcomes.append(error)

    thread = threading.Thread(target=run, daemon=True)
    thread.start()
    return thread


def test_postgres_dry_run_apply_failure_rollback_and_writer_serialization() -> None:
    bundle = _bundle([1, 2, 3], [101, 102, 103], [0, 1, 2], [True, False, True])

    with psycopg.connect(TEST_DSN) as connection:
        connection.execute(
            """
            CREATE TABLE popcorn_flavors (
                id bigint PRIMARY KEY,
                code varchar(30) NOT NULL UNIQUE,
                cluster_id integer NOT NULL UNIQUE
            );
            CREATE TABLE movies (
                id bigint PRIMARY KEY,
                tmdb_id bigint NOT NULL UNIQUE,
                representative_flavor_id bigint REFERENCES popcorn_flavors(id)
            );
            """
        )
        with connection.cursor() as cursor:
            cursor.executemany(
                "INSERT INTO popcorn_flavors(id,code,cluster_id) VALUES (%s,%s,%s)",
                [(index + 1, f"FLAVOR_{chr(65 + index)}", index) for index in range(8)],
            )
            cursor.executemany(
                "INSERT INTO movies(id,tmdb_id,representative_flavor_id) VALUES (%s,%s,%s)",
                [(1, 101, None), (2, 102, 2), (9, 109, 3)],
            )
        connection.commit()

        dry_run = sync_flavor_assignments(
            connection, bundle, apply=False, enforce_release_counts=False
        )
        assert dry_run.applied is False
        assert (dry_run.flavors_to_set, dry_run.flavors_to_clear) == (1, 1)
        assert (dry_run.database_only_rows, dry_run.bundle_only_rows) == (1, 1)
        assert _movie_flavors(connection) == [(1, None), (2, 2), (9, 3)]
        connection.commit()

        applied = sync_flavor_assignments(
            connection, bundle, apply=True, enforce_release_counts=False
        )
        assert applied.applied is True
        assert _movie_flavors(connection) == [(1, 1), (2, None), (9, 3)]
        connection.commit()

        rerun = sync_flavor_assignments(
            connection, bundle, apply=True, enforce_release_counts=False
        )
        assert (rerun.flavors_to_set, rerun.flavors_to_clear) == (0, 0)
        connection.commit()

        # A missing fixed flavor fails before writes and preserves the movie state.
        connection.execute("DELETE FROM popcorn_flavors WHERE cluster_id = 7")
        connection.commit()
        before = _movie_flavors(connection)
        with pytest.raises(FlavorAssignmentError, match="must contain exactly"):
            sync_flavor_assignments(connection, bundle, apply=True, enforce_release_counts=False)
        assert _movie_flavors(connection) == before
        connection.execute(
            "INSERT INTO popcorn_flavors(id,code,cluster_id) VALUES (8,'FLAVOR_H',7)"
        )
        connection.commit()

        # A duplicate assignment violates the real staging constraints and rolls back.
        duplicate_bundle = _bundle([1, 1], [101, 999], [0, 1], [True, True])
        before = _movie_flavors(connection)
        with pytest.raises(psycopg.errors.UniqueViolation):
            sync_flavor_assignments(
                connection, duplicate_bundle, apply=True, enforce_release_counts=False
            )
        assert _movie_flavors(connection) == before
        connection.commit()

        # An UPDATE failure after validation rolls the entire assignment back.
        connection.execute("UPDATE movies SET representative_flavor_id = NULL WHERE id = 1")
        connection.commit()
        connection.execute(
            """
            CREATE FUNCTION fail_flavor_update() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                RAISE EXCEPTION 'injected flavor update failure';
            END;
            $$;
            CREATE TRIGGER fail_flavor_update
            BEFORE UPDATE OF representative_flavor_id ON movies
            FOR EACH ROW EXECUTE FUNCTION fail_flavor_update();
            """
        )
        connection.commit()
        before = _movie_flavors(connection)
        with pytest.raises(psycopg.errors.RaiseException, match="injected flavor update failure"):
            sync_flavor_assignments(connection, bundle, apply=True, enforce_release_counts=False)
        assert _movie_flavors(connection) == before
        connection.execute("DROP TRIGGER fail_flavor_update ON movies")
        connection.execute("DROP FUNCTION fail_flavor_update()")
        connection.commit()

    # A writer already in flight completes before the loader's permanent-table snapshot.
    exact_bundle = _bundle([1, 4], [101, 104], [0, 3], [True, True])
    with (
        psycopg.connect(TEST_DSN) as writer,
        psycopg.connect(TEST_DSN, autocommit=True) as observer,
    ):
        writer.execute(
            "INSERT INTO movies(id,tmdb_id,representative_flavor_id) VALUES (4,104,NULL)"
        )
        outcomes: list[object] = []
        loader = _start_loader(exact_bundle, outcomes)
        _wait_until_loader_is_blocked(observer)
        writer.commit()
        loader.join(timeout=10)
        assert not loader.is_alive()
        assert len(outcomes) == 1 and not isinstance(outcomes[0], Exception)

    with psycopg.connect(TEST_DSN) as connection:
        assert connection.execute(
            "SELECT representative_flavor_id FROM movies WHERE id=4"
        ).fetchone() == (4,)
        connection.execute("DELETE FROM movies WHERE id=4")
        connection.commit()

    # The same boundary makes a concurrently committed ID rebind visible and rejects it.
    rebound_bundle = _bundle([1, 4], [101, 104], [0, 3], [True, True])
    with (
        psycopg.connect(TEST_DSN) as writer,
        psycopg.connect(TEST_DSN, autocommit=True) as observer,
    ):
        writer.execute(
            "INSERT INTO movies(id,tmdb_id,representative_flavor_id) VALUES (4,999,NULL)"
        )
        outcomes = []
        loader = _start_loader(rebound_bundle, outcomes)
        _wait_until_loader_is_blocked(observer)
        writer.commit()
        loader.join(timeout=10)
        assert not loader.is_alive()
        assert len(outcomes) == 1
        assert isinstance(outcomes[0], FlavorAssignmentError)
        assert "rebinding detected" in str(outcomes[0])
