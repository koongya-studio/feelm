from __future__ import annotations

import hashlib
import io
import json
from pathlib import Path

import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq
import pytest

from feelm_consumer import group_artifacts
from feelm_consumer.artifacts import ArtifactError
from feelm_consumer.group_artifacts import assign_group, compare_live_mapping, load_group_bundle

MAPPING_REVISION = "sha256:" + "a" * 64
RECIPE_REVISION = "sha256:" + "b" * 64


def _write_bundle(root: Path) -> None:
    root.mkdir()
    genre_ids = np.arange(1, 20, dtype=np.int64)
    keyword_ids = np.arange(100, 6643, dtype=np.int64)
    keyword_components = np.zeros((64, 6543), dtype=np.float64)
    keyword_components[0, 0] = 1.0
    top_centers = np.zeros((8, 131), dtype=np.float64)
    top_centers[0, 0] = 1.0
    arrays = {
        "genre_ids": genre_ids,
        "keyword_ids": keyword_ids,
        "keyword_idf": np.ones(6543, dtype=np.float64),
        "keyword_components": keyword_components,
        "text_tokens": np.asarray([f"token{index}" for index in range(16000)]),
        "text_idf": np.ones(16000, dtype=np.float64),
        "text_components": np.zeros((48, 16000), dtype=np.float64),
        "top_centers": top_centers,
        "child_centers": np.zeros((8, 128, 131), dtype=np.float64),
        "group_means": np.zeros((1024, 131), dtype=np.float64),
        "top_weights": np.asarray([1.0, 0.0, 0.0], dtype=np.float64),
        "sub_weights": np.asarray([0.5, 0.35, 0.15], dtype=np.float64),
        "offsets": np.arange(9, dtype=np.int64) * 128,
    }
    np.savez_compressed(root / "arrays.npz", **arrays)
    assignment_schema = pa.schema(
        [
            pa.field("service_movie_id", pa.int64()),
            pa.field("tmdb_id", pa.int64()),
            pa.field("taste_id", pa.int32()),
            pa.field("child_id", pa.int32()),
            pa.field("group_id", pa.int32()),
            pa.field("top_supported", pa.bool_()),
            pa.field("sub_supported", pa.bool_()),
            pa.field("group_mapped", pa.bool_()),
            pa.field("catalog_version", pa.string()),
            pa.field("geometry_version", pa.string()),
            pa.field("group_recipe_revision", pa.string()),
            pa.field("mapping_revision", pa.string()),
            pa.field("vector_hash", pa.string()),
            pa.field("source_hash", pa.string()),
        ]
    )
    rows = [
        {
            "service_movie_id": 20,
            "tmdb_id": 200,
            "taste_id": 0,
            "child_id": 0,
            "group_id": 0,
            "top_supported": False,
            "sub_supported": True,
            "group_mapped": False,
            "catalog_version": "catalog-v1",
            "geometry_version": "g913-gkt128-mean-v1",
            "group_recipe_revision": RECIPE_REVISION,
            "mapping_revision": MAPPING_REVISION,
            "vector_hash": "sha256:" + "c" * 64,
            "source_hash": "sha256:" + "d" * 64,
        },
        {
            "service_movie_id": 10,
            "tmdb_id": 100,
            "taste_id": 0,
            "child_id": 0,
            "group_id": 0,
            "top_supported": True,
            "sub_supported": True,
            "group_mapped": True,
            "catalog_version": "catalog-v1",
            "geometry_version": "g913-gkt128-mean-v1",
            "group_recipe_revision": RECIPE_REVISION,
            "mapping_revision": MAPPING_REVISION,
            "vector_hash": "sha256:" + "e" * 64,
            "source_hash": "sha256:" + "f" * 64,
        },
    ]
    pq.write_table(pa.Table.from_pylist(rows, schema=assignment_schema), root / "assignments.parquet")
    metadata = {
        "contract_version": "group-serving-artifacts-v1",
        "group_version": "groups-v1",
        "catalog_version": "catalog-v1",
        "geometry_version": "g913-gkt128-mean-v1",
        "mapping_revision": MAPPING_REVISION,
        "group_recipe_revision": RECIPE_REVISION,
        "movie_count": 2,
        "mapped_movie_count": 1,
        "genre_missing_count": 1,
        "genre_missing_policy": "EXCLUDE_FROM_DISCOVERY",
        "public_taste_semantics": "RECOMMENDATION_INTERNAL_NOT_PUBLIC_POPCORN",
    }
    (root / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
    (root / "_SUCCESS").write_bytes(b"")
    files = []
    for name in ("_SUCCESS", "arrays.npz", "assignments.parquet", "metadata.json"):
        data = (root / name).read_bytes()
        files.append({"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
    (root / "manifest.json").write_text(
        json.dumps({"contract_version": "group-serving-artifacts-v1", "files": files}), encoding="utf-8"
    )


def test_load_group_bundle_and_lookup_assignment(tmp_path: Path) -> None:
    root = tmp_path / "bundle"
    _write_bundle(root)

    bundle = load_group_bundle(root.as_uri(), expected_mapping_revision=MAPPING_REVISION)

    assert bundle.service_movie_ids.tolist() == [10, 20]
    assert bundle.assignment_for(10).group_mapped is True
    assert bundle.assignment_for(np.int64(10)).group_mapped is True
    assert bundle.assignment_for(20).group_mapped is False
    assert bundle.assignment_for(999) is None


def test_load_group_bundle_rejects_mapping_revision_mismatch(tmp_path: Path) -> None:
    root = tmp_path / "bundle"
    _write_bundle(root)

    with pytest.raises(ArtifactError, match="mapping revision mismatch"):
        load_group_bundle(root.as_uri(), expected_mapping_revision="sha256:" + "9" * 64)


def test_compare_live_mapping_allows_additions_and_removals(tmp_path: Path) -> None:
    root = tmp_path / "bundle"
    _write_bundle(root)
    bundle = load_group_bundle(root.as_uri())

    addition = compare_live_mapping(bundle, [(10, 100), (20, 200), (30, 300)])
    removal = compare_live_mapping(bundle, [(10, 100)])

    assert addition.activation_allowed is True
    assert addition.unchanged_count == 2
    assert addition.added_service_movie_ids == (30,)
    assert addition.removed_service_movie_ids == ()
    assert bundle.assignment_for(30) is None
    assert removal.activation_allowed is True
    assert removal.unchanged_count == 1
    assert removal.added_service_movie_ids == ()
    assert removal.removed_service_movie_ids == (20,)


def test_compare_live_mapping_blocks_reused_service_id(tmp_path: Path) -> None:
    root = tmp_path / "bundle"
    _write_bundle(root)
    bundle = load_group_bundle(root.as_uri())

    result = compare_live_mapping(bundle, [(10, 999), (20, 200)])

    assert result.activation_allowed is False
    assert result.unchanged_count == 1
    assert result.rebound_service_movie_ids == (10,)


@pytest.mark.parametrize(
    "pairs",
    [
        [(10, 100), (10, 200)],
        [(10, 100), (20, 100)],
        [(0, 100)],
        [(True, 100)],
    ],
)
def test_compare_live_mapping_rejects_invalid_or_duplicate_pairs(
    tmp_path: Path, pairs: list[tuple[int, int]]
) -> None:
    root = tmp_path / "bundle"
    _write_bundle(root)
    bundle = load_group_bundle(root.as_uri())

    with pytest.raises(ArtifactError, match="live service mapping"):
        compare_live_mapping(bundle, pairs)


def test_load_group_bundle_rejects_file_changed_after_manifest(tmp_path: Path) -> None:
    root = tmp_path / "bundle"
    _write_bundle(root)
    with (root / "metadata.json").open("ab") as stream:
        stream.write(b" ")

    with pytest.raises(ArtifactError, match="does not match manifest"):
        load_group_bundle(root.as_uri())


def test_assign_group_excludes_genre_missing_even_with_keyword_support(tmp_path: Path) -> None:
    root = tmp_path / "bundle"
    _write_bundle(root)
    bundle = load_group_bundle(root.as_uri())

    unsupported_top = assign_group(bundle, [], [100], "", id_mapping_verified=True)
    supported = assign_group(bundle, [1], [100], "", id_mapping_verified=True)
    unverified = assign_group(bundle, 1, [100], "", id_mapping_verified="true")  # type: ignore[arg-type]

    assert unsupported_top.sub_supported is True
    assert unsupported_top.top_supported is False
    assert unsupported_top.group_mapped is False
    assert supported.top_supported is True
    assert supported.sub_supported is True
    assert supported.group_mapped is True
    assert unverified.group_mapped is False
    assert supported.taste_id == 0
    assert supported.child_id == 0


def test_hdfs_group_file_uses_pyarrow_filesystem_without_path_conversion(monkeypatch) -> None:
    seen: dict[str, object] = {}

    class FileSystem:
        def get_file_info(self, path: str):
            seen["info_path"] = path
            return type("Info", (), {"type": group_artifacts.pafs.FileType.File, "size": 4})()

        def open_input_file(self, path: str):
            seen["open_path"] = path
            return io.BytesIO(b"data")

    filesystem = FileSystem()

    class FileSystemFactory:
        @staticmethod
        def from_uri(uri: str):
            seen["uri"] = uri
            return filesystem, "/artifacts/groups/groups-v1"

    monkeypatch.setattr(group_artifacts.pafs, "FileSystem", FileSystemFactory)

    assert group_artifacts._read_bytes(
        "hdfs://namenode:8020/artifacts/groups/groups-v1", "metadata.json", 100
    ) == b"data"
    assert seen == {
        "uri": "hdfs://namenode:8020/artifacts/groups/groups-v1",
        "info_path": "/artifacts/groups/groups-v1/metadata.json",
        "open_path": "/artifacts/groups/groups-v1/metadata.json",
    }
