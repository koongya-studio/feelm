from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any

import numpy as np

from feelm_consumer.group_artifacts import GroupBundle

EXPECTED_MOVIE_COUNT = 237_817
EXPECTED_MAPPED_COUNT = 207_240
EXPECTED_UNMAPPED_COUNT = 30_577
EXPECTED_FLAVOR_IDENTITIES = tuple((f"FLAVOR_{chr(65 + index)}", index) for index in range(8))

CREATE_STAGE_SQL = """
CREATE TEMP TABLE flavor_assignment_stage (
    service_movie_id bigint PRIMARY KEY,
    tmdb_id bigint NOT NULL UNIQUE,
    taste_id integer NOT NULL CHECK (taste_id BETWEEN 0 AND 7),
    group_mapped boolean NOT NULL
) ON COMMIT DROP
"""

COPY_STAGE_SQL = """
COPY flavor_assignment_stage (service_movie_id, tmdb_id, taste_id, group_mapped)
FROM STDIN
"""

LOCK_ASSIGNMENT_TABLES_SQL = """
LOCK TABLE public.movies, public.popcorn_flavors
IN SHARE ROW EXCLUSIVE MODE
"""

FLAVOR_ROWS_SQL = """
SELECT code, cluster_id, id
FROM public.popcorn_flavors
ORDER BY cluster_id
"""

REBIND_SQL = """
SELECT s.service_movie_id, s.tmdb_id, m.id, m.tmdb_id
FROM flavor_assignment_stage s
JOIN public.movies m
  ON m.id = s.service_movie_id OR m.tmdb_id = s.tmdb_id
WHERE m.id <> s.service_movie_id OR m.tmdb_id <> s.tmdb_id
ORDER BY s.service_movie_id, m.id
LIMIT 20
"""

SUMMARY_SQL = """
WITH exact_matches AS (
    SELECT s.*, m.representative_flavor_id, f.id AS target_flavor_id
    FROM flavor_assignment_stage s
    JOIN public.movies m
      ON m.id = s.service_movie_id AND m.tmdb_id = s.tmdb_id
    LEFT JOIN public.popcorn_flavors f ON f.cluster_id = s.taste_id
)
SELECT
    (SELECT count(*) FROM flavor_assignment_stage) AS bundle_rows,
    count(*) AS matched_rows,
    count(*) FILTER (WHERE group_mapped) AS mapped_rows,
    count(*) FILTER (WHERE NOT group_mapped) AS unmapped_rows,
    (SELECT count(*) FROM public.movies m
       WHERE NOT EXISTS (
         SELECT 1 FROM flavor_assignment_stage s
         WHERE s.service_movie_id = m.id AND s.tmdb_id = m.tmdb_id
       )) AS database_only_rows,
    (SELECT count(*) FROM flavor_assignment_stage s
       WHERE NOT EXISTS (
         SELECT 1 FROM public.movies m
         WHERE m.id = s.service_movie_id AND m.tmdb_id = s.tmdb_id
       )) AS bundle_only_rows,
    count(*) FILTER (
      WHERE group_mapped AND representative_flavor_id IS DISTINCT FROM target_flavor_id
    ) AS flavors_to_set,
    count(*) FILTER (
      WHERE NOT group_mapped AND representative_flavor_id IS NOT NULL
    ) AS flavors_to_clear
FROM exact_matches
"""

SET_FLAVORS_SQL = """
UPDATE public.movies m
SET representative_flavor_id = f.id
FROM flavor_assignment_stage s
JOIN public.popcorn_flavors f ON f.cluster_id = s.taste_id
WHERE s.group_mapped
  AND m.id = s.service_movie_id
  AND m.tmdb_id = s.tmdb_id
  AND m.representative_flavor_id IS DISTINCT FROM f.id
"""

CLEAR_FLAVORS_SQL = """
UPDATE public.movies m
SET representative_flavor_id = NULL
FROM flavor_assignment_stage s
WHERE NOT s.group_mapped
  AND m.id = s.service_movie_id
  AND m.tmdb_id = s.tmdb_id
  AND m.representative_flavor_id IS NOT NULL
"""


class FlavorAssignmentError(RuntimeError):
    """The bundle or database cannot be safely synchronized."""


@dataclass(frozen=True)
class FlavorAssignmentReport:
    group_version: str
    catalog_version: str
    mapping_revision: str
    applied: bool
    bundle_rows: int
    matched_rows: int
    mapped_rows: int
    unmapped_rows: int
    database_only_rows: int
    bundle_only_rows: int
    flavors_to_set: int
    flavors_to_clear: int
    unchanged_rows: int

    def to_dict(self) -> dict[str, str | bool | int]:
        return asdict(self)


class _DryRunRollback(Exception):
    def __init__(self, report: FlavorAssignmentReport) -> None:
        super().__init__("dry-run rollback")
        self.report = report


