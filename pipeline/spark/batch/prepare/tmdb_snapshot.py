"""Normalize raw TMDb movie, credit and keyword JSON into an immutable snapshot.

The snapshot is the shared TMDb input of the movie-metadata snapshot and the
content-artifacts-v2 producer.  It keeps TMDb's ``id``, ``title`` and ``genres``
column names so the existing movie-metadata builder can read it unchanged.
"""

from __future__ import annotations

import re
from urllib.parse import urlsplit
from uuid import uuid4

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F
from pyspark.sql.types import (
    ArrayType,
    LongType,
    StringType,
    StructField,
    StructType,
)

from batch.train.artifacts import _delete_path, _path_exists, _publish_directory

MAX_INT = 2_147_483_647
MISSING_CAST_ORDER = 1_000_000
TOP_CAST_LIMIT = 5
REVISION_PATTERN = re.compile(r"^sha256:[0-9a-f]{64}$")
SUPPORTED_SCHEMES = {"", "file", "hdfs"}
MOVABLE_ALIASES = frozenset({"latest", "current", "stable", "production", "prod"})
# Python ``str.strip()`` removes every ``str.isspace()`` character while Spark
# ``trim`` removes only U+0020.  Use the same character class so tokens match 315.
_PY_WHITESPACE = "".join(
    f"\\x{{{code:x}}}" for code in range(0x110000) if chr(code).isspace()
)
STRIP_PATTERN = f"^[{_PY_WHITESPACE}]+|[{_PY_WHITESPACE}]+$"

_ID_NAME = ArrayType(StructType([StructField("id", LongType()), StructField("name", StringType())]))
RAW_MOVIE_SCHEMA = StructType(
    [
        StructField(
            "details",
            StructType(
                [
                    StructField("id", LongType()),
                    StructField("title", StringType()),
                    StructField("original_title", StringType()),
                    StructField("original_language", StringType()),
                    StructField("release_date", StringType()),
                    StructField("runtime", LongType()),
                    StructField("genres", _ID_NAME),
                ]
            ),
        ),
        StructField(
            "credits",
            StructType(
                [
                    StructField(
                        "cast",
                        ArrayType(
                            StructType(
                                [StructField("id", LongType()), StructField("order", LongType())]
                            )
                        ),
                    ),
                    StructField(
                        "crew",
                        ArrayType(
                            StructType(
                                [StructField("id", LongType()), StructField("job", StringType())]
                            )
                        ),
                    ),
                ]
            ),
        ),
    ]
)
RAW_KEYWORD_SCHEMA = StructType(
    [
        StructField("id", LongType()),
        StructField("keywords", _ID_NAME),
        StructField("results", _ID_NAME),
    ]
)
SNAPSHOT_COLUMNS = [
    "id",
    "mapping_revision",
    "title",
    "original_title",
    "genres",
    "original_language",
    "release_year",
    "runtime_minutes",
    "genre_ids",
    "director_ids",
    "top5_cast_ids",
    "top5_cast_source_order",
    "keyword_ids",
    "keywords_present",
    "feature_eligible",
]


def read_raw_movies(spark: SparkSession, movies_uri: str) -> DataFrame:
    """Read one pretty-printed ``<tmdb-id>.json`` document per movie."""
    return _read_raw_json(spark, movies_uri, RAW_MOVIE_SCHEMA)


def read_raw_keywords(spark: SparkSession, keywords_uri: str) -> DataFrame:
    """Read one ``<tmdb-id>.json`` keyword response per movie."""
    return _read_raw_json(spark, keywords_uri, RAW_KEYWORD_SCHEMA)


