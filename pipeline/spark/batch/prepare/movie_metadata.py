"""Build the immutable service-movie metadata snapshot consumed by Fold-in."""

from __future__ import annotations

from uuid import uuid4

from pyspark.sql import DataFrame
from pyspark.sql import functions as F

from batch.train.artifacts import _delete_path, _path_exists, _publish_directory


def _revision(frame: DataFrame, columns: list[str]) -> str:
    """Return a stable SHA-256 over canonical, service-ID ordered row values."""
    row = F.concat_ws(
        "\x1f", *(F.coalesce(F.col(column).cast("string"), F.lit("<null>")) for column in columns)
    )
    digest = frame.select(row.alias("row")).agg(
        F.sha2(F.concat_ws("\n", F.sort_array(F.collect_list("row"))), 256).alias("revision")
    ).first()["revision"]
    if digest is None:
        raise ValueError("movie metadata snapshot must not be empty")
    return f"sha256:{digest}"


def _validate_metadata_values(metadata: DataFrame) -> None:
    """Apply the Consumer's title and genre value constraints before publication."""
    invalid = metadata.filter(
        F.col("title").isNull()
        | (F.col("title") == "")
        | F.expr("exists(genre_codes, value -> value is null OR value = '')")
    )
    if invalid.limit(1).count():
        raise ValueError("movie metadata title and genre codes must be non-empty")


def build_movie_metadata(
    service_mapping: DataFrame,
    tmdb_movies: DataFrame,
    input_snapshot: str,
    flavor_by_service_movie: DataFrame | None = None,
) -> DataFrame:
    """Join service mappings to TMDb JSON and attach one immutable revision.

    ``service_mapping`` is the existing mapping producer output. ``tmdb_movies``
    must expose TMDb's ``id``, ``title`` and ``genres`` fields. Flavor input is
    optional until that producer is available; null is an explicit value, not a
    fabricated flavor classification.
    """
    if not input_snapshot or input_snapshot.lower() == "latest":
        raise ValueError("input_snapshot must be immutable")
    matched = service_mapping.filter(F.col("mapping_status") == "MATCHED")
    tmdb = tmdb_movies.select(
        F.col("id").cast("int").alias("tmdb_id"),
        F.col("title").cast("string").alias("title"),
        F.expr("transform(genres, value -> value.name)").alias("genre_codes"),
    )
    missing_metadata = matched.select("service_movie_id", "tmdb_id").join(
        tmdb.select("tmdb_id"), "tmdb_id", "left_anti"
    )
    if missing_metadata.limit(1).count():
        raise ValueError("every MATCHED mapping must have TMDb metadata")
    result = matched.join(tmdb, "tmdb_id", "inner").select(
        F.col("service_movie_id").cast("long"),
        "title",
        F.coalesce(F.col("genre_codes"), F.array().cast("array<string>")).alias("genre_codes"),
    )
    if flavor_by_service_movie is None:
        result = result.withColumn("representative_flavor_code", F.lit(None).cast("string"))
    else:
        flavor = flavor_by_service_movie.select(
            F.col("service_movie_id").cast("long"),
            F.col("representative_flavor_code").cast("string"),
        )
        result = result.join(flavor, "service_movie_id", "left")
    result = result.withColumn("input_snapshot", F.lit(input_snapshot)).cache()
    invalid_ids = result.filter(
        F.col("service_movie_id").isNull() | (F.col("service_movie_id") <= 0)
    )
    if invalid_ids.limit(1).count():
        raise ValueError("service_movie_id must be positive")
    if result.groupBy("service_movie_id").count().filter(F.col("count") != 1).limit(1).count():
        raise ValueError("service_movie_id must be unique")
    _validate_metadata_values(result)
    revision = _revision(
        result,
        [
            "service_movie_id",
            "title",
            "genre_codes",
            "representative_flavor_code",
            "input_snapshot",
        ],
    )
    return result.withColumn("movie_metadata_revision", F.lit(revision))


def publish_movie_metadata(metadata: DataFrame, output_uri: str) -> str:
    """Validate then atomically publish one immutable metadata snapshot.

    ``output_uri`` is the complete versioned destination, never a movable
    ``latest`` alias.  The temporary sibling is removed on every failure so a
    Consumer can only observe a complete, revision-homogeneous directory.
    """
    if not output_uri or output_uri.rstrip("/").lower().endswith("/latest"):
        raise ValueError("movie metadata output URI must be immutable")
    _validate_metadata_values(metadata)
    spark = metadata.sparkSession
    if _path_exists(spark, output_uri):
        raise FileExistsError(f"movie metadata snapshot already exists: {output_uri}")
    revisions = [
        row["movie_metadata_revision"]
        for row in metadata.select("movie_metadata_revision").distinct().collect()
    ]
    if len(revisions) != 1:
        raise ValueError("movie metadata snapshot must contain one revision")
    temporary_uri = f"{output_uri.rstrip('/')}.tmp-{uuid4().hex}"
    try:
        metadata.write.mode("errorifexists").parquet(temporary_uri)
        published = spark.read.parquet(temporary_uri)
        if published.count() == 0:
            raise ValueError("movie metadata snapshot must not be empty")
        published_revisions = [
            row["movie_metadata_revision"]
            for row in published.select("movie_metadata_revision").distinct().collect()
        ]
        if published_revisions != revisions:
            raise ValueError("movie metadata revision changed before publication")
        _publish_directory(spark, temporary_uri, output_uri)
    except Exception:
        _delete_path(spark, temporary_uri)
        raise
    return output_uri
