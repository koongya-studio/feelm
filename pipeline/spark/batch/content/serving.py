"""Build, publish and validate content-artifacts-v2 serving bundles.

v2 packages the S15P21E106-315 structured content representation for every
service movie so a serving consumer can read it from local storage or HDFS.
Token weights are computed in Spark SQL with the same formula as
``batch.content.features.build_content_vector``; vocabulary order and the
rating prior call the 315 functions directly.
"""

from __future__ import annotations

import hashlib
import math
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from urllib.parse import urlsplit
from uuid import uuid4

from pyspark.sql import DataFrame, Row, SparkSession
from pyspark.sql import functions as F
from pyspark.sql.types import (
    ArrayType,
    DoubleType,
    IntegerType,
    LongType,
    StringType,
    StructField,
    StructType,
)

from batch.content.features import RATING_VALUES, build_vocabulary, user_equal_prior
from batch.train.artifacts import _child_uri, _delete_path, _path_exists, _publish_directory

CONTRACT_VERSION = "content-artifacts-v2"
FEATURE_RULE = "structured-4group-v1"
ELIGIBILITY_RULE = "language-year-genre-v1"
GROUP_WEIGHT = 0.25
PRIOR_TOLERANCE = 1e-9
VERSION_PATTERN = re.compile(r"^[a-z0-9][a-z0-9._-]{0,127}$")
REVISION_PATTERN = re.compile(r"^sha256:[0-9a-f]{64}$")
UTC_TIMESTAMP_PATTERN = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")
SUPPORTED_SCHEMES = {"", "file", "hdfs"}
MOVABLE_ALIASES = frozenset({"latest", "current", "stable", "production", "prod"})
MIDRANK_TOLERANCE = 1e-12

FEATURES_SCHEMA = StructType(
    [
        StructField("service_movie_id", LongType(), False),
        StructField("tmdb_id", IntegerType(), False),
        StructField("indices", ArrayType(IntegerType(), False), False),
        StructField("values", ArrayType(DoubleType(), False), False),
    ]
)
VOCABULARY_SCHEMA = StructType(
    [
        StructField("feature_index", IntegerType(), False),
        StructField("token", StringType(), False),
    ]
)
METADATA_SCHEMA = StructType(
    [
        StructField("contract_version", StringType(), False),
        StructField("content_version", StringType(), False),
        StructField("input_snapshot", StringType(), False),
        StructField("input_uri", StringType(), False),
        StructField("mapping_revision", StringType(), False),
        StructField("movie_metadata_revision", StringType(), False),
        StructField("tmdb_snapshot_revision", StringType(), False),
        StructField("feature_rule", StringType(), False),
        StructField("eligibility_rule", StringType(), False),
        StructField("built_at_utc", StringType(), False),
        StructField("spark_version", StringType(), False),
        StructField("service_movie_count", LongType(), False),
        StructField("supported_movie_count", LongType(), False),
        StructField("vocabulary_size", IntegerType(), False),
        StructField("prior_probability", ArrayType(DoubleType(), False), False),
        StructField("prior_midrank", ArrayType(DoubleType(), False), False),
        StructField("prior_user_count", LongType(), False),
        StructField("prior_rating_count", LongType(), False),
        StructField("content_revision", StringType(), False),
        StructField("implementation_revision", StringType(), True),
    ]
)


@dataclass(frozen=True)
class ContentArtifactBuild:
    """Validated, not yet published content-artifacts-v2 contents."""

    content_version: str
    features: DataFrame
    vocabulary: DataFrame
    metadata: Row


@dataclass(frozen=True)
class ContentArtifacts:
    """A validated content-artifacts-v2 version."""

    version_uri: str
    features: DataFrame
    vocabulary: DataFrame
    metadata: Row


def content_version_uri(output_uri: str, content_version: str) -> str:
    """Return the contract path ``<output-uri>/content/<content-version>``."""
    validate_storage_uri(output_uri, "content output URI")
    validate_content_version(content_version)
    return _child_uri(_child_uri(output_uri, "content"), content_version)


