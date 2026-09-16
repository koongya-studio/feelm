"""Offline-only conversion of pinned, trusted research pickles into a safe serving bundle.

This module is not part of the Consumer startup path. Pickle loading is deliberately
limited to files whose size and SHA-256 are pinned in the reviewed recipe.
"""

from __future__ import annotations

import argparse
import contextlib
import hashlib
import importlib.metadata
import json
import os
import pickle
import re
import shutil
import sys
import uuid
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import numpy as np
import pyarrow as pa
import pyarrow.fs as pafs
import pyarrow.parquet as pq

from feelm_consumer.artifacts import REVISION_PATTERN, ArtifactError
from feelm_consumer.group_artifacts import CONTRACT_VERSION, GEOMETRY_VERSION, load_group_bundle

VERSION_PATTERN = re.compile(r"[a-z0-9][a-z0-9._-]{0,127}\Z")
ASSIGNMENT_SCHEMA = pa.schema(
    [
        pa.field("service_movie_id", pa.int64(), nullable=False),
        pa.field("tmdb_id", pa.int64(), nullable=False),
        pa.field("taste_id", pa.int32(), nullable=False),
        pa.field("child_id", pa.int32(), nullable=False),
        pa.field("group_id", pa.int32(), nullable=False),
        pa.field("top_supported", pa.bool_(), nullable=False),
        pa.field("sub_supported", pa.bool_(), nullable=False),
        pa.field("group_mapped", pa.bool_(), nullable=False),
        pa.field("catalog_version", pa.string(), nullable=False),
        pa.field("geometry_version", pa.string(), nullable=False),
        pa.field("group_recipe_revision", pa.string(), nullable=False),
        pa.field("mapping_revision", pa.string(), nullable=False),
        pa.field("vector_hash", pa.string(), nullable=False),
        pa.field("source_hash", pa.string(), nullable=False),
    ]
)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _canonical_json(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def _revision(data: bytes) -> str:
    return f"sha256:{hashlib.sha256(data).hexdigest()}"


def _array_hash(value: object) -> str:
    array = np.ascontiguousarray(value)
    return hashlib.sha256(str(array.dtype).encode() + str(array.shape).encode() + array.tobytes()).hexdigest()


def _verified_source(root: Path, spec: object, label: str) -> Path:
    if not isinstance(spec, dict):
        raise ArtifactError(f"invalid {label} source specification")
    raw_path, expected_size, expected_hash = spec.get("path"), spec.get("bytes"), spec.get("sha256")
    if (
        not isinstance(raw_path, str)
        or Path(raw_path).is_absolute()
        or ".." in Path(raw_path).parts
        or not isinstance(expected_size, int)
        or expected_size < 0
        or not isinstance(expected_hash, str)
        or not re.fullmatch(r"[0-9a-f]{64}", expected_hash)
    ):
        raise ArtifactError(f"invalid {label} source specification")
    path = root.joinpath(*Path(raw_path).parts).resolve()
    try:
        path.relative_to(root.resolve())
    except ValueError as exc:
        raise ArtifactError(f"invalid {label} source path") from exc
    if not path.is_file() or path.stat().st_size != expected_size or _sha256(path) != expected_hash:
        raise ArtifactError(f"{label} source does not match reviewed recipe")
    return path


def _trusted_pickle(path: Path) -> dict[str, Any]:
    # The caller must verify the file against the reviewed recipe before reaching this point.
    with path.open("rb") as stream:
        value = pickle.load(stream)  # noqa: S301
    if not isinstance(value, dict):
        raise ArtifactError("trusted source pickle must contain an object")
    return value


def _read_dataset(uri: str, columns: list[str]) -> pa.Table:
    try:
        filesystem, root = pafs.FileSystem.from_uri(uri)
        return pq.ParquetDataset(root, filesystem=filesystem).read(columns=columns)
    except (pa.ArrowException, TypeError, ValueError, OSError, FileNotFoundError) as exc:
        raise ArtifactError("mapping dataset is unavailable") from exc


def _validated_mapping(uri: str) -> tuple[pa.Table, str]:
    columns = ["service_movie_id", "tmdb_id", "mapping_revision"]
    table = _read_dataset(uri, columns)
    if table.num_rows <= 0:
        raise ArtifactError("empty service mapping")
    expected = {"service_movie_id": pa.int64(), "tmdb_id": pa.int32(), "mapping_revision": pa.string()}
    for name, physical_type in expected.items():
        if table.schema.field(name).type != physical_type or table.column(name).null_count:
            raise ArtifactError("invalid service mapping schema")
    service_ids = table.column("service_movie_id").combine_chunks().to_numpy()
    tmdb_ids = table.column("tmdb_id").combine_chunks().to_numpy()
    revisions = set(table.column("mapping_revision").combine_chunks().to_pylist())
    if (
        len(np.unique(service_ids)) != len(service_ids)
        or len(np.unique(tmdb_ids)) != len(tmdb_ids)
        or np.any(service_ids <= 0)
        or np.any(tmdb_ids <= 0)
        or len(revisions) != 1
    ):
        raise ArtifactError("invalid service mapping")
    revision = revisions.pop()
    if not isinstance(revision, str) or not REVISION_PATTERN.fullmatch(revision):
        raise ArtifactError("invalid service mapping revision")
    return table.sort_by("service_movie_id"), revision


def _require_array(value: object, shape: tuple[int, ...], expected_hash: str, label: str) -> np.ndarray:
    array = np.asarray(value)
    if array.shape != shape or array.dtype != np.float64 or not np.isfinite(array).all():
        raise ArtifactError(f"invalid frozen {label}")
    if _array_hash(array) != expected_hash:
        raise ArtifactError(f"frozen {label} hash mismatch")
    return np.ascontiguousarray(array)


def _source_arrays(recipe: dict[str, Any], root: Path) -> dict[str, np.ndarray]:
    sources = recipe.get("sources")
    if not isinstance(sources, dict):
        raise ArtifactError("recipe sources are missing")
    top_path = _verified_source(root, sources.get("topAndPreprocessor"), "top/preprocessor")
    hierarchy_path = _verified_source(root, sources.get("childrenAndMeans"), "hierarchy")
    top = _trusted_pickle(top_path)
    hierarchy = _trusted_pickle(hierarchy_path)
    extraction = recipe.get("sourceExtraction", {})
    allowed = set(extraction.get("topAndPreprocessor", {}).get("allowKeys", []))
    if allowed != {"preprocessor", "top_centers", "top_weights", "top_names"} or not allowed.issubset(top):
        raise ArtifactError("reviewed top extraction allow-list changed")
    prep = top["preprocessor"]
    if not isinstance(prep, dict):
        raise ArtifactError("invalid frozen preprocessor")
    geometry = recipe.get("geometry", {})
    preprocessor = recipe.get("preprocessor", {})
    genre_ids = np.asarray(prep.get("genres"), dtype=np.int64)
    keyword_ids = np.asarray(prep.get("keywords"), dtype=np.int64)
    text_vectorizer = prep.get("text_vectorizer")
    vocabulary = getattr(text_vectorizer, "vocabulary_", None)
    text_idf = np.asarray(getattr(text_vectorizer, "idf_", None))
    if (
        genre_ids.shape != (19,)
        or keyword_ids.shape != (6543,)
        or not isinstance(vocabulary, dict)
        or len(vocabulary) != 16000
        or text_idf.shape != (16000,)
        or text_idf.dtype != np.float64
    ):
        raise ArtifactError("invalid frozen vocabulary")
    text_tokens = np.empty(16000, dtype=f"<U{max(map(len, vocabulary))}")
    seen_indices: set[int] = set()
    normalized_vocabulary: dict[str, int] = {}
    for token, raw_index in vocabulary.items():
        if not isinstance(token, str) or isinstance(raw_index, bool) or not isinstance(raw_index, int | np.integer):
            raise ArtifactError("invalid frozen text vocabulary")
        index = int(raw_index)
        if not 0 <= index < 16000 or index in seen_indices:
            raise ArtifactError("invalid frozen text vocabulary")
        seen_indices.add(index)
        normalized_vocabulary[token] = index
        text_tokens[index] = token
    text_vocabulary_hash = hashlib.sha256(
        json.dumps(sorted(normalized_vocabulary.items()), ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    keyword_vocabulary_hash = hashlib.sha256(
        json.dumps(keyword_ids.tolist(), separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    if text_vocabulary_hash != preprocessor.get("textVocabularySha256") or keyword_vocabulary_hash != preprocessor.get(
        "keywordVocabularySha256"
    ):
        raise ArtifactError("frozen vocabulary hash mismatch")
    child_centers = np.stack(hierarchy.get("centers", []))
    group_means = hierarchy.get("representatives", {}).get("mean")
    arrays = {
        "genre_ids": genre_ids,
        "keyword_ids": keyword_ids,
        "keyword_idf": _require_array(
            prep.get("keyword_idf"), (6543,), preprocessor["keywordIdf"]["sha256"], "keyword IDF"
        ),
        "keyword_components": _require_array(
            prep.get("keyword_components"),
            (64, 6543),
            preprocessor["keywordComponents"]["sha256"],
            "keyword components",
        ),
        "text_tokens": text_tokens,
        "text_idf": _require_array(text_idf, (16000,), preprocessor["textIdf"]["sha256"], "text IDF"),
        "text_components": _require_array(
            prep.get("text_components"),
            (48, 16000),
            preprocessor["textComponents"]["sha256"],
            "text components",
        ),
        "top_centers": _require_array(
            top.get("top_centers"), (8, 131), geometry["topCenters"]["sha256"], "top centers"
        ),
        "child_centers": np.ascontiguousarray(child_centers, dtype=np.float64),
        "group_means": _require_array(group_means, (1024, 131), geometry["groupMeans"]["sha256"], "group means"),
        "top_weights": np.asarray(top.get("top_weights"), dtype=np.float64),
        "sub_weights": np.asarray(hierarchy.get("sub_weights"), dtype=np.float64),
        "offsets": np.asarray(hierarchy.get("offsets"), dtype=np.int64),
    }
    expected_child_hashes = [entry["sha256"] for entry in geometry["childCenters"]]
    if (
        arrays["child_centers"].shape != (8, 128, 131)
        or [_array_hash(value) for value in arrays["child_centers"]] != expected_child_hashes
        or not np.array_equal(arrays["top_weights"], np.asarray([1.0, 0.0, 0.0]))
        or not np.array_equal(arrays["sub_weights"], np.asarray([0.5, 0.35, 0.15]))
        or not np.array_equal(arrays["offsets"], np.arange(9, dtype=np.int64) * 128)
        or hierarchy.get("geometry_version") != recipe.get("sourceGeometryVersion")
    ):
        raise ArtifactError("invalid frozen group geometry")
    return arrays


def _reference_rows(
    recipe: dict[str, Any], root: Path, mapping: pa.Table, catalog_version: str, recipe_revision: str
) -> tuple[pa.Table, dict[str, int]]:
    sources = recipe["sources"]
    catalog_path = _verified_source(root, sources.get("referenceCatalog"), "reference catalog")
    assignment_path = _verified_source(root, sources.get("referenceAssignments"), "reference assignments")
    vector_path = _verified_source(root, sources.get("referenceContentVectors"), "reference content vectors")
    catalog = pq.read_table(catalog_path, columns=["service_movie_id", "tmdb_id", "genre_present"]).sort_by(
        "service_movie_id"
    )
    with np.load(assignment_path, allow_pickle=False) as reference:
        expected_keys = {"service_movie_id", "taste_id", "child_id", "group_id"}
        if set(reference.files) != expected_keys:
            raise ArtifactError("invalid reference assignment inventory")
        source_service_ids = np.asarray(reference["service_movie_id"])
        tastes = np.asarray(reference["taste_id"])
        children = np.asarray(reference["child_id"])
        groups = np.asarray(reference["group_id"])
    vectors = np.load(vector_path, mmap_mode="r", allow_pickle=False)
    expected_rows = recipe["sourceExtraction"]["referenceAssignments"]["referenceRows"]
    catalog_service_ids = catalog.column("service_movie_id").combine_chunks().to_numpy()
    if (
        catalog.num_rows != expected_rows
        or source_service_ids.shape != (expected_rows,)
        or vectors.shape != (expected_rows, 131)
        or not np.array_equal(catalog_service_ids, source_service_ids)
        or not np.array_equal(groups, tastes * 128 + children)
    ):
        raise ArtifactError("reference catalog and assignments are not aligned")
    tmdb_ids = catalog.column("tmdb_id").combine_chunks().to_numpy()
    genre_present = catalog.column("genre_present").combine_chunks().to_numpy(zero_copy_only=False)
    source_by_tmdb = {int(tmdb_id): index for index, tmdb_id in enumerate(tmdb_ids)}
    current_service_ids = mapping.column("service_movie_id").combine_chunks().to_numpy()
    current_tmdb_ids = mapping.column("tmdb_id").combine_chunks().to_numpy()
    indices: list[int] = []
    for tmdb_id in current_tmdb_ids:
        index = source_by_tmdb.get(int(tmdb_id))
        if index is None:
            raise ArtifactError("current service mapping contains a movie outside the frozen source catalog")
        indices.append(index)
    selected = np.asarray(indices, dtype=np.int64)
    selected_vectors = np.asarray(vectors[selected], dtype=np.float64)
    top_supported = np.asarray(genre_present[selected], dtype=bool)
    sub_supported = np.sum(selected_vectors * selected_vectors, axis=1) > 1e-12
    group_mapped = top_supported & sub_supported
    vector_hashes = [
        f"sha256:{hashlib.sha256(np.ascontiguousarray(vector).tobytes()).hexdigest()}" for vector in selected_vectors
    ]
    source_hashes = [
        _revision(_canonical_json({"source_service_movie_id": int(source_service_ids[index]), "tmdb_id": int(tmdb_id)}))
        for index, tmdb_id in zip(selected, current_tmdb_ids)
    ]
    mapping_revision = mapping.column("mapping_revision")[0].as_py()
    rows = pa.Table.from_arrays(
        [
            pa.array(current_service_ids, type=pa.int64()),
            pa.array(current_tmdb_ids, type=pa.int64()),
            pa.array(tastes[selected], type=pa.int32()),
            pa.array(children[selected], type=pa.int32()),
            pa.array(groups[selected], type=pa.int32()),
            pa.array(top_supported, type=pa.bool_()),
            pa.array(sub_supported, type=pa.bool_()),
            pa.array(group_mapped, type=pa.bool_()),
            pa.array([catalog_version] * len(selected), type=pa.string()),
            pa.array([GEOMETRY_VERSION] * len(selected), type=pa.string()),
            pa.array([recipe_revision] * len(selected), type=pa.string()),
            pa.array([mapping_revision] * len(selected), type=pa.string()),
            pa.array(vector_hashes, type=pa.string()),
            pa.array(source_hashes, type=pa.string()),
        ],
        schema=ASSIGNMENT_SCHEMA,
    )
    counts = {
        "movie_count": len(selected),
        "mapped_movie_count": int(group_mapped.sum()),
        "genre_missing_count": int((~top_supported).sum()),
        "sub_unsupported_count": int((~sub_supported).sum()),
    }
    return rows, counts


def _write_json(path: Path, value: object) -> None:
    path.write_bytes(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True).encode("utf-8") + b"\n")


def _manifest_entry(path: Path) -> dict[str, object]:
    return {"path": path.name, "bytes": path.stat().st_size, "sha256": _sha256(path)}


@contextlib.contextmanager
def _publication_lock(parent: Path):
    lock_path = parent / ".group-serving-publish.lock"
    with lock_path.open("a+b") as lock:
        if lock.tell() == 0 and lock.seek(0, os.SEEK_END) == 0:
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


def export_group_bundle(
    *,
    recipe_path: Path,
    trusted_source_root: Path,
    mapping_uri: str,
    output_root: Path,
    group_version: str,
    catalog_version: str,
) -> Path:
    if not VERSION_PATTERN.fullmatch(group_version) or not VERSION_PATTERN.fullmatch(catalog_version):
        raise ArtifactError("invalid group or catalog version")
    recipe_bytes = recipe_path.read_bytes()
    try:
        recipe = json.loads(recipe_bytes.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ArtifactError("invalid group recipe") from exc
    if (
        not isinstance(recipe, dict)
        or recipe.get("schemaVersion") != "feelm-group-recipe/1"
        or recipe.get("targetGeometryVersion") != GEOMETRY_VERSION
        or recipe.get("trainingPerformedByThisRecipe") is not False
    ):
        raise ArtifactError("unsupported group recipe")
    actual_runtime = {
        "python": sys.version.split()[0],
        "numpy": importlib.metadata.version("numpy"),
        "scipy": importlib.metadata.version("scipy"),
        "sklearn": importlib.metadata.version("scikit-learn"),
    }
    expected_runtime = {key: recipe["runtime"][key] for key in actual_runtime}
    if actual_runtime != expected_runtime:
        raise ArtifactError(f"source extraction runtime mismatch: expected {expected_runtime}, got {actual_runtime}")
    mapping, mapping_revision = _validated_mapping(mapping_uri)
    arrays = _source_arrays(recipe, trusted_source_root)
    recipe_revision = _revision(recipe_bytes)
    assignments, counts = _reference_rows(recipe, trusted_source_root, mapping, catalog_version, recipe_revision)
    parent = output_root / "groups"
    target = parent / group_version
    staging = parent / f".{group_version}.tmp-{uuid.uuid4().hex}"
    parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        raise ArtifactError("group version already exists")
    staging.mkdir()
    try:
        np.savez(staging / "arrays.npz", **arrays)
        pq.write_table(assignments, staging / "assignments.parquet", compression="zstd", use_dictionary=True)
        metadata = {
            "contract_version": CONTRACT_VERSION,
            "group_version": group_version,
            "catalog_version": catalog_version,
            "geometry_version": GEOMETRY_VERSION,
            "source_geometry_version": recipe["sourceGeometryVersion"],
            "mapping_revision": mapping_revision,
            "group_recipe_revision": recipe_revision,
            "built_at_utc": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "export_runtime": actual_runtime,
            "movie_count": counts["movie_count"],
            "mapped_movie_count": counts["mapped_movie_count"],
            "genre_missing_count": counts["genre_missing_count"],
            "sub_unsupported_count": counts["sub_unsupported_count"],
            "genre_missing_policy": "EXCLUDE_FROM_DISCOVERY",
            "public_taste_semantics": "PUBLIC_POPCORN_FLAVOR_V1",
            "source_files": {
                key: {"bytes": value["bytes"], "sha256": value["sha256"]}
                for key, value in recipe["sources"].items()
                if isinstance(value, dict) and "bytes" in value and "sha256" in value
            },
        }
        _write_json(staging / "metadata.json", metadata)
        (staging / "_SUCCESS").write_bytes(b"")
        files = [
            _manifest_entry(staging / name)
            for name in sorted(("_SUCCESS", "arrays.npz", "assignments.parquet", "metadata.json"))
        ]
        _write_json(staging / "manifest.json", {"contract_version": CONTRACT_VERSION, "files": files})
        load_group_bundle(staging.as_uri(), expected_mapping_revision=mapping_revision)
        with _publication_lock(parent):
            if target.exists():
                raise ArtifactError("group version already exists")
            os.rename(staging, target)
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    return target


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Export the pinned 8x128 group serving bundle")
    parser.add_argument("--recipe", required=True, type=Path)
    parser.add_argument("--trusted-source-root", required=True, type=Path)
    parser.add_argument("--mapping-uri", required=True)
    parser.add_argument("--output-root", required=True, type=Path)
    parser.add_argument("--group-version", required=True)
    parser.add_argument("--catalog-version", required=True)
    args = parser.parse_args(argv)
    result = export_group_bundle(
        recipe_path=args.recipe,
        trusted_source_root=args.trusted_source_root,
        mapping_uri=args.mapping_uri,
        output_root=args.output_root,
        group_version=args.group_version,
        catalog_version=args.catalog_version,
    )
    print(result)
    return 0


if __name__ == "__main__":
    sys.exit(main())
