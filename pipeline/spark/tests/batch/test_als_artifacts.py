import multiprocessing
import os
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from pathlib import Path
from shutil import copytree
from threading import Barrier
from uuid import uuid4

import pytest
from pyspark.errors import AnalysisException
from pyspark.ml.recommendation import ALSModel
from pyspark.sql import SparkSession
from pyspark.sql import functions as F

from batch.train import artifacts
from batch.train.artifacts import (
    FINAL_EVALUATION,
    MODEL_SELECTION,
    SERVING,
    AlsArtifactMetadata,
    artifact_version_uri,
    load_als_artifacts,
    save_als_artifacts,
)
from batch.train.settings import AlsConfig
from batch.train.training import train_als_model
from tests.fixtures.als_baseline import RATING_ROWS, RATINGS_SCHEMA


@pytest.fixture(scope="module")
def spark() -> SparkSession:
    session = (
        SparkSession.builder.master("local[1]")
        .appName("als-artifacts-test")
        .config("spark.ui.enabled", "false")
        .config("spark.sql.shuffle.partitions", "1")
        .getOrCreate()
    )
    session.sparkContext.setLogLevel("ERROR")
    yield session
    session.stop()


@pytest.fixture(scope="module")
def model(spark: SparkSession) -> ALSModel:
    ratings = spark.createDataFrame(RATING_ROWS, RATINGS_SCHEMA)
    return train_als_model(
        ratings,
        config=AlsConfig(rank=3, reg_param=0.1, max_iter=2),
        seed=42,
    )


@pytest.fixture
def storage_root(tmp_path: Path) -> str:
    # Opt-in server verification; each test uses its own disposable output root.
    root = os.environ.get("ALS_ARTIFACT_TEST_ROOT", tmp_path.as_uri())
    output = f"{root.rstrip('/')}/als-check-{uuid4().hex}"
    print(f"artifact verification output: {output}")
    return output


def _children(spark: SparkSession, uri: str) -> set[str]:
    path, file_system, _ = artifacts._hadoop_path(spark, uri)
    return {status.getPath().getName() for status in file_system.listStatus(path)}


def _serving_metadata(model_version: str) -> AlsArtifactMetadata:
    return AlsArtifactMetadata(
        model_version=model_version,
        artifact_stage=SERVING,
        input_snapshot="sha256:fixture-v1",
        input_uri="/fixtures/movie-mapping/v1",
        trained_at_utc="2026-09-08T05:30:00Z",
        config=AlsConfig(rank=3, reg_param=0.1, max_iter=2),
        als_seed=42,
        source_ratings_count=len(RATING_ROWS),
        training_ratings_count=len(RATING_ROWS),
        implementation_revision="test-revision",
    )