def validate_storage_uri(uri: str, label: str) -> None:
    """Accept only local or hdfs URIs without query, fragment or movable aliases."""
    if not uri:
        raise ValueError(f"{label} is required")
    parsed = urlsplit(uri)
    if parsed.scheme not in SUPPORTED_SCHEMES or parsed.query or parsed.fragment:
        raise ValueError(f"{label} must be a local or hdfs path")
    segments = [segment.lower() for segment in parsed.path.split("/") if segment]
    if not segments or any(segment in MOVABLE_ALIASES for segment in segments):
        raise ValueError(f"{label} must be an immutable versioned path")


def validate_content_version(content_version: str) -> None:
    if not isinstance(content_version, str) or not VERSION_PATTERN.fullmatch(content_version):
        raise ValueError("content version must match ^[a-z0-9][a-z0-9._-]{0,127}$")


def build_content_artifacts(
    *,
    ratings: DataFrame,
    service_mapping: DataFrame,
    tmdb_snapshot: DataFrame,
    movie_metadata: DataFrame,
    input_uri: str,
    mapping_revision: str,
    tmdb_snapshot_revision: str,
    movie_metadata_revision: str,
    input_snapshot: str,
    content_version: str,
    implementation_revision: str | None = None,
    built_at_utc: str | None = None,
) -> ContentArtifactBuild:
    """Validate the published input snapshots and compute one v2 bundle.

    ``input_uri`` is the mapping snapshot root whose ``ratings/`` and
    ``service-movie-id-map/`` were read.  Every upstream revision is an explicit
    argument and must match the data, and the movie metadata must be derived from
    exactly this mapping and TMDb snapshot.
    """
    validate_content_version(content_version)
    validate_storage_uri(input_uri, "content input URI")
    for label, value in (
        ("mapping revision", mapping_revision),
        ("TMDb snapshot revision", tmdb_snapshot_revision),
        ("movie metadata revision", movie_metadata_revision),
    ):
        if not isinstance(value, str) or not REVISION_PATTERN.fullmatch(value):
            raise ValueError(f"{label} must be a sha256 digest")
    _validate_input_snapshot(input_snapshot)
    if implementation_revision is not None and not implementation_revision:
        raise ValueError("implementation revision must not be empty")
    if _distinct(service_mapping, "mapping_revision") != [mapping_revision]:
        raise ValueError("mapping revision does not match the published mapping snapshot")
    if _distinct(movie_metadata, "movie_metadata_revision") != [movie_metadata_revision]:
        raise ValueError("movie metadata revision does not match the published snapshot")
    if _distinct(movie_metadata, "input_snapshot") != [input_snapshot]:
        raise ValueError("movie metadata input snapshot does not match")
    if _distinct(tmdb_snapshot, "tmdb_snapshot_revision") != [tmdb_snapshot_revision]:
        raise ValueError("TMDb snapshot revision does not match the published snapshot")
    if _distinct(tmdb_snapshot, "mapping_revision") != [mapping_revision]:
        raise ValueError("TMDb snapshot was not built from the mapping snapshot")

    spark = service_mapping.sparkSession
    movies = _service_content_rows(service_mapping, tmdb_snapshot).cache()
    _validate_movie_metadata_lineage(service_mapping, tmdb_snapshot, movie_metadata)
    tokens = _weighted_tokens(movies).cache()
    distinct_tokens = [row["token"] for row in tokens.select("token").distinct().collect()]
    vocabulary_map = build_vocabulary([dict.fromkeys(distinct_tokens, 1.0)])
    vocabulary = spark.createDataFrame(
        [(index, token) for token, index in vocabulary_map.items()], VOCABULARY_SCHEMA
    ).cache()
    features = _encode_features(movies, tokens, vocabulary).cache()
    prior_probability, prior_midrank, prior_users, prior_ratings = _rating_prior(ratings)

    service_movie_count = features.count()
    supported_movie_count = features.filter(F.size("indices") > 0).count()
    content_revision = compute_content_revision(features, vocabulary, prior_probability)
    metadata = Row(
        contract_version=CONTRACT_VERSION,
        content_version=content_version,
        input_snapshot=input_snapshot,
        input_uri=input_uri,
        mapping_revision=mapping_revision,
        movie_metadata_revision=movie_metadata_revision,
        tmdb_snapshot_revision=tmdb_snapshot_revision,
        feature_rule=FEATURE_RULE,
        eligibility_rule=ELIGIBILITY_RULE,
        built_at_utc=built_at_utc or _utc_now(),
        spark_version=spark.version,
        service_movie_count=service_movie_count,
        supported_movie_count=supported_movie_count,
        vocabulary_size=len(vocabulary_map),
        prior_probability=prior_probability,
        prior_midrank=prior_midrank,
        prior_user_count=prior_users,
        prior_rating_count=prior_ratings,
        content_revision=content_revision,
        implementation_revision=implementation_revision,
    )
    return ContentArtifactBuild(content_version, features, vocabulary, metadata)


