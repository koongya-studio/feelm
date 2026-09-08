"""Metadata schema, conversion and validation for ALS artifact versions."""

from __future__ import annotations

import re
from dataclasses import asdict, dataclass, fields
from datetime import datetime
from decimal import Decimal
from math import isfinite

from pyspark.sql import DataFrame, Row
from pyspark.sql import functions as F
from pyspark.sql.types import (
    DoubleType,
    IntegerType,
    LongType,
    StringType,
    StructField,
    StructType,
)

from batch.train.settings import AlsConfig

CONTRACT_VERSION = "als-artifacts-v1"
MODEL_SELECTION = "MODEL_SELECTION"
FINAL_EVALUATION = "FINAL_EVALUATION"
SERVING = "SERVING"
ARTIFACT_STAGES = {MODEL_SELECTION, FINAL_EVALUATION, SERVING}
SPLIT_METHOD = "per-user-chronological-v1"

MODEL_VERSION_PATTERN = re.compile(r"^[a-z0-9][a-z0-9._-]{0,127}$")
UTC_TIMESTAMP_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")

METADATA_SCHEMA = StructType(
    [
        StructField("contract_version", StringType(), False),
        StructField("model_version", StringType(), False),
        StructField("model_uid", StringType(), False),
        StructField("artifact_stage", StringType(), False),
        StructField("input_snapshot", StringType(), False),
        StructField("input_uri", StringType(), False),
        StructField("trained_at_utc", StringType(), False),
        StructField("spark_version", StringType(), False),
        StructField("rank", IntegerType(), False),
        StructField("reg_param", DoubleType(), False),
        StructField("max_iter", IntegerType(), False),
        StructField("als_seed", LongType(), False),
        StructField("factor_dimension", IntegerType(), False),
        StructField("split_method", StringType(), True),
        StructField("split_seed", LongType(), True),
        StructField("train_ratio", DoubleType(), True),
        StructField("validation_ratio", DoubleType(), True),
        StructField("min_eval_ratings", IntegerType(), True),
        StructField("source_ratings_count", LongType(), False),
        StructField("training_ratings_count", LongType(), False),
        StructField("user_factor_count", LongType(), False),
        StructField("item_factor_count", LongType(), False),
        StructField("implementation_revision", StringType(), True),
    ]
)


@dataclass(frozen=True)
class AlsArtifactMetadata:
    """Caller-supplied metadata whose derived fields are filled during save."""

    model_version: str
    artifact_stage: str
    input_snapshot: str
    input_uri: str
    trained_at_utc: str
    config: AlsConfig
    als_seed: int
    source_ratings_count: int
    training_ratings_count: int
    split_method: str | None = None
    split_seed: int | None = None
    train_ratio: float | None = None
    validation_ratio: float | None = None
    min_eval_ratings: int | None = None
    implementation_revision: str | None = None


def build_metadata_row(
    metadata: AlsArtifactMetadata,
    *,
    model_uid: str,
    spark_version: str,
    factor_dimension: int,
    user_factor_count: int,
    item_factor_count: int,
) -> dict[str, object]:
    """Flatten caller metadata and add fields derived from the saved model."""
    row = asdict(metadata)
    row.update(row.pop("config"))
    row.update(
        contract_version=CONTRACT_VERSION,
        model_uid=model_uid,
        spark_version=spark_version,
        factor_dimension=factor_dimension,
        user_factor_count=user_factor_count,
        item_factor_count=item_factor_count,
    )
    return row


