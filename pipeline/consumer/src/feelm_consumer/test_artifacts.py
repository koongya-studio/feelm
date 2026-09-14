# ruff: noqa: E501

import pyarrow as pa
import pyarrow.parquet as pq
import pytest

from feelm_consumer import artifacts
from feelm_consumer.artifacts import ArtifactError, load_bundle
from feelm_consumer.profile import ProfileInput, fold_in

MAPPING_REVISION = "sha256:" + "a" * 64
METADATA_REVISION = "sha256:" + "b" * 64
ITEM_FACTORS_SCHEMA = pa.schema([
    pa.field("movielens_movie_id", pa.int32()),
    pa.field("features", pa.list_(pa.float32())),
])
MAPPING_SCHEMA = pa.schema([
    pa.field("service_movie_id", pa.int64()),
    pa.field("movielens_movie_id", pa.int32()),
    pa.field("mapping_status", pa.string()),
    pa.field("mapping_revision", pa.string()),
])


def _write_table(path, rows: list[dict[str, object]], schema: pa.Schema | None = None) -> None:
    path.mkdir()
    path = path / "part-00000.parquet"
    pq.write_table(pa.Table.from_pylist(rows, schema=schema), path)


def test_load_bundle_rejects_metadata_from_another_snapshot(tmp_path) -> None:
    model_root = tmp_path / "model"
    model_root.mkdir()
    _write_table(
        model_root / "metadata",
        [{
            "contract_version": "als-artifacts-v2", "artifact_stage": "SERVING", "model_version": "als-v1",
            "factor_dimension": 2, "reg_param": 0.1, "input_snapshot": "snapshot-a", "mapping_revision": MAPPING_REVISION, "movie_metadata_revision": METADATA_REVISION,
        }],
    )
    _write_table(model_root / "item-factors", [{"movielens_movie_id": 1, "features": [1.0, 0.0]}], ITEM_FACTORS_SCHEMA)
    mapping = tmp_path / "mapping.parquet"
    _write_table(mapping, [{"service_movie_id": 10, "movielens_movie_id": 1, "mapping_status": "MATCHED", "mapping_revision": MAPPING_REVISION}], MAPPING_SCHEMA)
    metadata = tmp_path / "metadata.parquet"
    _write_table(metadata, [{
        "service_movie_id": 10, "title": "영화", "genre_codes": ["DRAMA"],
        "representative_flavor_code": "FLAVOR_X", "input_snapshot": "snapshot-b", "movie_metadata_revision": METADATA_REVISION,
    }])

    with pytest.raises(ArtifactError, match="invalid metadata snapshot"):
        load_bundle(str(model_root), str(mapping), str(metadata))


def test_load_bundle_reads_matching_fixed_metadata_snapshot(tmp_path) -> None:
    model_root = tmp_path / "model"
    model_root.mkdir()
    _write_table(
        model_root / "metadata",
        [{
            "contract_version": "als-artifacts-v2", "artifact_stage": "SERVING", "model_version": "als-v1",
            "factor_dimension": 1, "reg_param": 0, "input_snapshot": "snapshot-a", "mapping_revision": MAPPING_REVISION, "movie_metadata_revision": METADATA_REVISION,
        }],
    )
    _write_table(model_root / "item-factors", [{"movielens_movie_id": 1, "features": [1.0]}], ITEM_FACTORS_SCHEMA)
    mapping = tmp_path / "mapping.parquet"
    _write_table(mapping, [{"service_movie_id": 10, "movielens_movie_id": 1, "mapping_status": "MATCHED", "mapping_revision": MAPPING_REVISION}], MAPPING_SCHEMA)
    metadata = tmp_path / "metadata.parquet"
    _write_table(metadata, [{
        "service_movie_id": 10, "title": "영화", "genre_codes": ["DRAMA"],
        "representative_flavor_code": None, "input_snapshot": "snapshot-a", "movie_metadata_revision": METADATA_REVISION,
    }])

    bundle = load_bundle(model_root.as_uri(), mapping.as_uri(), metadata.as_uri())
    assert bundle.movie_metadata[10].genre_codes == ("DRAMA",)
    folded = fold_in(bundle.factors, [ProfileInput(1, 4.0, "rating")], bundle.reg_param)
    assert folded is not None
    assert folded.vector.tolist() == [4.0]


