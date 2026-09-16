from __future__ import annotations

import hashlib
import io
import json
import math
import posixpath
import re
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from types import MappingProxyType

import numpy as np
import pyarrow as pa
import pyarrow.fs as pafs
import pyarrow.parquet as pq

from feelm_consumer.artifacts import REVISION_PATTERN, ArtifactError

CONTRACT_VERSION = "group-serving-artifacts-v1"
GEOMETRY_VERSION = "g913-gkt128-mean-v1"
PUBLIC_TASTE_SEMANTICS = frozenset(
    {
        # Existing S15P21E106-439 bundles keep their immutable metadata hash.
        "RECOMMENDATION_INTERNAL_NOT_PUBLIC_POPCORN",
        # New exports describe the service binding introduced by S15P21E106-466.
        "PUBLIC_POPCORN_FLAVOR_V1",
    }
)
EXPECTED_FILES = frozenset({"_SUCCESS", "arrays.npz", "assignments.parquet", "metadata.json"})
MAX_CONTROL_FILE_BYTES = 1024 * 1024
MAX_ARRAY_FILE_BYTES = 64 * 1024 * 1024
TOKEN_PATTERN = re.compile(r"(?u)\b\w\w+\b")


@dataclass(frozen=True)
class GroupAssignment:
    taste_id: int
    child_id: int
    group_id: int
    top_supported: bool
    sub_supported: bool
    group_mapped: bool


@dataclass(frozen=True)
class LiveMappingCompatibility:
    current_movie_count: int
    unchanged_count: int
    added_service_movie_ids: tuple[int, ...]
    removed_service_movie_ids: tuple[int, ...]
    rebound_service_movie_ids: tuple[int, ...]
    activation_allowed: bool


@dataclass(frozen=True)
class GroupBundle:
    group_version: str
    catalog_version: str
    mapping_revision: str
    group_recipe_revision: str
    public_taste_semantics: str
    genre_ids: np.ndarray
    keyword_ids: np.ndarray
    keyword_idf: np.ndarray
    keyword_components: np.ndarray
    text_tokens: np.ndarray
    text_idf: np.ndarray
    text_components: np.ndarray
    top_centers: np.ndarray
    child_centers: np.ndarray
    group_means: np.ndarray
    top_weights: np.ndarray
    sub_weights: np.ndarray
    offsets: np.ndarray
    service_movie_ids: np.ndarray
    tmdb_ids: np.ndarray
    taste_ids: np.ndarray
    child_ids: np.ndarray
    group_ids: np.ndarray
    top_supported: np.ndarray
    sub_supported: np.ndarray
    group_mapped: np.ndarray
    genre_lookup: Mapping[int, int]
    keyword_lookup: Mapping[int, int]
    text_lookup: Mapping[str, int]

    def assignment_for(self, service_movie_id: int) -> GroupAssignment | None:
        if (
            isinstance(service_movie_id, bool)
            or not isinstance(service_movie_id, int | np.integer)
            or service_movie_id <= 0
        ):
            return None
        index = int(np.searchsorted(self.service_movie_ids, service_movie_id))
        if index == len(self.service_movie_ids) or int(self.service_movie_ids[index]) != service_movie_id:
            return None
        return GroupAssignment(
            taste_id=int(self.taste_ids[index]),
            child_id=int(self.child_ids[index]),
            group_id=int(self.group_ids[index]),
            top_supported=bool(self.top_supported[index]),
            sub_supported=bool(self.sub_supported[index]),
            group_mapped=bool(self.group_mapped[index]),
        )


