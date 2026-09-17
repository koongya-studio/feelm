"""Generate validated multilingual-e5-base vectors from embedding source rows."""

from __future__ import annotations

import hashlib
import math
import re
from collections.abc import Iterable, Iterator, Sequence
from dataclasses import dataclass
from itertools import islice
from typing import Protocol
from uuid import uuid4

from pyspark.sql import DataFrame, Row, SparkSession
from pyspark.sql import functions as F
from pyspark.sql.types import ArrayType, FloatType, LongType, StringType, StructField, StructType
from pyspark.storagelevel import StorageLevel

from batch.embedding.manifest import file_checksums, manifest_json, verify_manifest
from batch.train.artifacts import _delete_path, _path_exists, _publish_directory

MODEL_ID = "intfloat/multilingual-e5-base"
DIMENSION = 768
QUERY_PREFIX = "query: "
TEXT_TEMPLATE_VERSION = "tmdb-e5-v1"
DEFAULT_MAX_LENGTH = 512
DEVICE_CHOICES = frozenset({"auto", "cpu", "cuda"})
TMDB_ID_MAX = 2_147_483_647
REVISION_PATTERN = r"sha256:[0-9a-f]{64}"
SHA256_PATTERN = r"[0-9a-f]{64}"

EMBEDDING_SCHEMA = StructType(
    [
        StructField("movie_id", LongType(), False),
        StructField("tmdb_id", LongType(), False),
        StructField("mapping_revision", StringType(), False),
        StructField("input_text_sha256", StringType(), False),
        StructField("embedding", ArrayType(FloatType(), False), False),
    ]
)
METADATA_SCHEMA = StructType(
    [
        StructField("embedding_version", StringType(), False),
        StructField("model_id", StringType(), False),
        StructField("model_revision", StringType(), False),
        StructField("dimension", LongType(), False),
        StructField("query_prefix", StringType(), False),
        StructField("max_length", LongType(), False),
        StructField("mapping_revision", StringType(), False),
        StructField("row_count", LongType(), False),
        StructField("text_template_version", StringType(), False),
        StructField("input_snapshot_sha256", StringType(), False),
    ]
)


class Encoder(Protocol):
    """Small boundary that permits model-free unit tests."""

    def encode(self, texts: Sequence[str]) -> list[list[float]]: ...


@dataclass(frozen=True)
class E5Config:
    """Immutable model parameters recorded with every embedding artifact."""

    revision: str
    batch_size: int = 32
    max_length: int = DEFAULT_MAX_LENGTH
    device: str = "auto"
    partitions: int = 4
    model_id: str = MODEL_ID

    def __post_init__(self) -> None:
        if not re.fullmatch(r"[0-9a-f]{40}", self.revision):
            raise ValueError("E5 model revision must be a 40-character commit SHA")
        if self.model_id != MODEL_ID:
            raise ValueError(f"E5 model must be {MODEL_ID}")
        if self.batch_size <= 0:
            raise ValueError("E5 batch_size must be positive")
        if not 1 <= self.max_length <= DEFAULT_MAX_LENGTH:
            raise ValueError(f"E5 max_length must be in 1..{DEFAULT_MAX_LENGTH}")
        if self.device not in DEVICE_CHOICES:
            raise ValueError(f"E5 device must be one of {sorted(DEVICE_CHOICES)}")
        if self.partitions <= 0:
            raise ValueError("E5 partitions must be positive")


class TransformersE5Encoder:
    """CPU/GPU-independent Hugging Face implementation loaded lazily per worker."""

    def __init__(self, config: E5Config) -> None:
        self._config = config
        try:
            import torch
            from transformers import AutoModel, AutoTokenizer
        except ImportError as error:
            raise RuntimeError(
                "E5 embedding runtime requires torch and transformers in the Spark image"
            ) from error
        self._torch = torch
        self._device = _resolve_device(torch, config.device)
        self._tokenizer = AutoTokenizer.from_pretrained(config.model_id, revision=config.revision)
        self._model = AutoModel.from_pretrained(
            config.model_id, revision=config.revision
        ).to(self._device)
        self._model.eval()

    def encode(self, texts: Sequence[str]) -> list[list[float]]:
        if not texts:
            return []
        torch = self._torch
        encoded = self._tokenizer(
            [QUERY_PREFIX + text for text in texts],
            max_length=self._config.max_length,
            padding=True,
            truncation=True,
            return_tensors="pt",
        )
        encoded = {name: value.to(self._device) for name, value in encoded.items()}
        with torch.no_grad():
            hidden = self._model(**encoded).last_hidden_state
            mask = encoded["attention_mask"].unsqueeze(-1).bool()
            pooled = hidden.masked_fill(~mask, 0.0).sum(dim=1) / mask.sum(dim=1)
            normalized = torch.nn.functional.normalize(pooled, p=2, dim=1)
        return normalized.cpu().tolist()