@pytest.mark.parametrize("stage", [SERVING, MODEL_SELECTION, FINAL_EVALUATION])
def test_saves_and_loads_complete_artifact_version(
    spark: SparkSession,
    model: ALSModel,
    storage_root: str,
    stage: str,
) -> None:
    metadata = _serving_metadata("als-local-v1")
    if stage != SERVING:
        metadata = replace(
            metadata,
            artifact_stage=stage,
            source_ratings_count=75,
            split_method="per-user-chronological-v1",
            split_seed=17,
            train_ratio=0.8,
            validation_ratio=0.1,
            min_eval_ratings=3,
        )

    version_uri = save_als_artifacts(spark, model, storage_root, metadata)
    loaded = load_als_artifacts(spark, storage_root, metadata.model_version)

    assert version_uri == f"{storage_root}/als/{metadata.model_version}"
    assert _children(spark, version_uri) == {
        "model",
        "user-factors",
        "item-factors",
        "metadata",
    }
    assert loaded.version_uri == version_uri
    assert loaded.model.rank == 3
    assert loaded.metadata.asDict() == {
        "contract_version": "als-artifacts-v1",
        "model_version": metadata.model_version,
        "model_uid": model.uid,
        "artifact_stage": stage,
        "input_snapshot": "sha256:fixture-v1",
        "input_uri": "/fixtures/movie-mapping/v1",
        "trained_at_utc": "2026-09-08T05:30:00Z",
        "spark_version": spark.version,
        "rank": 3,
        "reg_param": 0.1,
        "max_iter": 2,
        "als_seed": 42,
        "factor_dimension": 3,
        "split_method": metadata.split_method,
        "split_seed": metadata.split_seed,
        "train_ratio": metadata.train_ratio,
        "validation_ratio": metadata.validation_ratio,
        "min_eval_ratings": metadata.min_eval_ratings,
        "source_ratings_count": metadata.source_ratings_count,
        "training_ratings_count": len(RATING_ROWS),
        "user_factor_count": 6,
        "item_factor_count": 14,
        "implementation_revision": "test-revision",
    }
    assert loaded.user_factors.count() == 6
    assert loaded.item_factors.count() == 14

    prediction_columns = ["movielens_user_id", "movielens_movie_id", "prediction"]
    original_predictions = (
        model.transform(spark.createDataFrame(RATING_ROWS, RATINGS_SCHEMA))
        .select(*prediction_columns)
        .orderBy(*prediction_columns[:2])
        .collect()
    )
    loaded_predictions = (
        loaded.model.transform(spark.createDataFrame(RATING_ROWS, RATINGS_SCHEMA))
        .select(*prediction_columns)
        .orderBy(*prediction_columns[:2])
        .collect()
    )
    assert [tuple(row[:2]) for row in loaded_predictions] == [
        tuple(row[:2]) for row in original_predictions
    ]
    assert [row["prediction"] for row in loaded_predictions] == pytest.approx(
        [row["prediction"] for row in original_predictions]
    )


def test_does_not_overwrite_existing_version(
    spark: SparkSession,
    model: ALSModel,
    storage_root: str,
) -> None:
    metadata = _serving_metadata("als-existing-v1")
    save_als_artifacts(spark, model, storage_root, metadata)

    with pytest.raises(FileExistsError, match="already exists"):
        save_als_artifacts(spark, model, storage_root, metadata)

    loaded = load_als_artifacts(spark, storage_root, metadata.model_version)
    assert loaded.metadata["model_uid"] == model.uid


