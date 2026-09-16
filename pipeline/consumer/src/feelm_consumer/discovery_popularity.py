from __future__ import annotations

import contextlib
import hashlib
import io
import json
import math
import os
import posixpath
import re
import shutil
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

import numpy as np
import pyarrow as pa
import pyarrow.fs as pafs
import pyarrow.parquet as pq

from feelm_consumer.artifacts import REVISION_PATTERN, ArtifactError
from feelm_consumer.ranking import RankedMovie

CONTRACT_VERSION = "discovery-popularity-artifacts-v1"
EXPECTED_FILES = frozenset({"_SUCCESS", "metadata.json", "ranking.parquet"})
VERSION_PATTERN = re.compile(r"[a-z0-9][a-z0-9._-]{0,127}\Z")
MAX_CONTROL_FILE_BYTES = 1024 * 1024
MAX_RANKING_FILE_BYTES = 64 * 1024 * 1024

RANKING_SCHEMA = pa.schema(
    [
        pa.field("service_movie_id", pa.int64(), nullable=False),
        pa.field("tmdb_id", pa.int64(), nullable=False),
        pa.field("global_rank", pa.int32(), nullable=False),
        pa.field("popularity_score", pa.float64(), nullable=False),
        pa.field("tmdb_vote_percentile", pa.float64(), nullable=False),
        pa.field("tmdb_vote_count", pa.int64(), nullable=False),
        pa.field("kobis_admissions_percentile", pa.float64()),
        pa.field("kobis_cumulative_admissions", pa.int64()),
        pa.field("kobis_movie_code", pa.string()),
        pa.field("popularity_version", pa.string(), nullable=False),
        pa.field("mapping_revision", pa.string(), nullable=False),
    ]
)


@dataclass(frozen=True)
class DiscoveryPopularityBundle:
    popularity_version: str
    mapping_revision: str
    candidates: tuple[RankedMovie, ...]
    service_movie_ids: np.ndarray
    tmdb_ids: np.ndarray


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def _write_json(path: Path, value: object) -> None:
    path.write_bytes(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True, allow_nan=False).encode("utf-8") + b"\n"
    )


def _manifest_entry(path: Path) -> dict[str, object]:
    return {"path": path.name, "bytes": path.stat().st_size, "sha256": _sha256(path)}


def _tie_aware_percentiles(values: np.ndarray) -> np.ndarray:
    if values.ndim != 1 or not len(values) or not np.isfinite(values).all():
        raise ArtifactError("popularity values must be a non-empty finite vector")
    order = np.argsort(values, kind="mergesort")
    scores = np.empty(len(values), dtype=np.float64)
    start = 0
    while start < len(order):
        end = start + 1
        while end < len(order) and values[order[end]] == values[order[start]]:
            end += 1
        midpoint = start + 0.5 * (end - start - 1)
        scores[order[start:end]] = midpoint / max(1, len(order) - 1)
        start = end
    return scores


def _catalog(path: Path) -> tuple[list[dict[str, object]], dict[int, int]]:
    try:
        table = pq.read_table(path, columns=["service_movie_id", "tmdb_id", "raw_vote_count_number"])
    except (OSError, pa.ArrowException) as exc:
        raise ArtifactError("TMDB catalog is unavailable or invalid") from exc
    rows = table.to_pylist()
    if not rows:
        raise ArtifactError("TMDB catalog must not be empty")
    service_ids: set[int] = set()
    tmdb_ids: set[int] = set()
    pairs: dict[int, int] = {}
    for row in rows:
        service_id = row["service_movie_id"]
        tmdb_id = row["tmdb_id"]
        raw_count = row["raw_vote_count_number"]
        if (
            isinstance(service_id, bool)
            or not isinstance(service_id, int)
            or service_id <= 0
            or isinstance(tmdb_id, bool)
            or not isinstance(tmdb_id, int)
            or tmdb_id <= 0
            or isinstance(raw_count, bool)
            or not isinstance(raw_count, int | float)
            or not math.isfinite(float(raw_count))
            or raw_count < 0
            or raw_count != int(raw_count)
            or service_id in service_ids
            or tmdb_id in tmdb_ids
        ):
            raise ArtifactError("TMDB catalog has invalid or duplicate popularity rows")
        row["raw_vote_count_number"] = int(raw_count)
        service_ids.add(service_id)
        tmdb_ids.add(tmdb_id)
        pairs[service_id] = tmdb_id
    return rows, pairs