def build_tmdb_snapshot(
    raw_movies: DataFrame, raw_keywords: DataFrame, service_mapping: DataFrame
) -> DataFrame:
    """Normalize every service movie's TMDb row and attach one snapshot revision.

    Every service ``tmdb_id`` must have exactly one movie document whose body ID
    equals its file name.  Keyword documents are optional per movie, but a
    present document must be unique and match its file name as well.  The single
    ``mapping_revision`` of ``service_mapping`` is recorded on every row so the
    catalog scope of the snapshot is traceable.
    """
    mapping_revisions = [
        row["mapping_revision"]
        for row in service_mapping.select("mapping_revision").distinct().collect()
    ]
    if len(mapping_revisions) != 1 or not REVISION_PATTERN.fullmatch(mapping_revisions[0] or ""):
        raise ValueError("service mapping must contain one sha256 mapping revision")
    movies = raw_movies.select(
        _file_tmdb_id(F.col("file_name")).alias("file_tmdb_id"),
        F.col("details.id").alias("body_tmdb_id"),
        # Kept so a rejection can name the offending file.
        "file_name",
        "details",
        "credits",
    )
    _reject_file_body_mismatch(movies, "movie")
    keywords = raw_keywords.select(
        _file_tmdb_id(F.col("file_name")).alias("file_tmdb_id"),
        F.col("id").alias("body_tmdb_id"),
        "file_name",
        F.coalesce(F.col("keywords"), F.col("results")).alias("keyword_items"),
    )
    _reject_file_body_mismatch(keywords, "keyword")

    service_ids = service_mapping.select(F.col("tmdb_id").cast("long").alias("tmdb_id")).distinct()
    missing = service_ids.join(
        movies.select(F.col("file_tmdb_id").alias("tmdb_id")), "tmdb_id", "left_anti"
    )
    first_missing = missing.orderBy("tmdb_id").limit(1).collect()
    if first_missing:
        raise ValueError(
            "every service movie must have a raw TMDb movie document "
            f"(first missing tmdb_id={first_missing[0]['tmdb_id']})"
        )

    scoped = movies.join(service_ids, movies.file_tmdb_id == service_ids.tmdb_id, "inner").drop(
        "tmdb_id"
    )
    keyed_keywords = keywords.join(
        service_ids, keywords.file_tmdb_id == service_ids.tmdb_id, "inner"
    ).select(F.col("file_tmdb_id").alias("keyword_tmdb_id"), "keyword_items")
    joined = scoped.join(
        keyed_keywords, scoped.file_tmdb_id == keyed_keywords.keyword_tmdb_id, "left"
    )
    snapshot = joined.select(
        F.col("file_tmdb_id").cast("int").alias("id"),
        F.lit(mapping_revisions[0]).alias("mapping_revision"),
        *_normalized_columns(),
    ).cache()
    _reject_duplicates(snapshot)
    row_hash = F.sha2(F.to_json(F.struct(*SNAPSHOT_COLUMNS)), 256)
    digest = (
        snapshot.select(row_hash.alias("row_hash"))
        .agg(F.sha2(F.concat_ws("", F.array_sort(F.collect_list("row_hash"))), 256))
        .first()[0]
    )
    if digest is None:
        raise ValueError("TMDb snapshot must not be empty")
    return snapshot.withColumn("tmdb_snapshot_revision", F.lit(f"sha256:{digest}"))


def publish_tmdb_snapshot(snapshot: DataFrame, output_uri: str) -> str:
    """Write, reload and atomically publish one immutable TMDb snapshot."""
    _validate_output_uri(output_uri)
    spark = snapshot.sparkSession
    if _path_exists(spark, output_uri):
        raise FileExistsError(f"TMDb snapshot already exists: {output_uri}")
    revisions = _revisions(snapshot)
    expected_rows = snapshot.count()
    parent, name = output_uri.rstrip("/").rsplit("/", 1)
    # A hidden sibling is ignored by Spark/pyarrow readers if a hard crash leaves it.
    temporary_uri = f"{parent}/.{name}.tmp-{uuid4().hex}"
    try:
        snapshot.write.mode("errorifexists").parquet(temporary_uri)
        published = spark.read.parquet(temporary_uri)
        if published.count() != expected_rows or _revisions(published) != revisions:
            raise ValueError("TMDb snapshot changed before publication")
        _publish_directory(spark, temporary_uri, output_uri)
    except Exception:
        _delete_path(spark, temporary_uri)
        raise
    return output_uri


def _validate_output_uri(output_uri: str) -> None:
    """Reject unsupported or movable publication URIs before any write."""
    if not output_uri:
        raise ValueError("TMDb snapshot output URI is required")
    parsed = urlsplit(output_uri)
    segments = [segment.lower() for segment in parsed.path.split("/") if segment]
    if parsed.scheme not in SUPPORTED_SCHEMES or parsed.query or parsed.fragment:
        raise ValueError("TMDb snapshot output URI must be a local or hdfs path")
    if len(segments) < 2 or segments[-1] in MOVABLE_ALIASES:
        raise ValueError("TMDb snapshot output URI must be immutable")


def _read_raw_json(spark: SparkSession, uri: str, schema: StructType) -> DataFrame:
    frame = (
        spark.read.schema(schema)
        .option("multiLine", "true")
        .option("mode", "FAILFAST")
        .json(uri)
    )
    return frame.select("*", F.col("_metadata.file_name").alias("file_name"))


def _file_tmdb_id(file_name):
    digits = F.regexp_extract(file_name, r"^([0-9]{1,10})\.json$", 1)
    return F.when(digits != "", digits.cast("long"))


