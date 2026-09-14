"""Publish one immutable v2 SERVING ALS artifact for the Fold-in Consumer.

The mapping and movie-metadata snapshots must already be published and their
revisions are explicit command inputs.  This keeps publication order visible:
snapshots first, model version last.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
from urllib.parse import urlsplit

from batch.train.artifact_metadata import SERVING, AlsArtifactMetadata
from batch.train.artifacts import save_als_artifacts
from batch.train.inputs import load_training_inputs
from batch.train.settings import AlsConfig
from batch.train.training import train_als_model
from common.spark import create_spark_session

MOVABLE_ARTIFACT_ALIASES = frozenset({"latest", "current", "stable", "production", "prod"})


def validate_immutable_artifact_uri(uri: str, argument_name: str) -> str:
    """Reject publication aliases; all serving inputs and outputs need fixed paths."""
    segments = [segment.lower() for segment in urlsplit(uri).path.split("/") if segment]
    if not segments or any(segment in MOVABLE_ARTIFACT_ALIASES for segment in segments):
        raise ValueError(f"{argument_name} must be an immutable versioned artifact URI")
    return uri


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ratings-uri", required=True)
    parser.add_argument("--mapping-uri", required=True)
    parser.add_argument("--movie-metadata-uri", required=True)
    parser.add_argument("--mapping-revision", required=True)
    parser.add_argument("--movie-metadata-revision", required=True)
    parser.add_argument("--input-snapshot", required=True)
    parser.add_argument("--output-uri", required=True)
    parser.add_argument("--model-version", required=True)
    parser.add_argument("--rank", type=int, required=True)
    parser.add_argument("--reg-param", type=float, required=True)
    parser.add_argument("--max-iter", type=int, required=True)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--spark-master")
    arguments = parser.parse_args()
    for name in ("mapping_uri", "movie_metadata_uri", "output_uri"):
        validate_immutable_artifact_uri(getattr(arguments, name), f"--{name.replace('_', '-')}")

    spark = create_spark_session(arguments.spark_master, "feelm-als-serving-v2")
    try:
        mapping_revisions = [
            row["mapping_revision"]
            for row in spark.read.parquet(arguments.mapping_uri)
            .select("mapping_revision")
            .distinct()
            .collect()
        ]
        metadata_revisions = [
            row["movie_metadata_revision"]
            for row in spark.read.parquet(arguments.movie_metadata_uri)
            .select("movie_metadata_revision")
            .distinct()
            .collect()
        ]
        if mapping_revisions != [arguments.mapping_revision]:
            raise ValueError("mapping revision does not match the published mapping snapshot")
        if metadata_revisions != [arguments.movie_metadata_revision]:
            raise ValueError("movie metadata revision does not match the published snapshot")
        inputs = load_training_inputs(spark, arguments.ratings_uri, arguments.mapping_uri)
        config = AlsConfig(arguments.rank, arguments.reg_param, arguments.max_iter)
        model = train_als_model(inputs.ratings, config, arguments.seed)
        metadata = AlsArtifactMetadata(
            model_version=arguments.model_version,
            artifact_stage=SERVING,
            input_snapshot=arguments.input_snapshot,
            input_uri=arguments.ratings_uri,
            trained_at_utc=(
                datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
            ),
            config=config,
            als_seed=arguments.seed,
            source_ratings_count=inputs.ratings_count,
            training_ratings_count=inputs.ratings_count,
            mapping_revision=arguments.mapping_revision,
            movie_metadata_revision=arguments.movie_metadata_revision,
        )
        print(save_als_artifacts(spark, model, arguments.output_uri, metadata))
    finally:
        spark.stop()


if __name__ == "__main__":
    main()