def _kobis_population(path: Path) -> tuple[dict[str, tuple[int, float]], int]:
    try:
        table = pq.read_table(path, columns=["kobis_movie_code", "last_observed_cumulative_admissions"])
    except (OSError, pa.ArrowException) as exc:
        raise ArtifactError("KOBIS population rollup is unavailable or invalid") from exc
    rows = table.to_pylist()
    codes: list[str] = []
    admissions: list[int] = []
    for row in rows:
        code = row["kobis_movie_code"]
        value = row["last_observed_cumulative_admissions"]
        if (
            not isinstance(code, str)
            or re.fullmatch(r"[0-9A-Z]{8}", code) is None
            or isinstance(value, bool)
            or not isinstance(value, int)
            or value <= 0
        ):
            raise ArtifactError("KOBIS population requires positive admissions and unique movie codes")
        codes.append(code)
        admissions.append(value)
    if not codes or len(set(codes)) != len(codes):
        raise ArtifactError("KOBIS population requires positive admissions and unique movie codes")
    scores = _tie_aware_percentiles(np.asarray(admissions, dtype=np.float64))
    population = {
        code: (value, float(score)) for code, value, score in zip(codes, admissions, scores, strict=True)
    }
    return population, len(codes)


def _verified_kobis(
    path: Path,
    catalog_pairs: dict[int, int],
    population: dict[str, tuple[int, float]],
) -> dict[int, tuple[str, int, float]]:
    try:
        table = pq.read_table(
            path,
            columns=[
                "service_movie_id",
                "tmdb_id",
                "kobis_movie_code",
                "cumulative_admissions",
                "verification_status",
            ],
        )
    except (OSError, pa.ArrowException) as exc:
        raise ArtifactError("verified KOBIS links are unavailable or invalid") from exc
    result: dict[int, tuple[str, int, float]] = {}
    used_codes: set[str] = set()
    for row in table.to_pylist():
        service_id = row["service_movie_id"]
        tmdb_id = row["tmdb_id"]
        code = row["kobis_movie_code"]
        admissions = row["cumulative_admissions"]
        population_row = population.get(code)
        if (
            not isinstance(service_id, int)
            or catalog_pairs.get(service_id) != tmdb_id
            or row["verification_status"] != "VERIFIED"
            or population_row is None
            or population_row[0] != admissions
            or service_id in result
            or code in used_codes
        ):
            raise ArtifactError("KOBIS links must be verified and match the full population rollup")
        result[service_id] = (code, admissions, population_row[1])
        used_codes.add(code)
    if not result:
        raise ArtifactError("verified KOBIS links must not be empty")
    return result


def _ranking_table(
    catalog: list[dict[str, object]],
    kobis: dict[int, tuple[str, int, float]],
    popularity_version: str,
    mapping_revision: str,
) -> pa.Table:
    vote_counts = np.asarray([row["raw_vote_count_number"] for row in catalog], dtype=np.float64)
    tmdb_scores = _tie_aware_percentiles(vote_counts)
    rows: list[dict[str, object]] = []
    for catalog_row, tmdb_score in zip(catalog, tmdb_scores, strict=True):
        service_id = int(catalog_row["service_movie_id"])
        kobis_row = kobis.get(service_id)
        kobis_score = kobis_row[2] if kobis_row else None
        rows.append(
            {
                "service_movie_id": service_id,
                "tmdb_id": int(catalog_row["tmdb_id"]),
                "popularity_score": max(float(tmdb_score), kobis_score if kobis_score is not None else -1.0),
                "tmdb_vote_percentile": float(tmdb_score),
                "tmdb_vote_count": int(catalog_row["raw_vote_count_number"]),
                "kobis_admissions_percentile": kobis_score,
                "kobis_cumulative_admissions": kobis_row[1] if kobis_row else None,
                "kobis_movie_code": kobis_row[0] if kobis_row else None,
                "popularity_version": popularity_version,
                "mapping_revision": mapping_revision,
            }
        )
    rows.sort(key=lambda row: (-float(row["popularity_score"]), int(row["service_movie_id"])))
    for rank, row in enumerate(rows, 1):
        row["global_rank"] = rank
    return pa.Table.from_pylist(rows, schema=RANKING_SCHEMA)