def _reject_file_body_mismatch(frame: DataFrame, label: str) -> None:
    invalid = frame.filter(
        F.col("file_tmdb_id").isNull()
        | (F.col("file_tmdb_id") <= 0)
        | (F.col("file_tmdb_id") > MAX_INT)
        | F.col("body_tmdb_id").isNull()
        | (F.col("file_tmdb_id") != F.col("body_tmdb_id"))
    )
    first = invalid.select("file_name", "body_tmdb_id").limit(1).collect()
    if first:
        raise ValueError(
            f"raw TMDb {label} file name and body id must match "
            f"(file={first[0]['file_name']}, body id={first[0]['body_tmdb_id']})"
        )


def _reject_duplicates(snapshot: DataFrame) -> None:
    duplicate = snapshot.groupBy("id").count().filter(F.col("count") != 1).limit(1).collect()
    if duplicate:
        raise ValueError(
            f"raw TMDb documents must be unique per tmdb id (tmdb_id={duplicate[0]['id']})"
        )


def _strip(column):
    """Strip leading and trailing characters exactly as Python ``str.strip()`` does."""
    return F.regexp_replace(column, STRIP_PATTERN, "")


def _or_empty(column, element_type: str):
    """Replace a null array with a typed empty array."""
    return F.coalesce(column, F.array().cast(f"array<{element_type}>"))


def _positive_ids(items, predicate=None):
    """Return sorted unique positive int IDs of a struct array (empty when null)."""
    selected = F.filter(items, predicate) if predicate is not None else items
    ids = F.transform(selected, lambda item: item["id"])
    valid = F.filter(ids, lambda value: value.isNotNull() & (value > 0) & (value <= MAX_INT))
    unique = F.array_sort(F.array_distinct(F.transform(valid, lambda value: value.cast("int"))))
    return _or_empty(unique, "int")


def _normalized_columns():
    details, credits = F.col("details"), F.col("credits")
    language = F.lower(_strip(details["original_language"]))
    release_date = _strip(details["release_date"])
    release_year = F.when(
        release_date.rlike(r"^[0-9]{4}"), F.substring(release_date, 1, 4).cast("int")
    )
    runtime = details["runtime"]
    genres = F.filter(
        details["genres"],
        lambda genre: genre["id"].isNotNull()
        & (genre["id"] > 0)
        & (genre["id"] <= MAX_INT)
        & (_strip(genre["name"]) != ""),
    )
    cast = F.array_sort(
        F.transform(
            F.filter(
                credits["cast"],
                lambda member: member["id"].isNotNull()
                & (member["id"] > 0)
                & (member["id"] <= MAX_INT),
            ),
            lambda member: F.struct(
                F.coalesce(member["order"], F.lit(MISSING_CAST_ORDER)).alias("order"),
                member["id"].alias("id"),
            ),
        )
    )
    sorted_cast_ids = F.transform(cast, lambda member: member["id"])
    top_cast = F.slice(F.array_distinct(sorted_cast_ids), 1, TOP_CAST_LIMIT)
    title = _strip(details["title"])
    original_title = _strip(details["original_title"])
    genre_ids = _positive_ids(genres)
    eligible_language = F.when(language != "", language)
    eligible_year = F.when(release_year > 0, release_year)
    return [
        F.when(title != "", title).alias("title"),
        F.when(original_title != "", original_title).alias("original_title"),
        _or_empty(
            F.transform(
                genres,
                lambda genre: F.struct(
                    genre["id"].cast("int").alias("id"), _strip(genre["name"]).alias("name")
                ),
            ),
            "struct<id:int,name:string>",
        ).alias("genres"),
        eligible_language.alias("original_language"),
        eligible_year.alias("release_year"),
        F.when((runtime > 0) & (runtime <= MAX_INT), runtime.cast("int")).alias("runtime_minutes"),
        genre_ids.alias("genre_ids"),
        _positive_ids(credits["crew"], lambda member: member["job"] == "Director").alias(
            "director_ids"
        ),
        _or_empty(F.transform(top_cast, lambda value: value.cast("int")), "int").alias(
            "top5_cast_ids"
        ),
        _or_empty(
            F.transform(
                top_cast,
                # First-occurrence position in the (order, id)-sorted valid cast list.
                lambda value: (F.array_position(sorted_cast_ids, value) - 1).cast("int"),
            ),
            "int",
        ).alias("top5_cast_source_order"),
        _positive_ids(F.col("keyword_items")).alias("keyword_ids"),
        F.col("keyword_tmdb_id").isNotNull().alias("keywords_present"),
        (
            eligible_language.isNotNull() & eligible_year.isNotNull() & (F.size(genre_ids) > 0)
        ).alias("feature_eligible"),
    ]


def _revisions(frame: DataFrame) -> list[str]:
    return [
        row["tmdb_snapshot_revision"]
        for row in frame.select("tmdb_snapshot_revision").distinct().collect()
    ]
