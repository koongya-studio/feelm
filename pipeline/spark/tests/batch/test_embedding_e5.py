from __future__ import annotations

import math

import pytest
from pyspark.sql import Row, SparkSession

from batch.embedding.e5 import (
    DIMENSION,
    EMBEDDING_SCHEMA,
    METADATA_SCHEMA,
    MODEL_ID,
    QUERY_PREFIX,
    E5Config,
    _load_e5_embeddings_at_uri,
    _resolve_device,
    _validate_embedding_rows,
    _validate_vector,
    expected_model_version,
    load_e5_embeddings,
    save_e5_embeddings,
    seal_legacy_e5_embeddings,
)

REVISION = "d12875059715" + "a" * 28
EMBEDDING_TEST_SCHEMA = (
    "movie_id long, tmdb_id long, mapping_revision string, "
    "input_text_sha256 string, embedding array<float>"
)


@pytest.fixture(scope="module")
def spark():
    session = (
        SparkSession.builder.master("local[1]")
        .appName("embedding-e5-test")
        .config("spark.ui.enabled", "false")
        .getOrCreate()
    )
    session.sparkContext.setLogLevel("ERROR")
    yield session
    session.stop()


def test_e5_config_requires_pinned_revision_and_valid_batch_settings() -> None:
    assert E5Config(revision=REVISION).model_id == MODEL_ID
    assert expected_model_version(REVISION) == "e5-base-d12875059715"
    with pytest.raises(ValueError, match="revision"):
        E5Config(revision="")
    with pytest.raises(ValueError, match="revision"):
        E5Config(revision="main")
    with pytest.raises(ValueError, match="batch_size"):
        E5Config(revision=REVISION, batch_size=0)
    with pytest.raises(ValueError, match="max_length"):
        E5Config(revision=REVISION, max_length=513)
    with pytest.raises(ValueError, match="device"):
        E5Config(revision=REVISION, device="mps")
    with pytest.raises(ValueError, match="partitions"):
        E5Config(revision=REVISION, partitions=0)


def test_e5_vector_validation_requires_finite_normalized_768_dimensions() -> None:
    unit = [0.0] * DIMENSION
    unit[0] = 1.0
    _validate_vector(unit)
    with pytest.raises(ValueError, match="dimension"):
        _validate_vector(unit[:-1])
    invalid = unit.copy()
    invalid[1] = math.nan
    with pytest.raises(ValueError, match="finite"):
        _validate_vector(invalid)
    invalid = unit.copy()
    invalid[0] = 0.5
    with pytest.raises(ValueError, match="L2-normalized"):
        _validate_vector(invalid)


def test_e5_uses_query_prefix_for_symmetric_movie_similarity() -> None:
    assert QUERY_PREFIX == "query: "


def test_e5_device_resolution_requires_available_cuda() -> None:
    class FakeTorch:
        class cuda:
            @staticmethod
            def is_available() -> bool:
                return False

    assert _resolve_device(FakeTorch, "auto") == "cpu"
    assert _resolve_device(FakeTorch, "cpu") == "cpu"
    with pytest.raises(RuntimeError, match="CUDA"):
        _resolve_device(FakeTorch, "cuda")


def test_artifact_validation_rejects_non_768_or_nonfinite_vectors(spark) -> None:
    prefix = (1, 101, "sha256:" + "a" * 64, "a" * 64)
    short_vector = spark.createDataFrame([(*prefix, [0.0])], EMBEDDING_TEST_SCHEMA)
    with pytest.raises(ValueError, match="invalid rows"):
        _validate_embedding_rows(short_vector)

    nan_vector = spark.createDataFrame(
        [(*prefix, [float("nan")] + [0.0] * (DIMENSION - 1))],
        EMBEDDING_TEST_SCHEMA,
    )
    with pytest.raises(ValueError, match="invalid rows"):
        _validate_embedding_rows(nan_vector)

    zero_vector = spark.createDataFrame([(*prefix, [0.0] * DIMENSION)], EMBEDDING_TEST_SCHEMA)
    with pytest.raises(ValueError, match="non-normalized"):
        _validate_embedding_rows(zero_vector)


