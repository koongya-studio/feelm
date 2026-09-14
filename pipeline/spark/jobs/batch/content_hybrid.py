"""CLI for the explicit offline Content/ALS Spark execution unit."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from pyspark.sql import SparkSession

from batch.evaluation.content_hybrid_run import run_local_content_hybrid


def main() -> None:
    parser = argparse.ArgumentParser(description="Run offline Content/ALS hybrid evaluation")
    parser.add_argument("--input-json", required=True)
    parser.add_argument("--output-uri", required=True)
    parser.add_argument("--content-version", required=True)
    parser.add_argument("--als-model-version", required=True)
    parser.add_argument("--mapping-snapshot", required=True)
    parser.add_argument("--reuse-existing-artifact", action="store_true")
    parser.add_argument("--spark-master", default="local[1]")
    parser.add_argument("--chunk-size", type=int, default=100)
    args = parser.parse_args()
    payload = json.loads(Path(args.input_json).read_text(encoding="utf-8"))
    required = {"metadata", "ratings", "movies", "scores"}
    if not isinstance(payload, dict) or required - set(payload):
        raise ValueError("input JSON must contain metadata, ratings, movies, and scores")
    spark = (
        SparkSession.builder.master(args.spark_master).appName("feelm-content-hybrid").getOrCreate()
    )
    try:
        result = run_local_content_hybrid(
            spark,
            metadata=payload["metadata"],
            ratings=payload["ratings"],
            movies=payload["movies"],
            scores=payload["scores"],
            output_uri=args.output_uri,
            content_version=args.content_version,
            als_model_version=args.als_model_version,
            mapping_snapshot=args.mapping_snapshot,
            chunk_size=args.chunk_size,
            reuse_existing_artifact=args.reuse_existing_artifact,
        )
        print(json.dumps({"selection": str(result.selection_path), "coverage": result.coverage}))
    finally:
        spark.stop()


if __name__ == "__main__":
    main()
