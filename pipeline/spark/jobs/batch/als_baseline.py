"""Validate inputs for the Spark ALS baseline batch."""

from __future__ import annotations

from batch.train.inputs import load_training_inputs
from batch.train.settings import AlsJobSettings
from common.spark import create_spark_session


def main() -> None:
    settings = AlsJobSettings.from_sources()
    ratings_uri, service_movie_map_uri = settings.require_input_uris()
    spark = create_spark_session(settings.spark_master, settings.spark_app_name)
    spark.conf.set("spark.sql.session.timeZone", "UTC")
    try:
        inputs = load_training_inputs(spark, ratings_uri, service_movie_map_uri)
        print(
            "ALS input validation completed: "
            f"ratings={inputs.ratings_count}, "
            f"service_movies={inputs.service_movie_count}, "
            f"matched_service_movies={inputs.matched_service_movie_count}"
        )
    finally:
        spark.stop()


if __name__ == "__main__":
    main()