def compare_live_mapping(
    bundle: GroupBundle, current_pairs: Iterable[tuple[int, int]]
) -> LiveMappingCompatibility:
    """Compare a live service mapping without requiring an equal snapshot revision.

    Additions and removals are safe because the immutable bundle remains authoritative
    for its own catalog. Reusing an existing service ID for another TMDb movie is not.
    """
    current: dict[int, int] = {}
    current_tmdb_ids: set[int] = set()
    for pair in current_pairs:
        try:
            service_movie_id, tmdb_id = pair
        except (TypeError, ValueError) as exc:
            raise ArtifactError("invalid live service mapping pair") from exc
        if (
            isinstance(service_movie_id, bool)
            or isinstance(tmdb_id, bool)
            or not isinstance(service_movie_id, int | np.integer)
            or not isinstance(tmdb_id, int | np.integer)
            or int(service_movie_id) <= 0
            or int(tmdb_id) <= 0
        ):
            raise ArtifactError("invalid live service mapping pair")
        service_id = int(service_movie_id)
        external_id = int(tmdb_id)
        if service_id in current or external_id in current_tmdb_ids:
            raise ArtifactError("duplicate live service mapping")
        current[service_id] = external_id
        current_tmdb_ids.add(external_id)

    baseline = {
        int(service_movie_id): int(tmdb_id)
        for service_movie_id, tmdb_id in zip(bundle.service_movie_ids, bundle.tmdb_ids)
    }
    shared_ids = baseline.keys() & current.keys()
    rebound = tuple(sorted(service_id for service_id in shared_ids if baseline[service_id] != current[service_id]))
    unchanged_count = sum(baseline[service_id] == current[service_id] for service_id in shared_ids)
    added = tuple(sorted(current.keys() - baseline.keys()))
    removed = tuple(sorted(baseline.keys() - current.keys()))
    return LiveMappingCompatibility(
        current_movie_count=len(current),
        unchanged_count=unchanged_count,
        added_service_movie_ids=added,
        removed_service_movie_ids=removed,
        rebound_service_movie_ids=rebound,
        activation_allowed=not rebound,
    )


@dataclass(frozen=True)
class AssignedGroup:
    taste_id: int
    child_id: int
    group_id: int
    top_supported: bool
    sub_supported: bool
    group_mapped: bool


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


def _json_object(data: bytes, label: str) -> dict[str, object]:
    try:
        value = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ArtifactError(f"invalid {label}") from exc
    if not isinstance(value, dict):
        raise ArtifactError(f"invalid {label}")
    return value


def _validated_manifest(data: bytes) -> dict[str, str]:
    value = _json_object(data, "manifest")
    if value.get("contract_version") != CONTRACT_VERSION:
        raise ArtifactError("unsupported group artifact manifest")
    raw_files = value.get("files")
    if not isinstance(raw_files, list):
        raise ArtifactError("invalid group artifact manifest")
    files: dict[str, str] = {}
    for item in raw_files:
        if not isinstance(item, dict) or set(item) != {"path", "bytes", "sha256"}:
            raise ArtifactError("invalid group artifact manifest")
        path, size, digest = item["path"], item["bytes"], item["sha256"]
        if (
            not isinstance(path, str)
            or path not in EXPECTED_FILES
            or path in files
            or not isinstance(size, int)
            or size < 0
            or not isinstance(digest, str)
            or not re.fullmatch(r"[0-9a-f]{64}", digest)
        ):
            raise ArtifactError("invalid group artifact manifest")
        files[path] = f"{size}:{digest}"
    if set(files) != EXPECTED_FILES:
        raise ArtifactError("incomplete group artifact manifest")
    return files


def _checked_file(uri: str, path: str, encoded: str, maximum_bytes: int) -> bytes:
    expected_size, expected_hash = encoded.split(":", maxsplit=1)
    data = _read_bytes(uri, path, maximum_bytes)
    if len(data) != int(expected_size) or hashlib.sha256(data).hexdigest() != expected_hash:
        raise ArtifactError(f"{path} does not match manifest")
    return data


def _finite_array(arrays: object, name: str, shape: tuple[int, ...], dtype: np.dtype) -> np.ndarray:
    try:
        value = np.asarray(arrays[name])
    except (KeyError, TypeError, ValueError) as exc:
        raise ArtifactError(f"invalid group array {name}") from exc
    if value.shape != shape or value.dtype != dtype or not np.isfinite(value).all():
        raise ArtifactError(f"invalid group array {name}")
    value.setflags(write=False)
    return value


def _integer_array(arrays: object, name: str, shape: tuple[int, ...], dtype: np.dtype) -> np.ndarray:
    try:
        value = np.asarray(arrays[name])
    except (KeyError, TypeError, ValueError) as exc:
        raise ArtifactError(f"invalid group array {name}") from exc
    if value.shape != shape or value.dtype != dtype:
        raise ArtifactError(f"invalid group array {name}")
    value.setflags(write=False)
    return value