@contextlib.contextmanager
def _publication_lock(parent: Path):
    lock_path = parent / ".discovery-popularity-publish.lock"
    with lock_path.open("a+b") as lock:
        lock.seek(0, os.SEEK_END)
        if lock.tell() == 0:
            lock.write(b"\0")
            lock.flush()
        lock.seek(0)
        if os.name == "nt":
            import msvcrt

            msvcrt.locking(lock.fileno(), msvcrt.LK_LOCK, 1)
        else:
            import fcntl

            fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        try:
            yield
        finally:
            lock.seek(0)
            if os.name == "nt":
                msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


def export_discovery_popularity(
    *,
    catalog_path: Path,
    kobis_population_path: Path,
    verified_kobis_path: Path,
    output_root: Path,
    popularity_version: str,
    mapping_revision: str,
) -> Path:
    output_root = output_root.resolve()
    if VERSION_PATTERN.fullmatch(popularity_version) is None or REVISION_PATTERN.fullmatch(mapping_revision) is None:
        raise ArtifactError("invalid Discovery popularity version or mapping revision")
    catalog, pairs = _catalog(catalog_path)
    population, population_count = _kobis_population(kobis_population_path)
    kobis = _verified_kobis(verified_kobis_path, pairs, population)
    ranking = _ranking_table(catalog, kobis, popularity_version, mapping_revision)

    parent = output_root / "discovery-popularity"
    target = parent / popularity_version
    staging = parent / f".{popularity_version}.tmp-{uuid.uuid4().hex}"
    parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        raise ArtifactError("Discovery popularity version already exists")
    staging.mkdir()
    try:
        pq.write_table(ranking, staging / "ranking.parquet", compression="zstd", use_dictionary=True)
        metadata = {
            "contract_version": CONTRACT_VERSION,
            "popularity_version": popularity_version,
            "mapping_revision": mapping_revision,
            "built_at_utc": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "row_count": ranking.num_rows,
            "kobis_population_count": population_count,
            "verified_kobis_attached_count": len(kobis),
            "policy": {
                "tmdb": "tie-aware percentile of raw vote_count over the full service catalog",
                "kobis": "tie-aware percentile of cumulative admissions over the full annual KOBIS rollup",
                "merge": "max(tmdb_vote_percentile,kobis_admissions_percentile); missing KOBIS does not become zero",
                "tie_break": "service_movie_id ascending",
            },
            "sources": {
                "catalog": {"bytes": catalog_path.stat().st_size, "sha256": _sha256(catalog_path)},
                "kobis_population": {
                    "bytes": kobis_population_path.stat().st_size,
                    "sha256": _sha256(kobis_population_path),
                },
                "verified_kobis": {
                    "bytes": verified_kobis_path.stat().st_size,
                    "sha256": _sha256(verified_kobis_path),
                },
            },
        }
        _write_json(staging / "metadata.json", metadata)
        (staging / "_SUCCESS").write_bytes(b"")
        _write_json(
            staging / "manifest.json",
            {
                "contract_version": CONTRACT_VERSION,
                "files": [_manifest_entry(staging / name) for name in sorted(EXPECTED_FILES)],
            },
        )
        load_discovery_popularity(staging.as_uri(), expected_mapping_revision=mapping_revision)
        with _publication_lock(parent):
            if target.exists():
                raise ArtifactError("Discovery popularity version already exists")
            os.rename(staging, target)
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    return target


def _read_bytes(uri: str, child: str, maximum_bytes: int) -> bytes:
    try:
        filesystem, root = pafs.FileSystem.from_uri(uri)
        path = posixpath.join(root, child)
        info = filesystem.get_file_info(path)
        if info.type != pafs.FileType.File or info.size < 0 or info.size > maximum_bytes:
            raise ArtifactError(f"invalid {child}")
        with filesystem.open_input_file(path) as stream:
            return stream.read()
    except ArtifactError:
        raise
    except (pa.ArrowException, TypeError, ValueError, OSError, FileNotFoundError) as exc:
        raise ArtifactError(f"{child} is unavailable") from exc


