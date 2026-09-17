"""Dry-run or transactionally load one E5 embedding artifact into PostgreSQL."""

from __future__ import annotations

import argparse
import os

from batch.embedding.e5 import expected_model_version, load_e5_embeddings
from batch.embedding.postgres import load, preflight
from common.spark import create_spark_session
from jobs.batch.als_serving import validate_immutable_artifact_uri


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-uri", required=True)
    parser.add_argument("--embedding-version", required=True)
    parser.add_argument("--model-version", required=True)
    parser.add_argument("--dsn-env", default="EMBEDDING_DATABASE_DSN")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--spark-master")
    arguments = parser.parse_args()
    validate_immutable_artifact_uri(arguments.output_uri, "--output-uri")
    dsn = os.environ.get(arguments.dsn_env)
    if not dsn:
        raise ValueError(f"{arguments.dsn_env} is required and must not be logged")
    try:
        import psycopg
    except ImportError as error:
        raise RuntimeError(
            "PostgreSQL embedding load requires psycopg in the Spark image"
        ) from error
    spark = create_spark_session(arguments.spark_master, "feelm-load-movie-embeddings")
    try:
        embeddings, metadata = load_e5_embeddings(
            spark, arguments.output_uri, arguments.embedding_version
        )
        if arguments.model_version != expected_model_version(metadata["model_revision"]):
            raise ValueError("model_version does not match artifact model_revision")
        with psycopg.connect(dsn) as connection:
            rows = (
                (
                    row["movie_id"],
                    row["tmdb_id"],
                    row["mapping_revision"],
                    row["input_text_sha256"],
                    row["embedding"],
                )
                for row in embeddings.toLocalIterator()
            )
            report = (
                load(connection, rows, arguments.model_version)
                if arguments.apply
                else preflight(connection, rows, arguments.model_version)
            )
        print(
            {
                "apply": arguments.apply,
                "embedding_version": metadata["embedding_version"],
                "model_version": arguments.model_version,
                "rows": report.rows,
                "existing_rows": report.existing_rows,
                "missing_movies": report.missing_movies,
                "other_model_rows": report.other_model_rows,
                "mismatched_movie_ids": report.mismatched_movie_ids,
                "different_vectors": report.different_vectors,
                "outside_rows": report.outside_rows,
            }
        )
    finally:
        spark.stop()


if __name__ == "__main__":
    main()