def save_content_artifacts(
    spark: SparkSession, build: ContentArtifactBuild, output_uri: str
) -> str:
    """Write to a sibling temporary path, reload-validate, then atomically publish."""
    final_uri = content_version_uri(output_uri, build.content_version)
    if _path_exists(spark, final_uri):
        raise FileExistsError(f"content artifact version already exists: {final_uri}")
    temporary_uri = _child_uri(
        _child_uri(output_uri, "content"), f".{build.content_version}.tmp-{uuid4().hex}"
    )
    try:
        build.features.select(*FEATURES_SCHEMA.fieldNames()).write.mode("errorifexists").parquet(
            _child_uri(temporary_uri, "features")
        )
        build.vocabulary.select(*VOCABULARY_SCHEMA.fieldNames()).write.mode(
            "errorifexists"
        ).parquet(_child_uri(temporary_uri, "vocabulary"))
        metadata_values = tuple(build.metadata[name] for name in METADATA_SCHEMA.fieldNames())
        spark.createDataFrame([metadata_values], METADATA_SCHEMA).coalesce(1).write.mode(
            "errorifexists"
        ).parquet(_child_uri(temporary_uri, "metadata"))
        _load_content_artifacts_at_uri(spark, temporary_uri, build.content_version)
        _publish_directory(spark, temporary_uri, final_uri)
    except Exception:
        _delete_path(spark, temporary_uri)
        raise
    return final_uri


def load_content_artifacts(
    spark: SparkSession, output_uri: str, content_version: str
) -> ContentArtifacts:
    """Load and fully validate one published content-artifacts-v2 version."""
    version_uri = content_version_uri(output_uri, content_version)
    if not _path_exists(spark, version_uri):
        raise FileNotFoundError(f"content artifact version does not exist: {version_uri}")
    return _load_content_artifacts_at_uri(spark, version_uri, content_version)


def compute_content_revision(
    features: DataFrame, vocabulary: DataFrame, prior_probability: list[float]
) -> str:
    """Hash canonical feature rows, vocabulary rows and the prior."""
    feature_digest = _rows_digest(features, FEATURES_SCHEMA.fieldNames())
    vocabulary_digest = _rows_digest(vocabulary, VOCABULARY_SCHEMA.fieldNames())
    prior_text = ",".join(repr(float(value)) for value in prior_probability)
    payload = f"features:{feature_digest}\nvocabulary:{vocabulary_digest}\nprior:{prior_text}"
    return f"sha256:{hashlib.sha256(payload.encode('utf-8')).hexdigest()}"


def _validate_input_snapshot(input_snapshot: object) -> None:
    if (
        not isinstance(input_snapshot, str)
        or not input_snapshot
        or input_snapshot.lower() in MOVABLE_ALIASES
    ):
        raise ValueError("input snapshot must be an immutable identifier")


