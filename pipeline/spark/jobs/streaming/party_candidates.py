"""Run the Kafka-to-Redis party candidate Spark Structured Streaming job."""

from __future__ import annotations

import argparse
from collections.abc import Sequence

from common.spark import create_spark_session
from streaming.party.artifacts import load_party_runtime_artifacts
from streaming.party.kafka_stream import party_refresh_handler, start_party_refresh_stream
from streaming.party.runtime import redis_writer


def parse_arguments(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spark-master")
    parser.add_argument("--kafka-bootstrap-servers", required=True)
    parser.add_argument("--kafka-topic", default="feelm.party-refresh.v1")
    parser.add_argument("--max-records-per-trigger", type=int, default=100)
    parser.add_argument("--checkpoint-uri", required=True)
    parser.add_argument("--redis-url", required=True)
    parser.add_argument("--model-root-uri", required=True)
    parser.add_argument("--model-version", required=True)
    parser.add_argument("--service-movie-map-uri", required=True)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> None:
    arguments = parse_arguments(argv)
    spark = create_spark_session(arguments.spark_master, "feelm-party-candidates")
    artifacts = load_party_runtime_artifacts(
        spark,
        model_root_uri=arguments.model_root_uri,
        model_version=arguments.model_version,
        service_movie_map_uri=arguments.service_movie_map_uri,
    )
    redis = redis_writer(arguments.redis_url)
    query = start_party_refresh_stream(
        spark,
        kafka_bootstrap_servers=arguments.kafka_bootstrap_servers,
        kafka_topic=arguments.kafka_topic,
        checkpoint_uri=arguments.checkpoint_uri,
        max_records_per_trigger=arguments.max_records_per_trigger,
        handle_message=party_refresh_handler(
            artifacts.model_version,
            artifacts.service_to_model_movie_ids,
            artifacts.model_item_factors,
            redis,
        ),
    )
    query.awaitTermination()


if __name__ == "__main__":
    main()
