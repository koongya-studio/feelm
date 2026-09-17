"""Create one immutable multilingual-e5-base movie-embedding artifact."""

from __future__ import annotations

import argparse
import json

from batch.embedding.e5 import (
    E5Config,
    generate_e5_embeddings,
    load_e5_embeddings,
    save_e5_embeddings,
)
from batch.embedding.source import (
    build_embedding_source,
    read_raw_embedding_keywords,
    read_raw_embedding_movies,
)
from common.spark import create_spark_session
from jobs.batch.als_serving import validate_immutable_artifact_uri


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--movies-raw-uri", required=True)
    parser.add_argument("--keywords-raw-uri", required=True)
    parser.add_argument("--mapping-uri", required=True)
    parser.add_argument("--mapping-revision", required=True)
    parser.add_argument("--output-uri", required=True)
    parser.add_argument("--embedding-version", required=True)
    parser.add_argument("--model-revision", required=True)
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--max-length", type=int, default=512)
    parser.add_argument("--device", choices=("auto", "cpu", "cuda"), default="auto")
    parser.add_argument("--partitions", type=int, default=4)
    parser.add_argument("--spark-master")
    arguments = parser.parse_args()
    for name in ("mapping_uri", "output_uri"):
        validate_immutable_artifact_uri(getattr(arguments, name), f"--{name.replace('_', '-')}")
    config = E5Config(
        revision=arguments.model_revision,
        batch_size=arguments.batch_size,
        max_length=arguments.max_length,
        device=arguments.device,
        partitions=arguments.partitions,
    )
    spark = create_spark_session(arguments.spark_master, "feelm-movie-embeddings")
    try:
        mapping = spark.read.parquet(arguments.mapping_uri)
        revisions = [
            row["mapping_revision"]
            for row in mapping.select("mapping_revision").distinct().collect()
        ]
        if revisions != [arguments.mapping_revision]:
            raise ValueError("mapping revision does not match the published mapping snapshot")
        source = build_embedding_source(
            read_raw_embedding_movies(spark, arguments.movies_raw_uri),
            read_raw_embedding_keywords(spark, arguments.keywords_raw_uri),
            mapping,
        )
        embeddings = generate_e5_embeddings(source, config)
        try:
            artifact = save_e5_embeddings(
                spark,
                embeddings,
                arguments.output_uri,
                arguments.embedding_version,
                config,
            )
            _, metadata = load_e5_embeddings(
                spark, arguments.output_uri, arguments.embedding_version
            )
        finally:
            embeddings.unpersist(blocking=False)
        print(
            json.dumps(
                {
                    "version_uri": artifact,
                    "embedding_version": arguments.embedding_version,
                    "model_revision": arguments.model_revision,
                    "rows": metadata["row_count"],
                }
            )
        )
    finally:
        spark.stop()


if __name__ == "__main__":
    main()
