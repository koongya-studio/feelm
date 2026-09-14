# ruff: noqa: E501

from __future__ import annotations

import logging
import posixpath
import re
from dataclasses import dataclass
from typing import Any

import numpy as np
import pyarrow as pa
import pyarrow.fs as pafs
import pyarrow.parquet as pq

LOG = logging.getLogger(__name__)


class ArtifactError(ValueError):
    pass


REVISION_PATTERN = re.compile(r"sha256:[0-9a-f]{64}\Z")


def _require_physical_types(table: pa.Table, expected: dict[str, pa.DataType], label: str) -> None:
    """Reject Parquet values that happen to coerce but violate the artifact schema."""
    for name, expected_type in expected.items():
        try:
            actual_type = table.schema.field(name).type
        except KeyError as exc:
            raise ArtifactError(f"{label} has missing columns") from exc
        if actual_type != expected_type:
            raise ArtifactError(f"{label} has incompatible physical types")


@dataclass(frozen=True)
class MovieMetadata:
    title: str
    genre_codes: tuple[str, ...]
    flavor_code: str | None


@dataclass(frozen=True)
class ArtifactBundle:
    model_version: str
    input_snapshot: str
    reg_param: float
    factors: dict[int, np.ndarray]
    service_to_movielens: dict[int, int]
    movie_metadata: dict[int, MovieMetadata]
    mapping_revision: str
    movie_metadata_revision: str


def _dataset(uri: str, child: str | None = None) -> Any:
    """Read a Parquet dataset without converting an HDFS URI to a local Path."""
    try:
        filesystem, root = pafs.FileSystem.from_uri(uri)
        path = posixpath.join(root, child) if child else root
        return pq.ParquetDataset(path, filesystem=filesystem).read()
    except (pa.ArrowException, TypeError, ValueError, OSError, FileNotFoundError) as exc:
        raise ArtifactError("artifact dataset is unavailable") from exc


def _revision(value: object, label: str) -> str:
    if not isinstance(value, str) or not REVISION_PATTERN.fullmatch(value):
        raise ArtifactError(f"invalid {label}")
    return value


def _single_metadata(uri: str) -> dict[str, object]:
    table = _dataset(uri, "metadata")
    if table.num_rows != 1:
        raise ArtifactError("metadata must contain exactly one row")
    return dict(table.to_pylist()[0])


def _load_movie_metadata(uri: str, input_snapshot: str, expected_revision: str) -> dict[int, MovieMetadata]:
    table = _dataset(uri)
    required = {"service_movie_id", "title", "genre_codes", "representative_flavor_code", "input_snapshot", "movie_metadata_revision"}
    if not required.issubset(table.column_names):
        raise ArtifactError("metadata snapshot has missing columns")
    movies: dict[int, MovieMetadata] = {}
    for row in table.to_pylist():
        movie_id, title, genre_codes = row["service_movie_id"], row["title"], row["genre_codes"]
        flavor_code = row["representative_flavor_code"]
        if (row["input_snapshot"] != input_snapshot or _revision(row["movie_metadata_revision"], "movie metadata revision") != expected_revision or not isinstance(movie_id, int) or movie_id <= 0 or movie_id in movies or not isinstance(title, str) or not title or not isinstance(genre_codes, list) or not all(isinstance(code, str) and code for code in genre_codes) or flavor_code is not None and not isinstance(flavor_code, str)):
            raise ArtifactError("invalid metadata snapshot")
        movies[movie_id] = MovieMetadata(title, tuple(genre_codes), flavor_code)
    if not movies:
        raise ArtifactError("empty metadata snapshot")
    return movies