def test_does_not_publish_or_keep_temporary_version_after_write_failure(
    spark: SparkSession,
    model: ALSModel,
    storage_root: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    metadata = _serving_metadata("als-failed-v1")

    def fail_metadata_write(
        spark_session: SparkSession,
        version_uri: str,
        metadata_row: dict[str, object],
    ) -> None:
        del spark_session, version_uri, metadata_row
        raise RuntimeError("simulated metadata failure")

    monkeypatch.setattr(artifacts, "_write_metadata", fail_metadata_write)

    with pytest.raises(RuntimeError, match="simulated metadata failure"):
        save_als_artifacts(spark, model, storage_root, metadata)

    assert not artifacts._path_exists(spark, f"{storage_root}/als/{metadata.model_version}")
    assert _children(spark, f"{storage_root}/als") == set()
    with pytest.raises(FileNotFoundError):
        load_als_artifacts(spark, storage_root, metadata.model_version)


def test_validates_artifact_metadata_before_writing(
    spark: SparkSession,
    model: ALSModel,
    tmp_path: Path,
) -> None:
    metadata = replace(
        _serving_metadata("als-invalid-v1"),
        training_ratings_count=len(RATING_ROWS) - 1,
    )

    with pytest.raises(ValueError, match="full input snapshot"):
        save_als_artifacts(spark, model, str(tmp_path), metadata)

    assert not (tmp_path / "als" / metadata.model_version).exists()


@pytest.mark.parametrize(
    ("parameter", "wrong_column", "error"),
    [
        ("userCol", "user", "model user column"),
        ("itemCol", "item", "model item column"),
    ],
)
def test_rejects_model_with_columns_outside_artifact_contract_before_writing(
    spark: SparkSession,
    model: ALSModel,
    tmp_path: Path,
    parameter: str,
    wrong_column: str,
    error: str,
) -> None:
    incompatible_model = model.copy({model.getParam(parameter): wrong_column})
    metadata = _serving_metadata(f"als-invalid-{parameter.lower()}-v1")

    with pytest.raises(ValueError, match=error):
        save_als_artifacts(spark, incompatible_model, str(tmp_path), metadata)

    assert not (tmp_path / "als" / metadata.model_version).exists()


def test_rejects_evaluation_ratios_without_positive_test_split(
    spark: SparkSession,
    model: ALSModel,
    tmp_path: Path,
) -> None:
    metadata = replace(
        _serving_metadata("als-invalid-split-v1"),
        artifact_stage=MODEL_SELECTION,
        training_ratings_count=48,
        split_method="per-user-chronological-v1",
        split_seed=42,
        train_ratio=0.7,
        validation_ratio=0.3,
        min_eval_ratings=3,
    )

    with pytest.raises(ValueError, match="positive test ratio"):
        save_als_artifacts(spark, model, str(tmp_path), metadata)


@pytest.mark.parametrize(
    ("output_uri", "expected"),
    [
        ("/models", "/models/als/als-v1"),
        ("file:///models", "file:///models/als/als-v1"),
        ("hdfs://namenode:8020/models", "hdfs://namenode:8020/models/als/als-v1"),
    ],
)
def test_builds_local_and_hdfs_version_uris(output_uri: str, expected: str) -> None:
    assert artifact_version_uri(output_uri, "als-v1") == expected


def test_rejects_storage_without_atomic_rename_contract() -> None:
    with pytest.raises(ValueError, match="local and hdfs"):
        artifact_version_uri("s3a://bucket/models", "als-v1")


def test_concurrent_publication_keeps_exactly_one_complete_directory(
    spark: SparkSession, storage_root: str
) -> None:
    # Exercise the actual filesystem operation without repeating ALS training.
    def publish(source: str, destination: str, barrier: Barrier) -> bool:
        barrier.wait(timeout=10)
        try:
            artifacts._publish_directory(spark, source, destination)
            return True
        except FileExistsError:
            return False

    with ThreadPoolExecutor(max_workers=2) as pool:
        for attempt in range(30):
            root = f"{storage_root}/{attempt}"
            sources = [f"{root}/first", f"{root}/second"]
            for source in sources:
                path, file_system, _ = artifacts._hadoop_path(
                    spark, f"{source}/{source.rsplit('/', 1)[-1]}"
                )
                assert file_system.mkdirs(path)
            destination = f"{root}/published"
            barrier = Barrier(2)

            futures = [pool.submit(publish, source, destination, barrier) for source in sources]
            results = [future.result(timeout=30) for future in futures]
            assert results.count(True) == 1, f"attempt={attempt}, results={results}"
            winner = sources[results.index(True)]
            loser = sources[results.index(False)]
            assert _children(spark, destination) == {winner.rsplit("/", 1)[-1]}
            assert not artifacts._path_exists(spark, winner)
            assert artifacts._path_exists(spark, loser)


def _publish_in_process(source, destination, barrier, results) -> None:
    barrier.wait(timeout=10)
    try:
        artifacts._publish_local_directory(source, destination)
        results.put((source.name, True))
    except FileExistsError:
        results.put((source.name, False))


def test_local_publication_lock_protects_separate_processes(tmp_path: Path) -> None:
    context = multiprocessing.get_context("spawn")
    barrier, results = context.Barrier(2), context.Queue()
    destination = tmp_path / "published"
    processes = []
    for name in ("first", "second"):
        source = tmp_path / name
        (source / name).mkdir(parents=True)
        processes.append(
            context.Process(
                target=_publish_in_process, args=(source, destination, barrier, results)
            )
        )
    try:
        for process in processes:
            process.start()
        for process in processes:
            process.join(timeout=30)
            assert process.exitcode == 0
        outcomes = dict(results.get(timeout=10) for _ in processes)
        assert sum(outcomes.values()) == 1
        winner = next(name for name, success in outcomes.items() if success)
        loser = next(name for name, success in outcomes.items() if not success)
        assert {path.name for path in destination.iterdir()} == {winner}
        assert not (tmp_path / winner).exists()
        assert (tmp_path / loser / loser).is_dir()
    finally:
        for process in processes:
            if process.is_alive():
                process.terminate()
                process.join(timeout=10)
        results.close()


@pytest.fixture(scope="module")
def saved_version(spark: SparkSession, model: ALSModel, tmp_path_factory) -> Path:
    output = tmp_path_factory.mktemp("als-artifact-corruption-source")
    return Path(save_als_artifacts(spark, model, str(output), _serving_metadata("als-check-v1")))


@pytest.mark.parametrize("component", ["model", "user-factors", "item-factors", "metadata"])
def test_rejects_missing_artifact_component(
    spark: SparkSession, saved_version: Path, tmp_path: Path, component: str
) -> None:
    version = tmp_path / "als" / saved_version.name
    copytree(saved_version, version)
    (version / component).rename(tmp_path / "removed-component")

    with pytest.raises(AnalysisException, match="PATH_NOT_FOUND"):
        load_als_artifacts(spark, str(tmp_path), saved_version.name)


def test_rejects_loaded_model_with_columns_outside_artifact_contract(
    spark: SparkSession,
    model: ALSModel,
    saved_version: Path,
    tmp_path: Path,
) -> None:
    version = tmp_path / "als" / saved_version.name
    copytree(saved_version, version)
    incompatible_model = model.copy({model.userCol: "user"})
    incompatible_model.write().overwrite().save(str(version / "model"))

    with pytest.raises(ValueError, match="model user column"):
        load_als_artifacts(spark, str(tmp_path), saved_version.name)


@pytest.mark.parametrize(
    ("damage", "error"),
    [
        ("nan", "invalid rows"),
        ("length", "invalid rows"),
        ("duplicate", "duplicate"),
        ("count", "count does not match"),
        ("value", "do not match the ALSModel"),
    ],
)
def test_rejects_corrupt_item_factors(
    spark: SparkSession, saved_version: Path, tmp_path: Path, damage: str, error: str
) -> None:
    version = tmp_path / "als" / saved_version.name
    copytree(saved_version, version)
    factors = spark.read.parquet(str(saved_version / "item-factors"))
    if damage == "nan":
        factors = factors.withColumn("features", F.array_repeat(F.lit(float("nan")), 3))
    elif damage == "length":
        factors = factors.withColumn("features", F.slice("features", 1, 2))
    elif damage == "duplicate":
        factors = factors.withColumn("movielens_movie_id", F.lit(1))
    elif damage == "count":
        factors = factors.filter("movielens_movie_id != 1")
    else:
        factors = factors.withColumn("features", F.transform("features", lambda x: x + F.lit(1.0)))
    factors.withColumn("features", F.col("features").cast("array<float>")).write.mode(
        "overwrite"
    ).parquet(str(version / "item-factors"))

    with pytest.raises(ValueError, match=error):
        load_als_artifacts(spark, str(tmp_path), saved_version.name)


@pytest.mark.parametrize(
    ("damage", "error"),
    [
        ("contract", "unsupported artifact contract"),
        ("uid", "UID"),
        ("null", "must not be null"),
        ("rows", "exactly one row"),
        ("schema", "incompatible type"),
    ],
)
def test_rejects_corrupt_metadata(
    spark: SparkSession, saved_version: Path, tmp_path: Path, damage: str, error: str
) -> None:
    version = tmp_path / "als" / saved_version.name
    copytree(saved_version, version)
    metadata = spark.read.parquet(str(saved_version / "metadata"))
    if damage == "contract":
        metadata = metadata.withColumn("contract_version", F.lit("unknown-v99"))
    elif damage == "uid":
        metadata = metadata.withColumn("model_uid", F.lit("different-model"))
    elif damage == "null":
        metadata = metadata.withColumn("model_uid", F.lit(None).cast("string"))
    elif damage == "rows":
        metadata = metadata.unionByName(metadata)
    else:
        metadata = metadata.withColumn("rank", F.col("rank").cast("string"))
    metadata.write.mode("overwrite").parquet(str(version / "metadata"))

    with pytest.raises(ValueError, match=error):
        load_als_artifacts(spark, str(tmp_path), saved_version.name)