def test_load_bundle_rejects_revision_mismatch_and_non_unique_matched_mapping(tmp_path) -> None:
    model_root = tmp_path / "model"
    model_root.mkdir()
    _write_table(model_root / "metadata", [{"contract_version": "als-artifacts-v2", "artifact_stage": "SERVING", "model_version": "als-v1", "factor_dimension": 1, "reg_param": 1.0, "input_snapshot": "snapshot-a", "mapping_revision": MAPPING_REVISION, "movie_metadata_revision": METADATA_REVISION}])
    _write_table(model_root / "item-factors", [{"movielens_movie_id": 1, "features": [1.0]}], ITEM_FACTORS_SCHEMA)
    mapping = tmp_path / "mapping.parquet"
    _write_table(mapping, [{"service_movie_id": 10, "movielens_movie_id": 1, "mapping_status": "MATCHED", "mapping_revision": MAPPING_REVISION}, {"service_movie_id": 11, "movielens_movie_id": 1, "mapping_status": "MATCHED", "mapping_revision": MAPPING_REVISION}], MAPPING_SCHEMA)
    metadata = tmp_path / "metadata.parquet"
    _write_table(metadata, [{"service_movie_id": 10, "title": "영화", "genre_codes": ["DRAMA"], "representative_flavor_code": None, "input_snapshot": "snapshot-a", "movie_metadata_revision": METADATA_REVISION}])
    with pytest.raises(ArtifactError, match="one-to-one"):
        load_bundle(model_root.as_uri(), mapping.as_uri(), metadata.as_uri())


def test_zero_regularization_rejects_singular_fold_in_matrix() -> None:
    assert fold_in(
        {1: artifacts.np.array([1.0, 0.0])},
        [ProfileInput(1, 4.0, "rating")],
        reg_param=0,
    ) is None


@pytest.mark.parametrize(
    ("factor_schema", "mapping_rows", "mapping_schema", "error"),
    [
        (
            pa.schema([pa.field("movielens_movie_id", pa.int64()), pa.field("features", pa.list_(pa.float32()))]),
            [{"service_movie_id": 10, "movielens_movie_id": 1, "mapping_status": "MATCHED", "mapping_revision": MAPPING_REVISION}],
            MAPPING_SCHEMA,
            "item factors has incompatible physical types",
        ),
        (
            ITEM_FACTORS_SCHEMA,
            [{"service_movie_id": 10, "movielens_movie_id": 1, "mapping_status": "MATCHED", "mapping_revision": MAPPING_REVISION}],
            pa.schema([pa.field("service_movie_id", pa.int64()), pa.field("movielens_movie_id", pa.int64()), pa.field("mapping_status", pa.string()), pa.field("mapping_revision", pa.string())]),
            "mapping snapshot has incompatible physical types",
        ),
        (
            ITEM_FACTORS_SCHEMA,
            [{"service_movie_id": 10, "movielens_movie_id": 1, "mapping_status": "MATCHED", "mapping_revision": MAPPING_REVISION}, {"service_movie_id": 11, "movielens_movie_id": 2, "mapping_status": "SERVICE_ONLY_CONTENT_BASED", "mapping_revision": MAPPING_REVISION}],
            MAPPING_SCHEMA,
            "non-MATCHED mapping must not have a MovieLens ID",
        ),
    ],
)
def test_load_bundle_rejects_invalid_factor_or_mapping_contract(
    tmp_path, factor_schema, mapping_rows, mapping_schema, error
) -> None:
    model_root = tmp_path / "model"
    model_root.mkdir()
    _write_table(model_root / "metadata", [{"contract_version": "als-artifacts-v2", "artifact_stage": "SERVING", "model_version": "als-v1", "factor_dimension": 1, "reg_param": 0.1, "input_snapshot": "snapshot-a", "mapping_revision": MAPPING_REVISION, "movie_metadata_revision": METADATA_REVISION}])
    _write_table(model_root / "item-factors", [{"movielens_movie_id": 1, "features": [1.0]}], factor_schema)
    mapping = tmp_path / "mapping.parquet"
    _write_table(mapping, mapping_rows, mapping_schema)
    metadata = tmp_path / "metadata.parquet"
    _write_table(metadata, [{"service_movie_id": 10, "title": "영화", "genre_codes": ["DRAMA"], "representative_flavor_code": None, "input_snapshot": "snapshot-a", "movie_metadata_revision": METADATA_REVISION}])

    with pytest.raises(ArtifactError, match=error):
        load_bundle(model_root.as_uri(), mapping.as_uri(), metadata.as_uri())


def test_hdfs_uri_is_passed_to_pyarrow_filesystem_without_path_conversion(monkeypatch) -> None:
    seen: dict[str, object] = {}
    filesystem = object()

    class Dataset:
        def read(self):
            return pa.table({"value": [1]})

    class FileSystem:
        @staticmethod
        def from_uri(uri: str):
            seen["uri"] = uri
            return filesystem, "/artifacts/models/current"

    def parquet_dataset(path: str, *, filesystem: object):
        seen["path"] = path
        seen["filesystem"] = filesystem
        return Dataset()

    monkeypatch.setattr(artifacts.pafs, "FileSystem", FileSystem)
    monkeypatch.setattr(artifacts.pq, "ParquetDataset", parquet_dataset)

    table = artifacts._dataset("hdfs://namenode:8020/artifacts/models/current", "metadata")

    assert table.num_rows == 1
    assert seen == {
        "uri": "hdfs://namenode:8020/artifacts/models/current",
        "path": "/artifacts/models/current/metadata",
        "filesystem": filesystem,
    }