def _load_mapping(uri: str, factors: dict[int, np.ndarray], expected_revision: str) -> dict[int, int]:
    table = _dataset(uri)
    required = {"service_movie_id", "movielens_movie_id", "mapping_status", "mapping_revision"}
    if not required.issubset(table.column_names):
        raise ArtifactError("mapping snapshot has missing columns")
    _require_physical_types(
        table,
        {
            "service_movie_id": pa.int64(),
            "movielens_movie_id": pa.int32(),
            "mapping_status": pa.string(),
            "mapping_revision": pa.string(),
        },
        "mapping snapshot",
    )
    result: dict[int, int] = {}
    matched: set[int] = set()
    seen_service_ids: set[int] = set()
    status_counts: dict[str, int] = {}
    factor_missing_count = 0
    for row in table.to_pylist():
        service_id, movielens_id = row["service_movie_id"], row["movielens_movie_id"]
        if _revision(row["mapping_revision"], "mapping revision") != expected_revision or not isinstance(service_id, int) or service_id <= 0 or service_id in seen_service_ids:
            raise ArtifactError("invalid service mapping")
        seen_service_ids.add(service_id)
        status = row["mapping_status"]
        if status not in {"MATCHED", "SERVICE_ONLY_CONTENT_BASED", "TMDB_ID_AMBIGUOUS"}:
            raise ArtifactError("invalid mapping status")
        status_counts[status] = status_counts.get(status, 0) + 1
        if status != "MATCHED":
            if movielens_id is not None:
                raise ArtifactError("non-MATCHED mapping must not have a MovieLens ID")
            continue
        if not isinstance(movielens_id, int) or movielens_id <= 0 or movielens_id in matched:
            raise ArtifactError("mapping must be one-to-one")
        matched.add(movielens_id)
        if movielens_id in factors:
            result[service_id] = movielens_id
        else:
            factor_missing_count += 1
    if not result:
        raise ArtifactError("empty artifact bundle")
    LOG.info(
        "artifact mapping loaded matched=%s service_only=%s ambiguous=%s factor_missing=%s",
        status_counts.get("MATCHED", 0),
        status_counts.get("SERVICE_ONLY_CONTENT_BASED", 0),
        status_counts.get("TMDB_ID_AMBIGUOUS", 0),
        factor_missing_count,
    )
    return result


def load_bundle(model_uri: str, mapping_uri: str, metadata_uri: str) -> ArtifactBundle:
    metadata = _single_metadata(model_uri)
    if metadata.get("contract_version") != "als-artifacts-v2" or metadata.get("artifact_stage") != "SERVING":
        raise ArtifactError("unsupported model artifact")
    model_version, rank, reg_param, input_snapshot = (metadata.get(key) for key in ("model_version", "factor_dimension", "reg_param", "input_snapshot"))
    mapping_revision = _revision(metadata.get("mapping_revision"), "mapping revision")
    movie_metadata_revision = _revision(metadata.get("movie_metadata_revision"), "movie metadata revision")
    if not isinstance(model_version, str) or not isinstance(rank, int) or rank <= 0 or not isinstance(reg_param, (int, float)) or not np.isfinite(reg_param) or reg_param < 0 or not isinstance(input_snapshot, str) or not input_snapshot:
        raise ArtifactError("invalid model metadata")
    factor_table = _dataset(model_uri, "item-factors")
    _require_physical_types(
        factor_table,
        {
            "movielens_movie_id": pa.int32(),
            "features": pa.list_(pa.float32()),
        },
        "item factors",
    )
    factors: dict[int, np.ndarray] = {}
    for row in factor_table.to_pylist():
        movie_id, values = row["movielens_movie_id"], row["features"]
        if not isinstance(movie_id, int) or movie_id <= 0 or movie_id in factors:
            raise ArtifactError("invalid or duplicate item factor id")
        vector = np.asarray(values, dtype=np.float64)
        if vector.shape != (rank,) or not np.isfinite(vector).all():
            raise ArtifactError("invalid item factor")
        factors[movie_id] = vector
    mapping = _load_mapping(mapping_uri, factors, mapping_revision)
    movies = _load_movie_metadata(metadata_uri, input_snapshot, movie_metadata_revision)
    if not set(mapping).issubset(movies):
        raise ArtifactError("mapped movie missing from metadata snapshot")
    return ArtifactBundle(model_version, input_snapshot, float(reg_param), factors, mapping, movies, mapping_revision, movie_metadata_revision)