def generate_e5_embeddings(source: DataFrame, config: E5Config) -> DataFrame:
    """Encode source rows in Spark Python workers and validate every returned vector.

    The source frame is intentionally narrowed before RDD conversion.  Each
    partition holds only one batch in memory and creates one transformer model
    instance, so the driver never gathers the movie catalog or vectors.
    """
    required = {"movie_id", "tmdb_id", "mapping_revision", "input_text_sha256", "source_text"}
    missing = sorted(required - set(source.columns))
    if missing:
        raise ValueError(f"embedding source is missing required columns: {missing}")
    selected = source.select(*sorted(required))
    _validate_source_rows(selected)
    spark = source.sparkSession
    encoded = selected.repartition(config.partitions).rdd.mapPartitions(
        lambda rows: _encode_partition(rows, config)
    )
    frame = spark.createDataFrame(encoded, EMBEDDING_SCHEMA).persist(StorageLevel.DISK_ONLY)
    try:
        _validate_embedding_rows(frame)
    except Exception:
        frame.unpersist(blocking=True)
        raise
    return frame


def embedding_version_uri(output_uri: str, embedding_version: str) -> str:
    """Return the immutable location for one dense embedding version."""
    if not output_uri or output_uri.rstrip("/").lower().endswith("/latest"):
        raise ValueError("embedding output URI must be immutable")
    if not re.fullmatch(
        r"[A-Za-z0-9][A-Za-z0-9._-]*", embedding_version or ""
    ) or embedding_version in {".", ".."}:
        raise ValueError("embedding version must be a non-empty path segment")
    return f"{output_uri.rstrip('/')}/movie-embeddings/{embedding_version}"


def expected_model_version(model_revision: str) -> str:
    if not re.fullmatch(r"[0-9a-f]{40}", model_revision or ""):
        raise ValueError("E5 model revision must be a 40-character commit SHA")
    return f"e5-base-{model_revision[:12]}"


def save_e5_embeddings(
    spark: SparkSession,
    embeddings: DataFrame,
    output_uri: str,
    embedding_version: str,
    config: E5Config,
) -> str:
    """Stage, reload-validate, and atomically publish one immutable version."""
    final_uri = embedding_version_uri(output_uri, embedding_version)
    if _path_exists(spark, final_uri):
        raise FileExistsError(f"embedding artifact version already exists: {final_uri}")
    _validate_embedding_rows(embeddings)
    mapping_revisions = [
        row["mapping_revision"]
        for row in embeddings.select("mapping_revision").distinct().collect()
    ]
    if len(mapping_revisions) != 1:
        raise ValueError("embedding artifact must have one mapping revision")
    row_count = embeddings.count()
    input_digest = hashlib.sha256()
    for item in embeddings.select("movie_id", "tmdb_id", "input_text_sha256").orderBy(
        "movie_id"
    ).toLocalIterator():
        input_digest.update(
            f"{item['movie_id']}:{item['tmdb_id']}:{item['input_text_sha256']}\n".encode()
        )
    parent = f"{output_uri.rstrip('/')}/movie-embeddings"
    temporary_uri = f"{parent}/.{embedding_version}.tmp-{uuid4().hex}"
    metadata = Row(
        embedding_version=embedding_version,
        model_id=config.model_id,
        model_revision=config.revision,
        dimension=DIMENSION,
        query_prefix=QUERY_PREFIX,
        max_length=config.max_length,
        mapping_revision=mapping_revisions[0],
        row_count=row_count,
        text_template_version=TEXT_TEMPLATE_VERSION,
        input_snapshot_sha256=input_digest.hexdigest(),
    )
    try:
        embeddings.select(*EMBEDDING_SCHEMA.fieldNames()).write.mode("errorifexists").parquet(
            f"{temporary_uri}/embeddings"
        )
        spark.createDataFrame([metadata], METADATA_SCHEMA).write.mode("errorifexists").parquet(
            f"{temporary_uri}/metadata"
        )
        checksum_manifest = manifest_json(metadata, file_checksums(spark, temporary_uri))
        spark.createDataFrame([(checksum_manifest,)], "json string").write.mode(
            "errorifexists"
        ).parquet(f"{temporary_uri}/manifest")
        _load_e5_embeddings_at_uri(spark, temporary_uri, embedding_version)
        _publish_directory(spark, temporary_uri, final_uri)
    except Exception:
        _delete_path(spark, temporary_uri)
        raise
    return final_uri