def test_artifact_manifest_roundtrip_and_file_checksum(spark, tmp_path) -> None:
    from batch.embedding.manifest import file_checksums

    unit = [1.0] + [0.0] * (DIMENSION - 1)
    rows = [(1, 101, "sha256:" + "a" * 64, "b" * 64, unit)]
    embeddings = spark.createDataFrame(rows, EMBEDDING_TEST_SCHEMA)
    output_uri = tmp_path.as_uri()
    version_uri = save_e5_embeddings(
        spark, embeddings, output_uri, "e5-test", E5Config(revision=REVISION)
    )
    loaded, metadata = load_e5_embeddings(spark, output_uri, "e5-test")
    assert loaded.count() == 1
    assert metadata["input_snapshot_sha256"]
    assert file_checksums(spark, version_uri)

    part = next((tmp_path / "movie-embeddings" / "e5-test" / "embeddings").glob("part-*.parquet"))
    with part.open("ab") as stream:
        stream.write(b"changed")
    with pytest.raises(ValueError, match="checksum mismatch"):
        load_e5_embeddings(spark, output_uri, "e5-test")


def test_seal_legacy_artifact_without_model_inference(spark, tmp_path) -> None:
    legacy = tmp_path / "movie-embeddings" / "old"
    unit = [1.0] + [0.0] * (DIMENSION - 1)
    mapping_revision = "sha256:" + "a" * 64
    spark.createDataFrame(
        [(1, 101, mapping_revision, "b" * 64, unit)], EMBEDDING_TEST_SCHEMA
    ).write.parquet(str(legacy / "embeddings"))
    spark.createDataFrame(
        [
            (
                "old", MODEL_ID, REVISION, DIMENSION, QUERY_PREFIX, 512,
                mapping_revision, 1,
            )
        ],
        "embedding_version string, model_id string, model_revision string, dimension long, "
        "query_prefix string, max_length long, mapping_revision string, row_count long",
    ).write.parquet(str(legacy / "metadata"))

    seal_legacy_e5_embeddings(spark, tmp_path.as_uri(), "old", "sealed")
    loaded, metadata = load_e5_embeddings(spark, tmp_path.as_uri(), "sealed")
    assert loaded.count() == 1
    assert metadata["model_revision"] == REVISION


@pytest.mark.parametrize(
    ("tmdb_id", "mapping_revision", "input_hash"),
    [
        (0, "sha256:" + "a" * 64, "b" * 64),
        (2_147_483_648, "sha256:" + "a" * 64, "b" * 64),
        (101, "wrong", "b" * 64),
        (101, "sha256:" + "A" * 64, "b" * 64),
        (101, "sha256:" + "a" * 64, "g" * 64),
        (101, "sha256:" + "a" * 64, "B" * 64),
    ],
)
def test_artifact_publish_and_read_reject_invalid_id_or_hash(
    spark, tmp_path, tmdb_id, mapping_revision, input_hash
) -> None:
    from batch.embedding.manifest import file_checksums, manifest_json

    unit = [1.0] + [0.0] * (DIMENSION - 1)
    embeddings = spark.createDataFrame(
        [(1, tmdb_id, mapping_revision, input_hash, unit)], EMBEDDING_TEST_SCHEMA
    )
    with pytest.raises(ValueError, match="invalid rows"):
        save_e5_embeddings(spark, embeddings, tmp_path.as_uri(), "invalid", E5Config(REVISION))
    assert not (tmp_path / "movie-embeddings" / "invalid").exists()

    version_uri = (tmp_path / "read-invalid").as_uri()
    embeddings.select(*EMBEDDING_SCHEMA.fieldNames()).write.parquet(f"{version_uri}/embeddings")
    metadata = Row(
        embedding_version="read-invalid", model_id=MODEL_ID, model_revision=REVISION,
        dimension=DIMENSION, query_prefix=QUERY_PREFIX, max_length=512,
        mapping_revision=mapping_revision, row_count=1, text_template_version="tmdb-e5-v1",
        input_snapshot_sha256="0" * 64,
    )
    spark.createDataFrame([metadata], METADATA_SCHEMA).write.parquet(f"{version_uri}/metadata")
    manifest = manifest_json(metadata, file_checksums(spark, version_uri))
    spark.createDataFrame([(manifest,)], "json string").write.parquet(f"{version_uri}/manifest")
    with pytest.raises(ValueError, match="invalid rows|mapping revision"):
        _load_e5_embeddings_at_uri(spark, version_uri, "read-invalid")