def _assignments(data: bytes, expected_rows: int, metadata: dict[str, object]) -> dict[str, np.ndarray]:
    try:
        table = pq.read_table(io.BytesIO(data))
    except (pa.ArrowException, OSError, ValueError) as exc:
        raise ArtifactError("invalid group assignments") from exc
    expected_types = {
        "service_movie_id": pa.int64(),
        "tmdb_id": pa.int64(),
        "taste_id": pa.int32(),
        "child_id": pa.int32(),
        "group_id": pa.int32(),
        "top_supported": pa.bool_(),
        "sub_supported": pa.bool_(),
        "group_mapped": pa.bool_(),
        "catalog_version": pa.string(),
        "geometry_version": pa.string(),
        "group_recipe_revision": pa.string(),
        "mapping_revision": pa.string(),
        "vector_hash": pa.string(),
        "source_hash": pa.string(),
    }
    if table.num_rows != expected_rows or set(table.column_names) != set(expected_types):
        raise ArtifactError("invalid group assignments schema")
    for name, expected_type in expected_types.items():
        if table.schema.field(name).type != expected_type or table.column(name).null_count:
            raise ArtifactError("invalid group assignments schema")
    rows = table.sort_by("service_movie_id")
    result = {name: rows.column(name).combine_chunks().to_numpy(zero_copy_only=False) for name in expected_types}
    service_ids = result["service_movie_id"]
    tmdb_ids = result["tmdb_id"]
    tastes, children, groups = result["taste_id"], result["child_id"], result["group_id"]
    if (
        len(np.unique(service_ids)) != expected_rows
        or len(np.unique(tmdb_ids)) != expected_rows
        or np.any(service_ids <= 0)
        or np.any(tmdb_ids <= 0)
        or np.any(tastes < 0)
        or np.any(tastes >= 8)
        or np.any(children < 0)
        or np.any(children >= 128)
        or not np.array_equal(groups, tastes * 128 + children)
        or np.any(result["group_mapped"] & ~(result["top_supported"] & result["sub_supported"]))
    ):
        raise ArtifactError("invalid group assignments")
    if (
        int(np.sum(result["group_mapped"])) != metadata["mapped_movie_count"]
        or int(np.sum(~result["top_supported"])) != metadata["genre_missing_count"]
    ):
        raise ArtifactError("group assignment counts do not match metadata")
    repeated = {
        "catalog_version": metadata["catalog_version"],
        "geometry_version": GEOMETRY_VERSION,
        "group_recipe_revision": metadata["group_recipe_revision"],
        "mapping_revision": metadata["mapping_revision"],
    }
    for name, expected in repeated.items():
        if set(result[name].tolist()) != {expected}:
            raise ArtifactError("mixed group assignment lineage")
    for name in ("vector_hash", "source_hash"):
        if any(not isinstance(value, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", value) for value in result[name]):
            raise ArtifactError("invalid group assignment hash")
    return result


def load_group_bundle(uri: str, expected_mapping_revision: str | None = None) -> GroupBundle:
    manifest = _validated_manifest(_read_bytes(uri, "manifest.json", MAX_CONTROL_FILE_BYTES))
    if _checked_file(uri, "_SUCCESS", manifest["_SUCCESS"], MAX_CONTROL_FILE_BYTES) != b"":
        raise ArtifactError("invalid group artifact success marker")
    metadata = _json_object(
        _checked_file(uri, "metadata.json", manifest["metadata.json"], MAX_CONTROL_FILE_BYTES),
        "group metadata",
    )
    required_metadata = {
        "contract_version",
        "group_version",
        "catalog_version",
        "geometry_version",
        "mapping_revision",
        "group_recipe_revision",
        "movie_count",
        "mapped_movie_count",
        "genre_missing_count",
        "genre_missing_policy",
        "public_taste_semantics",
    }
    if not required_metadata.issubset(metadata) or metadata.get("contract_version") != CONTRACT_VERSION:
        raise ArtifactError("unsupported group artifact metadata")
    group_version = metadata["group_version"]
    catalog_version = metadata["catalog_version"]
    mapping_revision = metadata["mapping_revision"]
    recipe_revision = metadata["group_recipe_revision"]
    movie_count = metadata["movie_count"]
    if (
        not isinstance(group_version, str)
        or not group_version
        or not isinstance(catalog_version, str)
        or not catalog_version
        or metadata["geometry_version"] != GEOMETRY_VERSION
        or not isinstance(mapping_revision, str)
        or not REVISION_PATTERN.fullmatch(mapping_revision)
        or not isinstance(recipe_revision, str)
        or not REVISION_PATTERN.fullmatch(recipe_revision)
        or not isinstance(movie_count, int)
        or movie_count <= 0
        or not isinstance(metadata["mapped_movie_count"], int)
        or not 0 <= metadata["mapped_movie_count"] <= movie_count
        or not isinstance(metadata["genre_missing_count"], int)
        or not 0 <= metadata["genre_missing_count"] <= movie_count
        or metadata["genre_missing_policy"] != "EXCLUDE_FROM_DISCOVERY"
        or metadata["public_taste_semantics"] not in PUBLIC_TASTE_SEMANTICS
    ):
        raise ArtifactError("invalid group artifact metadata")
    if expected_mapping_revision is not None and mapping_revision != expected_mapping_revision:
        raise ArtifactError("group mapping revision mismatch")

    raw_arrays = _checked_file(uri, "arrays.npz", manifest["arrays.npz"], MAX_ARRAY_FILE_BYTES)
    try:
        with np.load(io.BytesIO(raw_arrays), allow_pickle=False) as arrays:
            if set(arrays.files) != {
                "genre_ids",
                "keyword_ids",
                "keyword_idf",
                "keyword_components",
                "text_tokens",
                "text_idf",
                "text_components",
                "top_centers",
                "child_centers",
                "group_means",
                "top_weights",
                "sub_weights",
                "offsets",
            }:
                raise ArtifactError("invalid group array inventory")
            genre_ids = _integer_array(arrays, "genre_ids", (19,), np.dtype("int64"))
            keyword_ids = _integer_array(arrays, "keyword_ids", (6543,), np.dtype("int64"))
            keyword_idf = _finite_array(arrays, "keyword_idf", (6543,), np.dtype("float64"))
            keyword_components = _finite_array(arrays, "keyword_components", (64, 6543), np.dtype("float64"))
            text_tokens = np.asarray(arrays["text_tokens"])
            if (
                text_tokens.shape != (16000,)
                or text_tokens.dtype.kind != "U"
                or len(set(text_tokens.tolist())) != 16000
            ):
                raise ArtifactError("invalid group array text_tokens")
            text_tokens.setflags(write=False)
            text_idf = _finite_array(arrays, "text_idf", (16000,), np.dtype("float64"))
            text_components = _finite_array(arrays, "text_components", (48, 16000), np.dtype("float64"))
            top_centers = _finite_array(arrays, "top_centers", (8, 131), np.dtype("float64"))
            child_centers = _finite_array(arrays, "child_centers", (8, 128, 131), np.dtype("float64"))
            group_means = _finite_array(arrays, "group_means", (1024, 131), np.dtype("float64"))
            top_weights = _finite_array(arrays, "top_weights", (3,), np.dtype("float64"))
            sub_weights = _finite_array(arrays, "sub_weights", (3,), np.dtype("float64"))
            offsets = _integer_array(arrays, "offsets", (9,), np.dtype("int64"))
    except ArtifactError:
        raise
    except (OSError, ValueError, TypeError) as exc:
        raise ArtifactError("invalid group arrays") from exc
    if (
        len(np.unique(genre_ids)) != len(genre_ids)
        or len(np.unique(keyword_ids)) != len(keyword_ids)
        or np.any(genre_ids <= 0)
        or np.any(keyword_ids <= 0)
        or not np.array_equal(top_weights, np.asarray([1.0, 0.0, 0.0]))
        or not np.array_equal(sub_weights, np.asarray([0.5, 0.35, 0.15]))
        or not np.array_equal(offsets, np.arange(9, dtype=np.int64) * 128)
    ):
        raise ArtifactError("invalid frozen group geometry")

    assignment_bytes = _checked_file(uri, "assignments.parquet", manifest["assignments.parquet"], MAX_ARRAY_FILE_BYTES)
    assignments = _assignments(assignment_bytes, movie_count, metadata)
    return GroupBundle(
        group_version=group_version,
        catalog_version=catalog_version,
        mapping_revision=mapping_revision,
        group_recipe_revision=recipe_revision,
        public_taste_semantics=str(metadata["public_taste_semantics"]),
        genre_ids=genre_ids,
        keyword_ids=keyword_ids,
        keyword_idf=keyword_idf,
        keyword_components=keyword_components,
        text_tokens=text_tokens,
        text_idf=text_idf,
        text_components=text_components,
        top_centers=top_centers,
        child_centers=child_centers,
        group_means=group_means,
        top_weights=top_weights,
        sub_weights=sub_weights,
        offsets=offsets,
        service_movie_ids=assignments["service_movie_id"],
        tmdb_ids=assignments["tmdb_id"],
        taste_ids=assignments["taste_id"],
        child_ids=assignments["child_id"],
        group_ids=assignments["group_id"],
        top_supported=assignments["top_supported"],
        sub_supported=assignments["sub_supported"],
        group_mapped=assignments["group_mapped"],
        genre_lookup=MappingProxyType({int(value): index for index, value in enumerate(genre_ids)}),
        keyword_lookup=MappingProxyType({int(value): index for index, value in enumerate(keyword_ids)}),
        text_lookup=MappingProxyType({str(value): index for index, value in enumerate(text_tokens)}),
    )


def _normalized(vector: np.ndarray) -> np.ndarray:
    value = np.asarray(vector, dtype=np.float64)
    length = math.sqrt(float(np.sum(value * value)))
    return value / length if length > 1e-12 else np.zeros_like(value)


def _ids(value: Iterable[int] | None) -> set[int]:
    if value is None:
        return set()
    try:
        iterator = iter(value)
    except TypeError:
        return set()
    result: set[int] = set()
    for item in iterator:
        if isinstance(item, bool) or not isinstance(item, int | np.integer) or int(item) <= 0:
            continue
        result.add(int(item))
    return result


def _reduced_id_block(
    values: Iterable[int] | None,
    lookup: Mapping[int, int],
    components: np.ndarray,
    idf: np.ndarray | None,
) -> np.ndarray:
    indices = sorted(lookup[value] for value in _ids(values) if value in lookup)
    if not indices:
        return np.zeros(components.shape[0], dtype=np.float64)
    weights = np.ones(len(indices), dtype=np.float64) if idf is None else np.asarray(idf[indices], dtype=np.float64)
    weights = _normalized(weights)
    return _normalized(np.sum(components[:, indices] * weights, axis=1))


def _text_block(overview: object, bundle: GroupBundle) -> np.ndarray:
    text = overview if isinstance(overview, str) else ""
    counts: dict[int, int] = {}
    for token in TOKEN_PATTERN.findall(text.lower()):
        index = bundle.text_lookup.get(token)
        if index is not None:
            counts[index] = counts.get(index, 0) + 1
    if not counts:
        return np.zeros(48, dtype=np.float64)
    indices = sorted(counts)
    weights = np.asarray([counts[index] * bundle.text_idf[index] for index in indices], dtype=np.float64)
    weights = _normalized(weights)
    return _normalized(np.sum(bundle.text_components[:, indices] * weights, axis=1))


def _combined(blocks: tuple[np.ndarray, np.ndarray, np.ndarray], weights: np.ndarray) -> np.ndarray:
    return _normalized(np.concatenate([block * math.sqrt(float(weight)) for block, weight in zip(blocks, weights)]))


def assign_group(
    bundle: GroupBundle,
    genre_ids: Iterable[int] | None,
    keyword_ids: Iterable[int] | None,
    overview: object,
    *,
    id_mapping_verified: bool,
) -> AssignedGroup:
    genre_block = np.zeros(19, dtype=np.float64)
    for value in _ids(genre_ids):
        index = bundle.genre_lookup.get(value)
        if index is not None:
            genre_block[index] = 1.0
    genre_block = _normalized(genre_block)
    keyword_block = _reduced_id_block(keyword_ids, bundle.keyword_lookup, bundle.keyword_components, bundle.keyword_idf)
    text_block = _text_block(overview, bundle)
    blocks = (genre_block, keyword_block, text_block)
    top_vector = _combined(blocks, bundle.top_weights)
    sub_vector = _combined(blocks, bundle.sub_weights)
    top_distances = np.stack([np.sum((top_vector - center) ** 2) for center in bundle.top_centers])
    taste_id = int(np.argmin(top_distances))
    child_distances = np.stack([np.sum((sub_vector - center) ** 2) for center in bundle.child_centers[taste_id]])
    child_id = int(np.argmin(child_distances))
    top_supported = bool(np.sum(top_vector * top_vector) > 1e-12)
    sub_supported = bool(np.sum(sub_vector * sub_vector) > 1e-12)
    return AssignedGroup(
        taste_id=taste_id,
        child_id=child_id,
        group_id=taste_id * 128 + child_id,
        top_supported=top_supported,
        sub_supported=sub_supported,
        group_mapped=bool(id_mapping_verified is True and top_supported and sub_supported),
    )