def validate_metadata_input(metadata: AlsArtifactMetadata) -> None:
    validate_model_version(metadata.model_version)
    if metadata.artifact_stage not in ARTIFACT_STAGES:
        raise ValueError(f"unsupported artifact stage: {metadata.artifact_stage}")
    if not metadata.input_snapshot or metadata.input_snapshot.lower() == "latest":
        raise ValueError("input snapshot must be an immutable identifier")
    if not metadata.input_uri:
        raise ValueError("input URI is required")
    if not UTC_TIMESTAMP_PATTERN.fullmatch(metadata.trained_at_utc):
        raise ValueError("trained_at_utc must use YYYY-MM-DDTHH:mm:ssZ")
    try:
        datetime.strptime(metadata.trained_at_utc, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as error:
        raise ValueError("trained_at_utc is not a valid UTC timestamp") from error
    if (
        metadata.config.rank <= 0
        or not isfinite(metadata.config.reg_param)
        or metadata.config.reg_param < 0
        or metadata.config.max_iter <= 0
    ):
        raise ValueError("ALS config contains invalid values")
    if not 0 < metadata.training_ratings_count <= metadata.source_ratings_count:
        raise ValueError("rating counts do not satisfy the artifact contract")
    if metadata.implementation_revision is not None and not metadata.implementation_revision:
        raise ValueError("implementation revision must not be empty")

    split_values = (
        metadata.split_method,
        metadata.split_seed,
        metadata.train_ratio,
        metadata.validation_ratio,
        metadata.min_eval_ratings,
    )
    if metadata.artifact_stage == SERVING:
        if any(value is not None for value in split_values):
            raise ValueError("SERVING artifacts must not contain split metadata")
        if metadata.training_ratings_count != metadata.source_ratings_count:
            raise ValueError("SERVING artifacts must train on the full input snapshot")
        return

    if any(value is None for value in split_values):
        raise ValueError("evaluation artifacts require all split metadata")
    if metadata.split_method != SPLIT_METHOD:
        raise ValueError(f"unsupported split method: {metadata.split_method}")
    if metadata.train_ratio is None or not 0 < metadata.train_ratio < 1:
        raise ValueError("train ratio must be between 0 and 1")
    if metadata.validation_ratio is None or not 0 < metadata.validation_ratio < 1:
        raise ValueError("validation ratio must be between 0 and 1")
    if Decimal(str(metadata.train_ratio)) + Decimal(str(metadata.validation_ratio)) >= Decimal("1"):
        raise ValueError("train and validation ratios must leave a positive test ratio")
    if metadata.min_eval_ratings is None or metadata.min_eval_ratings < 3:
        raise ValueError("minimum evaluation ratings must be at least 3")


def parse_metadata(frame: DataFrame) -> Row:
    expected_fields = {field.name: field.dataType for field in METADATA_SCHEMA}
    required_columns = set(expected_fields) - {"implementation_revision"}
    missing_fields = sorted(required_columns - set(frame.columns))
    if missing_fields:
        raise ValueError(f"metadata is missing required columns: {missing_fields}")
    for name, expected_type in expected_fields.items():
        if name in frame.columns and frame.schema[name].dataType != expected_type:
            raise ValueError(f"metadata column {name} has an incompatible type")

    if "implementation_revision" not in frame.columns:
        frame = frame.withColumn("implementation_revision", F.lit(None).cast(StringType()))

    rows = frame.select(*(field.name for field in METADATA_SCHEMA)).take(2)
    if len(rows) != 1:
        raise ValueError("metadata must contain exactly one row")
    metadata = rows[0]
    required_values = {field.name for field in METADATA_SCHEMA if not field.nullable}
    for name in required_values:
        if metadata[name] is None:
            raise ValueError(f"metadata column {name} must not be null")
    return metadata


def validate_loaded_metadata(
    metadata: Row,
    spark_version: str,
    expected_model_version: str,
) -> None:
    if metadata["contract_version"] != CONTRACT_VERSION:
        raise ValueError(f"unsupported artifact contract: {metadata['contract_version']}")
    if metadata["model_version"] != expected_model_version:
        raise ValueError("metadata model version does not match the requested version")
    if metadata["spark_version"] != spark_version:
        raise ValueError("artifact Spark version does not match the current Spark version")

    input_values = {
        field.name: metadata[field.name]
        for field in fields(AlsArtifactMetadata)
        if field.name != "config"
    }
    config = AlsConfig(**{field.name: metadata[field.name] for field in fields(AlsConfig)})
    metadata_input = AlsArtifactMetadata(config=config, **input_values)
    validate_metadata_input(metadata_input)
    if metadata["factor_dimension"] != metadata["rank"]:
        raise ValueError("factor dimension does not match rank")
    for name in ("user", "item"):
        if not 0 < metadata[f"{name}_factor_count"] <= metadata["training_ratings_count"]:
            raise ValueError(f"{name} factor count does not satisfy the artifact contract")


def validate_model_version(model_version: str) -> None:
    if not MODEL_VERSION_PATTERN.fullmatch(model_version):
        raise ValueError("model version has an invalid format")