def validate_release_bundle(bundle: GroupBundle) -> None:
    movie_count = len(bundle.service_movie_ids)
    mapped_count = int(np.count_nonzero(bundle.group_mapped))
    unmapped_count = movie_count - mapped_count
    if (
        movie_count != EXPECTED_MOVIE_COUNT
        or mapped_count != EXPECTED_MAPPED_COUNT
        or unmapped_count != EXPECTED_UNMAPPED_COUNT
    ):
        raise FlavorAssignmentError(
            "unexpected S15P21E106-439 assignment counts: "
            f"movies={movie_count}, mapped={mapped_count}, unmapped={unmapped_count}"
        )


def _assignment_rows(bundle: GroupBundle):
    return zip(
        (int(value) for value in bundle.service_movie_ids),
        (int(value) for value in bundle.tmdb_ids),
        (int(value) for value in bundle.taste_ids),
        (bool(value) for value in bundle.group_mapped),
        strict=True,
    )


def _validate_flavors(rows: list[tuple[str, int, int]]) -> None:
    identities = tuple((str(code), int(cluster_id)) for code, cluster_id, _ in rows)
    if identities != EXPECTED_FLAVOR_IDENTITIES:
        raise FlavorAssignmentError(
            "popcorn_flavors must contain exactly FLAVOR_A..H mapped to cluster_id 0..7"
        )


def _report(bundle: GroupBundle, summary: tuple[Any, ...], *, applied: bool) -> FlavorAssignmentReport:
    values = tuple(int(value) for value in summary)
    if len(values) != 8:
        raise FlavorAssignmentError("invalid assignment summary returned by PostgreSQL")
    bundle_rows, matched, mapped, unmapped, database_only, bundle_only, to_set, to_clear = values
    if bundle_rows != len(bundle.service_movie_ids) or matched != mapped + unmapped:
        raise FlavorAssignmentError("assignment staging counts are inconsistent")
    return FlavorAssignmentReport(
        group_version=bundle.group_version,
        catalog_version=bundle.catalog_version,
        mapping_revision=bundle.mapping_revision,
        applied=applied,
        bundle_rows=bundle_rows,
        matched_rows=matched,
        mapped_rows=mapped,
        unmapped_rows=unmapped,
        database_only_rows=database_only,
        bundle_only_rows=bundle_only,
        flavors_to_set=to_set,
        flavors_to_clear=to_clear,
        unchanged_rows=matched - to_set - to_clear,
    )


def sync_flavor_assignments(
    connection: Any,
    bundle: GroupBundle,
    *,
    apply: bool = False,
    enforce_release_counts: bool = True,
) -> FlavorAssignmentReport:
    """Validate and optionally apply the fixed 439 taste assignments atomically.

    Dry-run is the default and is forcibly rolled back, including the temporary
    staging table. The caller owns the connection and decides how credentials are
    supplied.
    """
    if enforce_release_counts:
        validate_release_bundle(bundle)

    try:
        with connection.transaction():
            with connection.cursor() as cursor:
                cursor.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED")
                cursor.execute(CREATE_STAGE_SQL)
                with cursor.copy(COPY_STAGE_SQL) as copy:
                    for row in _assignment_rows(bundle):
                        copy.write_row(row)

                # Staging is intentionally completed before the lock so the service write
                # pause covers only validation and updates. READ COMMITTED takes the first
                # permanent-table snapshot after the lock is granted, so a writer that was
                # already in flight is included rather than becoming an invisible phantom.
                cursor.execute(LOCK_ASSIGNMENT_TABLES_SQL)
                cursor.execute(FLAVOR_ROWS_SQL)
                _validate_flavors(cursor.fetchall())

                cursor.execute(REBIND_SQL)
                rebound = cursor.fetchall()
                if rebound:
                    raise FlavorAssignmentError(
                        "service_movie_id/tmdb_id rebinding detected; sample=" + repr(rebound)
                    )

                cursor.execute(SUMMARY_SQL)
                summary = cursor.fetchone()
                if summary is None:
                    raise FlavorAssignmentError("assignment summary is unavailable")
                report = _report(bundle, summary, applied=apply)

                if not apply:
                    raise _DryRunRollback(report)

                cursor.execute(SET_FLAVORS_SQL)
                set_count = int(cursor.rowcount)
                cursor.execute(CLEAR_FLAVORS_SQL)
                clear_count = int(cursor.rowcount)
                if set_count != report.flavors_to_set or clear_count != report.flavors_to_clear:
                    raise FlavorAssignmentError(
                        "assignment update counts changed during transaction: "
                        f"set={set_count}/{report.flavors_to_set}, "
                        f"clear={clear_count}/{report.flavors_to_clear}"
                    )
                return report
    except _DryRunRollback as rollback:
        return rollback.report

    raise FlavorAssignmentError("assignment transaction ended without a report")
