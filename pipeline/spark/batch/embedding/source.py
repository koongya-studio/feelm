"""Build reproducible E5 input texts for the service movie catalog.

This module deliberately reads the raw TMDb documents rather than the v2
content-serving artifact.  The latter contains IDs and sparse features; it
does not retain the synopsis and person/keyword names needed by a text model.
"""

from __future__ import annotations

import re

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F
from pyspark.sql.types import ArrayType, IntegerType, LongType, StringType, StructField, StructType

MAX_INT = 2_147_483_647
TOP_CAST_LIMIT = 5
REVISION_PATTERN = re.compile(r"^sha256:[0-9a-f]{64}$")

_ID_NAME = ArrayType(StructType([StructField("id", LongType()), StructField("name", StringType())]))
_PERSON = StructType(
    [
        StructField("id", LongType()),
        StructField("name", StringType()),
        StructField("order", IntegerType()),
        StructField("job", StringType()),
    ]
)
RAW_MOVIE_SCHEMA = StructType(
    [
        StructField(
            "details",
            StructType(
                [
                    StructField("id", LongType()),
                    StructField("title", StringType()),
                    StructField("original_title", StringType()),
                    StructField("overview", StringType()),
                    StructField("genres", _ID_NAME),
                ]
            ),
        ),
        StructField(
            "credits",
            StructType(
                [StructField("cast", ArrayType(_PERSON)), StructField("crew", ArrayType(_PERSON))]
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


def read_raw_embedding_movies(spark: SparkSession, uri: str) -> DataFrame:
    """Read raw per-movie TMDb documents and preserve their source filename."""
    return _read_raw_json(spark, uri, RAW_MOVIE_SCHEMA)


def read_raw_embedding_keywords(spark: SparkSession, uri: str) -> DataFrame:
    """Read raw per-movie TMDb keyword documents and preserve their source filename."""
    return _read_raw_json(spark, uri, RAW_KEYWORD_SCHEMA)


def build_embedding_source(
    raw_movies: DataFrame, raw_keywords: DataFrame, service_mapping: DataFrame
) -> DataFrame:
    """Return one non-empty text document per mapped service movie.

    ``movie_id`` is exactly the backend ``movies.id`` exported as
    ``service_movie_id``.  A missing or malformed movie document is an input
    error; missing keywords, overview, cast, and crew are allowed.  A row with
    neither title nor original title is excluded with its explicit reason.
    """
    _validate_mapping(service_mapping)
    movies = raw_movies.select(
        _file_tmdb_id(F.col("file_name")).alias("file_tmdb_id"),
        F.col("details.id").alias("body_tmdb_id"),
        "file_name",
        "details",
        "credits",
    )
    _reject_file_body_mismatch(movies, "movie")
    _reject_duplicates(movies, "file_tmdb_id", "raw TMDb movie documents")
    keywords = raw_keywords.select(
        _file_tmdb_id(F.col("file_name")).alias("file_tmdb_id"),
        F.col("id").alias("body_tmdb_id"),
        "file_name",
        F.coalesce(F.col("keywords"), F.col("results")).alias("keyword_items"),
    )
    _reject_file_body_mismatch(keywords, "keyword")
    _reject_duplicates(keywords, "file_tmdb_id", "raw TMDb keyword documents")

    mapping = service_mapping.select(
        F.col("service_movie_id").cast("long").alias("movie_id"),
        F.col("tmdb_id").cast("int").alias("tmdb_id"),
        "mapping_revision",
    )
    missing = mapping.join(
        movies.select(F.col("file_tmdb_id").alias("tmdb_id")), "tmdb_id", "left_anti"
    )
    first_missing = missing.orderBy("movie_id").limit(1).collect()
    if first_missing:
        raise ValueError(
            "every service movie mapping must have a raw TMDb movie document "
            f"(first movie_id={first_missing[0]['movie_id']}, "
            f"tmdb_id={first_missing[0]['tmdb_id']})"
        )

    joined = (
        mapping.join(movies, mapping.tmdb_id == movies.file_tmdb_id, "inner")
        .join(
            keywords.select(F.col("file_tmdb_id").alias("keyword_tmdb_id"), "keyword_items"),
            mapping.tmdb_id == F.col("keyword_tmdb_id"),
            "left",
        )
        .select("movie_id", "tmdb_id", "mapping_revision", "details", "credits", "keyword_items")
    )
    source = joined.select(
        "movie_id",
        "tmdb_id",
        "mapping_revision",
        _source_text("details", "credits", "keyword_items").alias("source_text"),
    ).withColumn("input_text_sha256", F.sha2("source_text", 256))
    valid = source.filter(F.length("source_text") > 0)
    _reject_duplicates(valid, "movie_id", "embedding source movie IDs")
    return valid


def _read_raw_json(spark: SparkSession, uri: str, schema: StructType) -> DataFrame:
    frame = (
        spark.read.schema(schema).option("multiLine", "true").option("mode", "FAILFAST").json(uri)
    )
    return frame.select("*", F.col("_metadata.file_name").alias("file_name"))


def _validate_mapping(mapping: DataFrame) -> None:
    required = {"service_movie_id", "tmdb_id", "mapping_revision"}
    missing = sorted(required - set(mapping.columns))
    if missing:
        raise ValueError(f"service mapping is missing required columns: {missing}")
    revisions = [
        row["mapping_revision"] for row in mapping.select("mapping_revision").distinct().collect()
    ]
    if len(revisions) != 1 or not REVISION_PATTERN.fullmatch(revisions[0] or ""):
        raise ValueError("service mapping must contain one sha256 mapping revision")
    invalid = mapping.filter(
        F.col("service_movie_id").isNull()
        | (F.col("service_movie_id") <= 0)
        | F.col("tmdb_id").isNull()
        | (F.col("tmdb_id") <= 0)
        | (F.col("tmdb_id") > MAX_INT)
    )
    if invalid.limit(1).count():
        raise ValueError("service mapping contains invalid movie IDs")
    for column in ("service_movie_id", "tmdb_id"):
        _reject_duplicates(mapping, column, "service mapping")


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


def _reject_duplicates(frame: DataFrame, column: str, label: str) -> None:
    duplicate = frame.groupBy(column).count().filter(F.col("count") != 1).limit(1).collect()
    if duplicate:
        raise ValueError(f"{label} must be unique ({column}={duplicate[0][column]})")


def _source_text(details: str, credits: str, keyword_items: str):
    title = _clean(F.col(f"{details}.title"))
    original_title = _clean(F.col(f"{details}.original_title"))
    overview = _clean(F.col(f"{details}.overview"))
    genres = _names(F.col(f"{details}.genres"))
    cast = _cast_names(F.col(f"{credits}.cast"))
    directors = _director_names(F.col(f"{credits}.crew"))
    keywords = _names(F.col(keyword_items))
    segments = F.array(
        _label("title", title),
        F.when(
            (original_title != "") & (original_title != title),
            _label("original title", original_title),
        ),
        _label("genres", genres),
        _label("overview", overview),
        _label("director", directors),
        _label("cast", cast),
        _label("keywords", keywords),
    )
    return F.concat_ws("\n", F.filter(segments, lambda value: value.isNotNull()))


def _clean(value):
    whitespace_collapsed = F.regexp_replace(F.coalesce(value, F.lit("")), "[\r\n\t]+", " ")
    return F.trim(F.regexp_replace(whitespace_collapsed, r"\\s+", " "))


def _label(label: str, value):
    return F.when(value != "", F.concat(F.lit(f"{label}: "), value))


def _names(values):
    names = F.transform(
        F.coalesce(values, F.array().cast(_ID_NAME)), lambda value: _clean(value["name"])
    )
    return F.concat_ws(", ", F.array_distinct(F.filter(names, lambda value: value != "")))


def _cast_names(values):
    candidates = F.filter(
        F.coalesce(values, F.array().cast(ArrayType(_PERSON))),
        lambda value: value["id"].isNotNull() & (value["id"] > 0) & (_clean(value["name"]) != ""),
    )
    ordered = F.array_sort(
        F.transform(
            candidates,
            lambda value: F.struct(
                F.coalesce(value["order"], F.lit(MAX_INT)).alias("order"),
                value["id"].alias("id"),
                _clean(value["name"]).alias("name"),
            ),
        )
    )
    names = F.transform(F.slice(ordered, 1, TOP_CAST_LIMIT), lambda value: value["name"])
    return F.concat_ws(", ", F.array_distinct(names))


def _director_names(values):
    directors = F.filter(
        F.coalesce(values, F.array().cast(ArrayType(_PERSON))),
        lambda value: (value["job"] == "Director")
        & (value["id"] > 0)
        & (_clean(value["name"]) != ""),
    )
    sorted_directors = F.array_sort(
        F.transform(
            directors,
            lambda value: F.struct(value["id"].alias("id"), _clean(value["name"]).alias("name")),
        )
    )
    names = F.transform(sorted_directors, lambda value: value["name"])
    return F.concat_ws(", ", F.array_distinct(names))
