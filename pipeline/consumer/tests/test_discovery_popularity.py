from __future__ import annotations

from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq
import pytest

from feelm_consumer.artifacts import ArtifactError
from feelm_consumer.discovery_popularity import export_discovery_popularity, load_discovery_popularity

REVISION = "sha256:" + "a" * 64


def _catalog(path: Path) -> Path:
    pq.write_table(
        pa.table(
            {
                "service_movie_id": pa.array([1, 2, 3, 4], type=pa.int64()),
                "tmdb_id": pa.array([11, 22, 33, 44], type=pa.int64()),
                "raw_vote_count_number": [100.0, 10.0, 10.0, 0.0],
            }
        ),
        path,
    )
    return path


def _population(path: Path) -> Path:
    pq.write_table(
        pa.table(
            {
                "kobis_movie_code": ["20260001", "20260002", "20260003"],
                "last_observed_cumulative_admissions": pa.array([1_000, 100, 10], type=pa.int64()),
            }
        ),
        path,
    )
    return path


def _verified(path: Path) -> Path:
    pq.write_table(
        pa.table(
            {
                "service_movie_id": pa.array([4, 2], type=pa.int64()),
                "tmdb_id": pa.array([44, 22], type=pa.int64()),
                "kobis_movie_code": ["20260001", "20260003"],
                "cumulative_admissions": pa.array([1_000, 10], type=pa.int64()),
                "verification_status": ["VERIFIED", "VERIFIED"],
            }
        ),
        path,
    )
    return path


def test_export_uses_source_percentiles_max_and_reloads(tmp_path: Path) -> None:
    target = export_discovery_popularity(
        catalog_path=_catalog(tmp_path / "catalog.parquet"),
        kobis_population_path=_population(tmp_path / "population.parquet"),
        verified_kobis_path=_verified(tmp_path / "verified.parquet"),
        output_root=tmp_path / "out",
        popularity_version="discovery-popularity-v1",
        mapping_revision=REVISION,
    )

    bundle = load_discovery_popularity(target.as_uri(), expected_mapping_revision=REVISION)
    assert [item.movie_id for item in bundle.candidates] == [1, 4, 2, 3]
    assert bundle.service_movie_ids.tolist() == [1, 4, 2, 3]
    assert bundle.tmdb_ids.tolist() == [11, 44, 22, 33]
    assert bundle.service_movie_ids.flags.writeable is False
    assert bundle.tmdb_ids.flags.writeable is False
    assert bundle.candidates[0].score == pytest.approx(1.0)
    assert bundle.candidates[1].score == pytest.approx(1.0)
    assert bundle.candidates[2].score == bundle.candidates[3].score == pytest.approx(0.5)


def test_verified_admissions_must_match_population(tmp_path: Path) -> None:
    verified = pq.read_table(_verified(tmp_path / "verified.parquet")).set_column(
        3,
        "cumulative_admissions",
        pa.array([999, 10], type=pa.int64()),
    )
    pq.write_table(verified, tmp_path / "mismatch.parquet")
    with pytest.raises(ArtifactError, match="match the full population"):
        export_discovery_popularity(
            catalog_path=_catalog(tmp_path / "catalog.parquet"),
            kobis_population_path=_population(tmp_path / "population.parquet"),
            verified_kobis_path=tmp_path / "mismatch.parquet",
            output_root=tmp_path / "out",
            popularity_version="discovery-popularity-v1",
            mapping_revision=REVISION,
        )