def load_e5_embeddings(
    spark: SparkSession, output_uri: str, embedding_version: str
) -> tuple[DataFrame, Row]:
    """Load and validate a completed E5 artifact version."""
    version_uri = embedding_version_uri(output_uri, embedding_version)
    if not _path_exists(spark, version_uri):
        raise FileNotFoundError(f"embedding artifact version does not exist: {version_uri}")
    return _load_e5_embeddings_at_uri(spark, version_uri, embedding_version)


def seal_legacy_e5_embeddings(
    spark: SparkSession, output_uri: str, legacy_version: str, new_version: str
) -> str:
    """Copy a pre-manifest artifact into a newly validated immutable version."""
    legacy_uri = embedding_version_uri(output_uri, legacy_version)
    metadata_frame = spark.read.parquet(f"{legacy_uri}/metadata")
    if metadata_frame.count() != 1:
        raise ValueError("legacy embedding metadata must contain exactly one row")
    metadata = metadata_frame.first()
    if metadata["embedding_version"] != legacy_version:
        raise ValueError("legacy embedding version mismatch")
    if metadata["model_id"] != MODEL_ID or metadata["dimension"] != DIMENSION:
        raise ValueError("legacy embedding model contract mismatch")
    if metadata["query_prefix"] != QUERY_PREFIX:
        raise ValueError("legacy embedding text prefix mismatch")
    config = E5Config(revision=metadata["model_revision"], max_length=metadata["max_length"])
    embeddings = spark.read.parquet(f"{legacy_uri}/embeddings")
    _validate_embedding_rows(embeddings)
    if embeddings.count() != metadata["row_count"]:
        raise ValueError("legacy embedding row count mismatch")
    revisions = [
        row["mapping_revision"]
        for row in embeddings.select("mapping_revision").distinct().collect()
    ]
    if revisions != [metadata["mapping_revision"]]:
        raise ValueError("legacy embedding mapping revision mismatch")
    if _path_exists(spark, f"{legacy_uri}/manifest"):
        raise ValueError("source is already a manifest-backed embedding artifact")
    return save_e5_embeddings(spark, embeddings, output_uri, new_version, config)


def _load_e5_embeddings_at_uri(
    spark: SparkSession, version_uri: str, expected_version: str
) -> tuple[DataFrame, Row]:
    metadata = spark.read.parquet(f"{version_uri}/metadata")
    if metadata.count() != 1:
        raise ValueError("embedding metadata must contain exactly one row")
    row = metadata.first()
    if row is None or row["embedding_version"] != expected_version:
        raise ValueError("embedding metadata version mismatch")
    if row["model_id"] != MODEL_ID or row["dimension"] != DIMENSION:
        raise ValueError("embedding metadata model contract mismatch")
    if row["query_prefix"] != QUERY_PREFIX or row["text_template_version"] != TEXT_TEMPLATE_VERSION:
        raise ValueError("embedding metadata text recipe mismatch")
    if not isinstance(row["mapping_revision"], str) or not re.fullmatch(
        REVISION_PATTERN, row["mapping_revision"]
    ):
        raise ValueError("embedding metadata mapping revision is invalid")
    expected_model_version(row["model_revision"])
    verify_manifest(spark, version_uri, row)
    embeddings = spark.read.parquet(f"{version_uri}/embeddings")
    _validate_embedding_rows(embeddings)
    if embeddings.count() != row["row_count"]:
        raise ValueError("embedding metadata row count mismatch")
    revisions = [
        value["mapping_revision"]
        for value in embeddings.select("mapping_revision").distinct().collect()
    ]
    if revisions != [row["mapping_revision"]]:
        raise ValueError("embedding metadata mapping revision mismatch")
    input_digest = hashlib.sha256()
    for item in embeddings.select("movie_id", "tmdb_id", "input_text_sha256").orderBy(
        "movie_id"
    ).toLocalIterator():
        input_digest.update(
            f"{item['movie_id']}:{item['tmdb_id']}:{item['input_text_sha256']}\n".encode()
        )
    if input_digest.hexdigest() != row["input_snapshot_sha256"]:
        raise ValueError("embedding input snapshot checksum mismatch")
    return embeddings, row


