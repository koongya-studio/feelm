"""Versioned persistence and loading for Spark ALS artifacts."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit
from uuid import uuid4

from py4j.protocol import Py4JJavaError
from pyspark.ml.recommendation import ALSModel
from pyspark.sql import DataFrame, Row, SparkSession
from pyspark.sql import functions as F
from pyspark.sql.types import ArrayType, FloatType, IntegerType

from batch.train import artifact_metadata
from batch.train.artifact_metadata import (
    FINAL_EVALUATION as FINAL_EVALUATION,
)
from batch.train.artifact_metadata import (
    MODEL_SELECTION as MODEL_SELECTION,
)
from batch.train.artifact_metadata import (
    SERVING as SERVING,
)
from batch.train.artifact_metadata import (
    AlsArtifactMetadata as AlsArtifactMetadata,
)
from batch.train.training import ITEM_COLUMN, USER_COLUMN

SUPPORTED_STORAGE_SCHEMES = {"", "file", "hdfs"}


@dataclass(frozen=True)
class AlsArtifacts:
    """A validated, loaded ALS artifact version."""

    version_uri: str
    model: ALSModel
    user_factors: DataFrame
    item_factors: DataFrame
    metadata: Row


def artifact_version_uri(output_uri: str, model_version: str) -> str:
    """Return the contract path for one model version."""
    _validate_output_uri(output_uri)
    artifact_metadata.validate_model_version(model_version)
    return _child_uri(_child_uri(output_uri, "als"), model_version)


def save_als_artifacts(
    spark: SparkSession,
    model: ALSModel,
    output_uri: str,
    metadata: AlsArtifactMetadata,
) -> str:
    """Persist and atomically publish one immutable ALS artifact version."""
    final_uri = artifact_version_uri(output_uri, metadata.model_version)
    if _path_exists(spark, final_uri):
        raise FileExistsError(f"ALS artifact version already exists: {final_uri}")

    artifact_metadata.validate_metadata_input(metadata)
    if model.rank != metadata.config.rank:
        raise ValueError("model rank does not match metadata config rank")
    _validate_model_columns(model)

    user_factors = model.userFactors.select(F.col("id").alias(USER_COLUMN), "features")
    item_factors = model.itemFactors.select(F.col("id").alias(ITEM_COLUMN), "features")
    factor_counts = {}
    for name, frame, id_column in (
        ("user", user_factors, USER_COLUMN),
        ("item", item_factors, ITEM_COLUMN),
    ):
        count = _validate_factor_frame(frame, id_column, model.rank)
        if count > metadata.training_ratings_count:
            raise ValueError(f"{name} factor count exceeds training ratings count")
        factor_counts[f"{name}_factor_count"] = count

    metadata_row = artifact_metadata.build_metadata_row(
        metadata,
        model_uid=model.uid,
        spark_version=spark.version,
        factor_dimension=model.rank,
        **factor_counts,
    )
    temporary_uri = _child_uri(
        _child_uri(output_uri, "als"), f".{metadata.model_version}.tmp-{uuid4().hex}"
    )
    try:
        model.write().save(_child_uri(temporary_uri, "model"))
        user_factors.write.mode("errorifexists").parquet(_child_uri(temporary_uri, "user-factors"))
        item_factors.write.mode("errorifexists").parquet(_child_uri(temporary_uri, "item-factors"))
        _write_metadata(spark, temporary_uri, metadata_row)
        _load_als_artifacts_at_uri(spark, temporary_uri, metadata.model_version)
        _publish_directory(spark, temporary_uri, final_uri)
    except Exception:
        _delete_path(spark, temporary_uri)
        raise
    return final_uri


def load_als_artifacts(
    spark: SparkSession,
    output_uri: str,
    model_version: str,
) -> AlsArtifacts:
    """Load and validate one published ALS artifact version."""
    version_uri = artifact_version_uri(output_uri, model_version)
    if not _path_exists(spark, version_uri):
        raise FileNotFoundError(f"ALS artifact version does not exist: {version_uri}")
    return _load_als_artifacts_at_uri(spark, version_uri, model_version)


def _load_als_artifacts_at_uri(
    spark: SparkSession,
    version_uri: str,
    expected_model_version: str,
) -> AlsArtifacts:
    metadata_frame = spark.read.parquet(_child_uri(version_uri, "metadata"))
    metadata = artifact_metadata.parse_metadata(metadata_frame)
    artifact_metadata.validate_loaded_metadata(metadata, spark.version, expected_model_version)

    model = ALSModel.load(_child_uri(version_uri, "model"))
    _validate_model_columns(model)
    user_factors = spark.read.parquet(_child_uri(version_uri, "user-factors"))
    item_factors = spark.read.parquet(_child_uri(version_uri, "item-factors"))

    rank = metadata["rank"]
    if model.rank != rank or model.rank != metadata["factor_dimension"]:
        raise ValueError("loaded model rank does not match metadata")
    if model.uid != metadata["model_uid"]:
        raise ValueError("loaded model UID does not match metadata")

    for name, frame, model_frame, id_column in (
        ("user", user_factors, model.userFactors, USER_COLUMN),
        ("item", item_factors, model.itemFactors, ITEM_COLUMN),
    ):
        if _validate_factor_frame(frame, id_column, rank) != metadata[f"{name}_factor_count"]:
            raise ValueError(f"stored {name} factor count does not match metadata")
        expected = model_frame.select(F.col("id").alias(id_column), "features")
        if not _same_factor_rows(frame, expected, id_column):
            raise ValueError(f"stored {name} factors do not match the ALSModel")

    return AlsArtifacts(
        version_uri=version_uri,
        model=model,
        user_factors=user_factors,
        item_factors=item_factors,
        metadata=metadata,
    )


def _validate_model_columns(model: ALSModel) -> None:
    expected_columns = {
        "user": (model.getUserCol(), USER_COLUMN),
        "item": (model.getItemCol(), ITEM_COLUMN),
    }
    for name, (actual, expected) in expected_columns.items():
        if actual != expected:
            raise ValueError(
                f"model {name} column does not match the artifact contract: "
                f"expected {expected}, got {actual}"
            )


def _write_metadata(
    spark: SparkSession,
    version_uri: str,
    metadata_row: dict[str, object],
) -> None:
    spark.createDataFrame([metadata_row], artifact_metadata.METADATA_SCHEMA).write.mode(
        "errorifexists"
    ).parquet(_child_uri(version_uri, "metadata"))


def _validate_factor_frame(frame: DataFrame, id_column: str, rank: int) -> int:
    expected_fields = {id_column, "features"}
    missing_fields = sorted(expected_fields - set(frame.columns))
    if missing_fields:
        raise ValueError(f"factor data is missing required columns: {missing_fields}")
    if frame.schema[id_column].dataType != IntegerType():
        raise ValueError(f"factor column {id_column} has an incompatible type")
    feature_type = frame.schema["features"].dataType
    if not isinstance(feature_type, ArrayType) or feature_type.elementType != FloatType():
        raise ValueError("factor column features has an incompatible type")

    invalid_element = F.exists(
        F.col("features"),
        lambda value: value.isNull() | F.isnan(value) | (F.abs(value) == float("inf")),
    )
    summary = frame.agg(
        F.count(F.lit(1)).alias("row_count"),
        F.sum(
            F.when(
                F.col(id_column).isNull()
                | (F.col(id_column) <= 0)
                | F.col("features").isNull()
                | (F.size("features") != rank)
                | invalid_element,
                1,
            ).otherwise(0)
        ).alias("invalid_count"),
    ).first()
    if summary is None or summary["row_count"] == 0:
        raise ValueError("factor data is empty")
    if summary["invalid_count"]:
        raise ValueError("factor data contains invalid rows")
    duplicate_exists = frame.groupBy(id_column).count().filter(F.col("count") > 1).limit(1).count()
    if duplicate_exists:
        raise ValueError(f"factor data contains duplicate {id_column} values")
    return summary["row_count"]


def _same_factor_rows(left: DataFrame, right: DataFrame, id_column: str) -> bool:
    columns = [id_column, "features"]
    return (
        left.select(*columns).exceptAll(right.select(*columns)).limit(1).count() == 0
        and right.select(*columns).exceptAll(left.select(*columns)).limit(1).count() == 0
    )


def _publish_directory(spark: SparkSession, source_uri: str, destination_uri: str) -> None:
    source_path, source_file_system, configuration = _hadoop_path(spark, source_uri)
    destination_path, destination_file_system, _ = _hadoop_path(spark, destination_uri)
    if source_file_system.getUri().toString() != destination_file_system.getUri().toString():
        raise ValueError("temporary and final artifact paths must use the same filesystem")
    scheme = source_file_system.getUri().getScheme()
    if scheme == "file":
        _publish_local_directory(
            Path(source_file_system.makeQualified(source_path).toUri().getPath()),
            Path(destination_file_system.makeQualified(destination_path).toUri().getPath()),
        )
        return
    if scheme != "hdfs":
        raise ValueError("ALS artifacts support only local and hdfs filesystems")

    file_context = spark._jvm.org.apache.hadoop.fs.FileContext.getFileContext(  # noqa: SLF001
        source_file_system.getUri(),
        configuration,
    )
    rename_options = spark.sparkContext._gateway.new_array(  # noqa: SLF001
        spark._jvm.org.apache.hadoop.fs.Options.Rename,  # noqa: SLF001
        1,
    )
    rename_options[0] = spark._jvm.org.apache.hadoop.fs.Options.Rename.NONE  # noqa: SLF001
    try:
        file_context.rename(source_path, destination_path, rename_options)
    except Py4JJavaError as error:
        if _path_exists(spark, destination_uri):
            raise FileExistsError(
                f"ALS artifact version already exists: {destination_uri}"
            ) from error
        raise OSError(f"failed to publish ALS artifact version: {destination_uri}") from error


def _publish_local_directory(source: Path, destination: Path) -> None:
    # Linux local storage: lock the stable parent directory across threads/processes.
    # Hadoop's local rename can otherwise nest the losing source inside the winner.
    import fcntl
    import os

    parent_fd = os.open(destination.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        fcntl.flock(parent_fd, fcntl.LOCK_EX)
        if os.path.lexists(destination):
            raise FileExistsError(f"ALS artifact version already exists: {destination}")
        os.rename(source, destination)
    finally:
        os.close(parent_fd)


def _path_exists(spark: SparkSession, uri: str) -> bool:
    path, file_system, _ = _hadoop_path(spark, uri)
    return file_system.exists(path)


def _delete_path(spark: SparkSession, uri: str) -> None:
    path, file_system, _ = _hadoop_path(spark, uri)
    if file_system.exists(path):
        file_system.delete(path, True)


def _hadoop_path(spark: SparkSession, uri: str) -> tuple[object, object, object]:
    configuration = spark.sparkContext._jsc.hadoopConfiguration()  # noqa: SLF001
    path = spark._jvm.org.apache.hadoop.fs.Path(uri)  # noqa: SLF001
    return path, path.getFileSystem(configuration), configuration


def _child_uri(base_uri: str, child: str) -> str:
    return f"{base_uri.rstrip('/')}/{child}"


def _validate_output_uri(output_uri: str) -> None:
    if not output_uri:
        raise ValueError("output URI is required")
    parsed = urlsplit(output_uri)
    if parsed.scheme not in SUPPORTED_STORAGE_SCHEMES:
        raise ValueError("ALS artifacts support only local and hdfs output URIs")
    if parsed.query or parsed.fragment:
        raise ValueError("output URI must not contain a query or fragment")