def _validate_movie_metadata_lineage(
    service_mapping: DataFrame, tmdb_snapshot: DataFrame, movie_metadata: DataFrame
) -> None:
    """Require metadata rows to equal the rows derived from this mapping and TMDb snapshot.

    The movie-metadata schema records neither upstream revision, so the lineage is
    proven by content: the same service movies with the same title and genre codes.
    """
    expected = (
        service_mapping.select(
            F.col("service_movie_id").cast("long").alias("service_movie_id"),
            F.col("tmdb_id").cast("int").alias("tmdb_id"),
        )
        .join(
            tmdb_snapshot.select(
                F.col("id").cast("int").alias("tmdb_id"),
                F.col("title").cast("string").alias("title"),
                F.coalesce(
                    F.expr("transform(genres, value -> value.name)"),
                    F.array().cast("array<string>"),
                ).alias("genre_codes"),
            ),
            "tmdb_id",
            "inner",
        )
        .select("service_movie_id", "title", "genre_codes")
    )
    actual = movie_metadata.select(
        F.col("service_movie_id").cast("long").alias("service_movie_id"),
        F.col("title").cast("string").alias("title"),
        F.col("genre_codes").cast("array<string>").alias("genre_codes"),
    )
    if expected.exceptAll(actual).limit(1).count() or actual.exceptAll(expected).limit(1).count():
        raise ValueError(
            "movie metadata was not built from this service mapping and TMDb snapshot"
        )


def _service_content_rows(service_mapping: DataFrame, tmdb_snapshot: DataFrame) -> DataFrame:
    """Attach normalized TMDb fields to every service movie regardless of status."""
    mapping = service_mapping.select(
        F.col("service_movie_id").cast("long"), F.col("tmdb_id").cast("int")
    )
    invalid_ids = mapping.filter(
        F.col("service_movie_id").isNull()
        | (F.col("service_movie_id") <= 0)
        | F.col("tmdb_id").isNull()
        | (F.col("tmdb_id") <= 0)
    )
    if invalid_ids.limit(1).count():
        raise ValueError("service mapping IDs must be positive")
    for column in ("service_movie_id", "tmdb_id"):
        if mapping.groupBy(column).count().filter(F.col("count") != 1).limit(1).count():
            raise ValueError("service mapping IDs must be unique")
    snapshot = tmdb_snapshot.select(
        F.col("id").cast("int").alias("tmdb_id"),
        "original_language",
        "release_year",
        "runtime_minutes",
        "genre_ids",
        "director_ids",
        "top5_cast_ids",
        "keyword_ids",
        "feature_eligible",
    )
    if snapshot.groupBy("tmdb_id").count().filter(F.col("count") != 1).limit(1).count():
        raise ValueError("TMDb snapshot IDs must be unique")
    if mapping.join(snapshot, "tmdb_id", "left_anti").limit(1).count():
        raise ValueError("every service movie must have a TMDb snapshot row")
    return mapping.join(snapshot, "tmdb_id", "inner")


