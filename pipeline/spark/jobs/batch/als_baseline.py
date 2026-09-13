"""Validate inputs for the Spark ALS baseline batch."""

from __future__ import annotations

import logging

from batch.train.baseline import run_baseline
from batch.train.input_snapshot import validate_input_snapshot
from batch.train.inputs import load_training_inputs
from batch.train.settings import AlsJobSettings
from common.spark import create_spark_session

LOGGER = logging.getLogger(__name__)


def main() -> None:
    settings = AlsJobSettings.from_sources()
    ratings_uri, service_movie_map_uri = settings.require_input_uris()
    context = settings.require_run_context() if settings.mode == "run" else None
    spark = create_spark_session(settings.spark_master, settings.spark_app_name)
    active_error = None
    try:
        spark.conf.set("spark.sql.session.timeZone", "UTC")
        inputs = load_training_inputs(spark, ratings_uri, service_movie_map_uri)
        if settings.mode == "run":
            validate_input_snapshot(
                spark,
                input_uri=settings.input_uri,
                manifest_uri=context.input_manifest_uri,
                expected_snapshot=context.input_snapshot,
                inputs=inputs,
            )
            result = run_baseline(spark, inputs, settings)
            print(
                "ALS baseline run completed: "
                f"run_id={result.run_id}, serving_model_version={result.serving_model_version}"
            )
            return
        print(
            "ALS input validation completed: "
            f"ratings={inputs.ratings_count}, "
            f"service_movies={inputs.service_movie_count}, "
            f"matched_service_movies={inputs.matched_service_movie_count}"
        )
    except BaseException as error:
        active_error = error
        raise
    finally:
        try:
            spark.stop()
        except Exception:
            if active_error is None:
                raise
            LOGGER.exception("Spark session cleanup failed while handling the original error")


if __name__ == "__main__":
    main()