def _encode_partition(rows: Iterable[object], config: E5Config) -> Iterator[tuple[object, ...]]:
    encoder = TransformersE5Encoder(config)
    while batch := list(islice(rows, config.batch_size)):
        texts = [row["source_text"] for row in batch]
        vectors = encoder.encode(texts)
        if len(vectors) != len(batch):
            raise ValueError("E5 encoder returned a different number of vectors than texts")
        for row, vector in zip(batch, vectors, strict=True):
            _validate_vector(vector)
            yield (
                int(row["movie_id"]),
                int(row["tmdb_id"]),
                str(row["mapping_revision"]),
                str(row["input_text_sha256"]),
                [float(value) for value in vector],
            )


def _resolve_device(torch, requested_device: str) -> str:
    if requested_device == "auto":
        return "cuda" if torch.cuda.is_available() else "cpu"
    if requested_device == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("E5 CUDA device was requested but torch.cuda.is_available() is false")
    return requested_device


def _validate_source_rows(source: DataFrame) -> None:
    invalid = source.filter(
        source.movie_id.isNull()
        | (source.movie_id <= 0)
        | source.tmdb_id.isNull()
        | (source.tmdb_id <= 0)
        | source.source_text.isNull()
        | (source.source_text == "")
    )
    if invalid.limit(1).count():
        raise ValueError("embedding source contains invalid rows")
    if source.groupBy("movie_id").count().filter("count != 1").limit(1).count():
        raise ValueError("embedding source movie_id must be unique")


def _validate_embedding_rows(embeddings: DataFrame) -> None:
    invalid = embeddings.filter(
        embeddings.movie_id.isNull()
        | (embeddings.movie_id <= 0)
        | embeddings.tmdb_id.isNull()
        | (embeddings.tmdb_id <= 0)
        | (embeddings.tmdb_id > TMDB_ID_MAX)
        | embeddings.mapping_revision.isNull()
        | ~embeddings.mapping_revision.rlike(f"^{REVISION_PATTERN}$")
        | embeddings.input_text_sha256.isNull()
        | ~embeddings.input_text_sha256.rlike(f"^{SHA256_PATTERN}$")
        | embeddings.embedding.isNull()
        | (F.size(embeddings.embedding) != DIMENSION)
        | F.exists(
            embeddings.embedding,
            lambda value: value.isNull() | F.isnan(value) | (F.abs(value) == float("inf")),
        )
    )
    if invalid.limit(1).count():
        raise ValueError("E5 embedding output contains invalid rows")
    if embeddings.groupBy("movie_id").count().filter("count != 1").limit(1).count():
        raise ValueError("E5 embedding output movie_id must be unique")
    squared_norm = F.aggregate(
        F.col("embedding"),
        F.lit(0.0),
        lambda total, value: total + value.cast("double") * value.cast("double"),
    )
    if embeddings.filter(F.abs(squared_norm - 1.0) > 2e-5).limit(1).count():
        raise ValueError("E5 embedding output contains non-normalized vectors")


def _validate_vector(vector: Sequence[float]) -> None:
    if len(vector) != DIMENSION:
        raise ValueError(f"E5 embedding dimension must be {DIMENSION}")
    if any(not math.isfinite(float(value)) for value in vector):
        raise ValueError("E5 embedding values must be finite")
    norm = math.sqrt(sum(float(value) * float(value) for value in vector))
    if not math.isfinite(norm) or abs(norm - 1.0) > 1e-5:
        raise ValueError("E5 embedding must be L2-normalized")