def _weighted_tokens(movies: DataFrame) -> DataFrame:
    """Emit (service_movie_id, token, weight) with the 315 four-group formula."""

    def prefixed(prefix: str, column: str):
        return F.transform(
            F.coalesce(F.col(column), F.array().cast("array<int>")),
            lambda value: F.concat(F.lit(f"{prefix}:"), value.cast("string")),
        )

    context = F.filter(
        F.array(
            F.when(
                F.col("original_language").isNotNull(),
                F.concat(F.lit("language:"), F.col("original_language")),
            ),
            F.when(
                F.col("release_year").isNotNull(),
                F.concat(
                    F.lit("decade:"),
                    (F.floor(F.col("release_year") / 10) * 10).cast("long").cast("string"),
                ),
            ),
            F.when(
                F.col("runtime_minutes").isNotNull(),
                F.concat(
                    F.lit("runtime30:"),
                    F.floor(F.col("runtime_minutes") / 30).cast("long").cast("string"),
                ),
            ),
        ),
        lambda token: token.isNotNull(),
    )
    groups = F.array(
        F.array_distinct(prefixed("genre", "genre_ids")),
        F.array_distinct(context),
        F.array_distinct(
            F.concat(prefixed("director", "director_ids"), prefixed("cast", "top5_cast_ids"))
        ),
        F.array_distinct(prefixed("keyword", "keyword_ids")),
    )
    eligible = movies.filter(F.col("feature_eligible") == F.lit(True)).select(
        "service_movie_id", groups.alias("groups")
    )
    # Python accumulates each token's squared 0.25/sqrt(n) weight; the norm is
    # therefore sqrt(sum_g n_g * (0.25 / sqrt(n_g))^2) over non-empty groups.
    nonempty = F.filter(F.col("groups"), lambda group: F.size(group) > 0)
    norm = F.sqrt(
        F.aggregate(
            nonempty,
            F.lit(0.0).cast("double"),
            lambda total, group: total
            + F.size(group) * F.pow(F.lit(GROUP_WEIGHT) / F.sqrt(F.size(group)), 2),
        )
    )
    exploded = eligible.select(
        "service_movie_id",
        norm.alias("norm"),
        F.explode(nonempty).alias("group"),
    ).select(
        "service_movie_id",
        F.explode("group").alias("token"),
        ((F.lit(GROUP_WEIGHT) / F.sqrt(F.size("group"))) / F.col("norm")).alias("weight"),
    )
    return exploded


def _encode_features(movies: DataFrame, tokens: DataFrame, vocabulary: DataFrame) -> DataFrame:
    encoded = (
        tokens.join(vocabulary, "token", "inner")
        .groupBy("service_movie_id")
        .agg(
            F.array_sort(
                F.collect_list(F.struct(F.col("feature_index"), F.col("weight")))
            ).alias("entries")
        )
    )
    return movies.select("service_movie_id", "tmdb_id").join(
        encoded, "service_movie_id", "left"
    ).select(
        F.col("service_movie_id").cast("long").alias("service_movie_id"),
        F.col("tmdb_id").cast("int").alias("tmdb_id"),
        F.coalesce(
            F.transform("entries", lambda entry: entry["feature_index"]),
            F.array().cast("array<int>"),
        ).alias("indices"),
        F.coalesce(
            F.transform("entries", lambda entry: entry["weight"]),
            F.array().cast("array<double>"),
        ).alias("values"),
    )


def _rating_prior(ratings: DataFrame) -> tuple[list[float], list[float], int, int]:
    """Collect per-user ten-bin histograms and call the 315 equal-user prior."""
    frame = ratings.select(
        F.col("movielens_user_id").cast("long").alias("user_id"),
        F.col("rating").cast("double").alias("rating"),
    )
    allowed = [float(value) for value in RATING_VALUES]
    if frame.filter(
        F.col("user_id").isNull() | F.col("rating").isNull() | ~F.col("rating").isin(allowed)
    ).limit(1).count():
        raise ValueError("prior ratings must be 0.5 through 5.0 in half-star increments")
    bin_index = (F.col("rating") * 2).cast("int") - 1
    histograms = (
        frame.groupBy("user_id")
        .agg(
            *[
                F.sum(F.when(bin_index == index, 1).otherwise(0)).alias(f"bin_{index}")
                for index in range(10)
            ]
        )
        .orderBy("user_id")
    )
    rows = [[float(row[f"bin_{index}"]) for index in range(10)] for row in histograms.collect()]
    if not rows:
        raise ValueError("prior ratings must not be empty")
    probability, midrank = user_equal_prior(rows)
    return (
        [float(value) for value in probability],
        [float(value) for value in midrank],
        len(rows),
        int(sum(sum(row) for row in rows)),
    )