def _checked_files(uri: str) -> dict[str, bytes]:
    try:
        manifest = json.loads(_read_bytes(uri, "manifest.json", MAX_CONTROL_FILE_BYTES))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ArtifactError("invalid Discovery popularity manifest") from exc
    if not isinstance(manifest, dict) or manifest.get("contract_version") != CONTRACT_VERSION:
        raise ArtifactError("unsupported Discovery popularity manifest")
    entries = manifest.get("files")
    if not isinstance(entries, list):
        raise ArtifactError("invalid Discovery popularity manifest")
    results: dict[str, bytes] = {}
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != {"path", "bytes", "sha256"}:
            raise ArtifactError("invalid Discovery popularity manifest")
        path, size, digest = entry["path"], entry["bytes"], entry["sha256"]
        if path not in EXPECTED_FILES or path in results or not isinstance(size, int) or size < 0:
            raise ArtifactError("invalid Discovery popularity manifest")
        maximum = MAX_RANKING_FILE_BYTES if path == "ranking.parquet" else MAX_CONTROL_FILE_BYTES
        data = _read_bytes(uri, path, maximum)
        if (
            not isinstance(digest, str)
            or re.fullmatch(r"[0-9a-f]{64}", digest) is None
            or len(data) != size
            or hashlib.sha256(data).hexdigest() != digest
        ):
            raise ArtifactError(f"{path} does not match manifest")
        results[path] = data
    if set(results) != EXPECTED_FILES or results["_SUCCESS"] != b"":
        raise ArtifactError("incomplete Discovery popularity artifact")
    return results


def load_discovery_popularity(
    uri: str, *, expected_mapping_revision: str | None = None
) -> DiscoveryPopularityBundle:
    files = _checked_files(uri)
    try:
        metadata = json.loads(files["metadata.json"])
        table = pq.read_table(io.BytesIO(files["ranking.parquet"]))
    except (UnicodeDecodeError, json.JSONDecodeError, pa.ArrowException) as exc:
        raise ArtifactError("Discovery popularity files are invalid") from exc
    if not isinstance(metadata, dict) or metadata.get("contract_version") != CONTRACT_VERSION:
        raise ArtifactError("invalid Discovery popularity metadata")
    version = metadata.get("popularity_version")
    mapping_revision = metadata.get("mapping_revision")
    if (
        VERSION_PATTERN.fullmatch(version or "") is None
        or REVISION_PATTERN.fullmatch(mapping_revision or "") is None
        or table.schema != RANKING_SCHEMA
        or table.num_rows != metadata.get("row_count")
        or (expected_mapping_revision is not None and mapping_revision != expected_mapping_revision)
    ):
        raise ArtifactError("Discovery popularity metadata or schema mismatch")
    rows = table.to_pylist()
    if [row["global_rank"] for row in rows] != list(range(1, len(rows) + 1)):
        raise ArtifactError("Discovery popularity ranks must be contiguous")
    if len({row["service_movie_id"] for row in rows}) != len(rows):
        raise ArtifactError("Discovery popularity service IDs must be unique")
    previous: tuple[float, int] | None = None
    candidates: list[RankedMovie] = []
    service_movie_ids = np.empty(len(rows), dtype=np.int64)
    tmdb_ids = np.empty(len(rows), dtype=np.int64)
    for index, row in enumerate(rows):
        score = row["popularity_score"]
        expected = max(
            row["tmdb_vote_percentile"],
            row["kobis_admissions_percentile"]
            if row["kobis_admissions_percentile"] is not None
            else -1.0,
        )
        key = (-score, row["service_movie_id"])
        if (
            not isinstance(score, float)
            or not 0 <= score <= 1
            or not 0 <= row["tmdb_vote_percentile"] <= 1
            or row["tmdb_vote_count"] < 0
            or (
                row["kobis_admissions_percentile"] is not None
                and not 0 <= row["kobis_admissions_percentile"] <= 1
            )
            or ((row["kobis_admissions_percentile"] is None) != (row["kobis_movie_code"] is None))
            or ((row["kobis_admissions_percentile"] is None) != (row["kobis_cumulative_admissions"] is None))
            or score != expected
            or row["popularity_version"] != version
            or row["mapping_revision"] != mapping_revision
            or (previous is not None and key < previous)
        ):
            raise ArtifactError("invalid Discovery popularity row")
        previous = key
        candidates.append(RankedMovie(row["service_movie_id"], score))
        service_movie_ids[index] = row["service_movie_id"]
        tmdb_ids[index] = row["tmdb_id"]
    service_movie_ids.setflags(write=False)
    tmdb_ids.setflags(write=False)
    return DiscoveryPopularityBundle(
        version,
        mapping_revision,
        tuple(candidates),
        service_movie_ids,
        tmdb_ids,
    )