def _load_content_artifacts_at_uri(
    spark: SparkSession, version_uri: str, content_version: str
) -> ContentArtifacts:
    for child in ("features", "vocabulary", "metadata"):
        if not _path_exists(spark, _child_uri(version_uri, child)):
            raise FileNotFoundError(f"content artifact is missing {child}/")
    metadata_frame = spark.read.parquet(_child_uri(version_uri, "metadata"))
    rows = metadata_frame.limit(2).collect()
    if len(rows) != 1:
        raise ValueError("content metadata must contain exactly one row")
    metadata = rows[0]
    _validate_metadata(metadata_frame, metadata, content_version)
    features = spark.read.parquet(_child_uri(version_uri, "features"))
    vocabulary = spark.read.parquet(_child_uri(version_uri, "vocabulary"))
    _require_types(features, FEATURES_SCHEMA, "features")
    _require_types(vocabulary, VOCABULARY_SCHEMA, "vocabulary")
    _validate_vocabulary(vocabulary, metadata["vocabulary_size"])
    _validate_features(features, metadata)
    revision = compute_content_revision(features, vocabulary, list(metadata["prior_probability"]))
    if revision != metadata["content_revision"]:
        raise ValueError("content revision does not match artifact contents")
    return ContentArtifacts(version_uri, features, vocabulary, metadata)


def _validate_metadata(frame: DataFrame, metadata: Row, content_version: str) -> None:
    _require_types(frame, METADATA_SCHEMA, "metadata")
    if metadata["contract_version"] != CONTRACT_VERSION:
        raise ValueError("unsupported content contract version")
    if metadata["content_version"] != content_version:
        raise ValueError("content metadata version does not match its path")
    required = [field.name for field in METADATA_SCHEMA.fields if not field.nullable]
    if any(metadata[name] is None for name in required):
        raise ValueError("content metadata has null required values")
    for name in (
        "mapping_revision",
        "movie_metadata_revision",
        "tmdb_snapshot_revision",
        "content_revision",
    ):
        if not REVISION_PATTERN.fullmatch(metadata[name]):
            raise ValueError(f"content metadata {name} must be a sha256 digest")
    if metadata["feature_rule"] != FEATURE_RULE or metadata["eligibility_rule"] != ELIGIBILITY_RULE:
        raise ValueError("unsupported content feature rule")
    if metadata["implementation_revision"] == "":
        raise ValueError("implementation revision must not be empty")
    _validate_input_snapshot(metadata["input_snapshot"])
    validate_storage_uri(metadata["input_uri"], "content metadata input_uri")
    if not metadata["spark_version"]:
        raise ValueError("content metadata spark_version must not be empty")
    timestamp = metadata["built_at_utc"]
    if not UTC_TIMESTAMP_PATTERN.fullmatch(timestamp):
        raise ValueError("content metadata built_at_utc must use YYYY-MM-DDTHH:mm:ssZ")
    try:
        datetime.strptime(timestamp, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as error:
        raise ValueError("content metadata built_at_utc is not a valid UTC time") from error
    counts = (
        metadata["service_movie_count"],
        metadata["supported_movie_count"],
        metadata["vocabulary_size"],
        metadata["prior_user_count"],
        metadata["prior_rating_count"],
    )
    if (
        counts[0] <= 0
        or not 0 <= counts[1] <= counts[0]
        or counts[2] < 0
        or counts[3] <= 0
        or counts[4] < counts[3]
    ):
        raise ValueError("content metadata counts are inconsistent")
    probability = list(metadata["prior_probability"])
    midrank = list(metadata["prior_midrank"])
    if (
        len(probability) != 10
        or len(midrank) != 10
        or not all(math.isfinite(value) and value >= 0 for value in probability)
        or not all(math.isfinite(value) for value in midrank)
        or abs(sum(probability) - 1.0) > PRIOR_TOLERANCE
        or any(later < earlier for earlier, later in zip(midrank, midrank[1:], strict=False))
    ):
        raise ValueError("content metadata prior is invalid")
    cumulative = 0.0
    for probability_value, midrank_value in zip(probability, midrank, strict=True):
        cumulative += probability_value
        if abs((cumulative - 0.5 * probability_value) - midrank_value) > MIDRANK_TOLERANCE:
            raise ValueError("content metadata prior midrank does not match its probability")


def _validate_vocabulary(vocabulary: DataFrame, vocabulary_size: int) -> None:
    summary = vocabulary.agg(
        F.count(F.lit(1)).alias("rows"),
        F.countDistinct("feature_index").alias("indexes"),
        F.countDistinct("token").alias("tokens"),
        F.min("feature_index").alias("minimum"),
        F.max("feature_index").alias("maximum"),
        F.sum(F.when(F.col("token").isNull() | (F.col("token") == ""), 1).otherwise(0)).alias(
            "blank"
        ),
    ).first()
    if summary["rows"] != vocabulary_size:
        raise ValueError("content vocabulary size does not match metadata")
    if vocabulary_size and (
        summary["indexes"] != vocabulary_size
        or summary["tokens"] != vocabulary_size
        or summary["minimum"] != 0
        or summary["maximum"] != vocabulary_size - 1
        or summary["blank"]
    ):
        raise ValueError("content vocabulary indexes must be contiguous with unique tokens")


def _validate_features(features: DataFrame, metadata: Row) -> None:
    size = metadata["vocabulary_size"]
    invalid = features.filter(
        F.col("service_movie_id").isNull()
        | (F.col("service_movie_id") <= 0)
        | F.col("tmdb_id").isNull()
        | (F.col("tmdb_id") <= 0)
        | F.col("indices").isNull()
        | F.col("values").isNull()
        | (F.size("indices") != F.size("values"))
        | (F.array_sort(F.array_distinct("indices")) != F.col("indices"))
        | F.exists("indices", lambda index: index.isNull() | (index < 0) | (index >= size))
        | F.exists(
            "values",
            lambda value: value.isNull()
            | F.isnan(value)
            | (value == float("inf"))
            | (value <= 0),
        )
    )
    if invalid.limit(1).count():
        raise ValueError("content features violate the artifact contract")
    summary = features.agg(
        F.count(F.lit(1)).alias("rows"),
        F.countDistinct("service_movie_id").alias("service_ids"),
        F.countDistinct("tmdb_id").alias("tmdb_ids"),
        F.sum(F.when(F.size("indices") > 0, 1).otherwise(0)).alias("supported"),
    ).first()
    if (
        summary["rows"] != metadata["service_movie_count"]
        or summary["service_ids"] != summary["rows"]
        or summary["tmdb_ids"] != summary["rows"]
        or summary["supported"] != metadata["supported_movie_count"]
    ):
        raise ValueError("content features do not match metadata counts or are duplicated")


def _require_types(frame: DataFrame, schema: StructType, label: str) -> None:
    actual = {field.name: field.dataType for field in frame.schema.fields}
    for field in schema.fields:
        data_type = actual.get(field.name)
        if data_type is None or _without_nullability(data_type) != _without_nullability(
            field.dataType
        ):
            raise ValueError(f"content {label} has missing or incompatible columns")


def _without_nullability(data_type):
    if isinstance(data_type, ArrayType):
        return ("array", _without_nullability(data_type.elementType))
    return data_type.simpleString()


def _rows_digest(frame: DataFrame, columns: list[str]) -> str:
    row_hash = F.sha2(F.to_json(F.struct(*columns)), 256)
    digest = (
        frame.select(row_hash.alias("row_hash"))
        .agg(F.sha2(F.concat_ws("", F.array_sort(F.collect_list("row_hash"))), 256))
        .first()[0]
    )
    return digest or hashlib.sha256(b"").hexdigest()


def _distinct(frame: DataFrame, column: str) -> list[str | None]:
    values = [row[column] for row in frame.select(column).distinct().collect()]
    return sorted(values, key=lambda value: (value is None, value or ""))


def _utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
