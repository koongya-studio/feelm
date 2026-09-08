import argparse

import pytest

from batch.train.settings import DEFAULT_ALS_CONFIGS, AlsConfig, AlsJobSettings


def test_uses_reproducible_baseline_defaults() -> None:
    settings = AlsJobSettings.from_sources(environ={}, argv=[])

    assert settings.seed == 42
    assert settings.train_ratio == 0.8
    assert settings.validation_ratio == 0.1
    assert settings.test_ratio == pytest.approx(0.1)
    assert settings.positive_rating_threshold == 4.0
    assert settings.candidate_size == 500
    assert settings.als_configs == DEFAULT_ALS_CONFIGS


def test_cli_overrides_paths_and_training_settings() -> None:
    settings = AlsJobSettings.from_sources(
        environ={"RECOMMENDATION_ALS_SEED": "7"},
        argv=[
            "--input-uri",
            "hdfs://cluster/processed/movie-mapping/v1",
            "--output-uri",
            "hdfs://cluster/models/als/test",
            "--seed",
            "11",
            "--candidate-size",
            "100",
            "--als-config",
            "16:0.2:5",
        ],
    )

    ratings_uri, service_map_uri = settings.require_input_uris()
    assert ratings_uri.endswith("/ratings")
    assert service_map_uri.endswith("/service-movie-id-map")
    assert settings.output_uri == "hdfs://cluster/models/als/test"
    assert settings.seed == 11
    assert settings.candidate_size == 100
    assert settings.als_configs == (AlsConfig(16, 0.2, 5),)


def test_cli_value_is_used_before_invalid_environment_value() -> None:
    settings = AlsJobSettings.from_sources(
        environ={"RECOMMENDATION_ALS_SEED": "not-an-int"},
        argv=["--seed", "11"],
    )

    assert settings.seed == 11


def test_rejects_invalid_split_ratios() -> None:
    with pytest.raises(ValueError, match="positive test ratio"):
        AlsJobSettings.from_sources(
            environ={},
            argv=["--train-ratio", "0.9", "--validation-ratio", "0.1"],
        )


def test_rejects_zero_test_ratio_without_float_rounding_error() -> None:
    with pytest.raises(ValueError, match="positive test ratio"):
        AlsJobSettings.from_sources(
            environ={},
            argv=["--train-ratio", "0.7", "--validation-ratio", "0.3"],
        )


def test_accepts_missing_optional_output_uri() -> None:
    settings = AlsJobSettings.from_sources(environ={}, argv=["--input-uri", "/movie-mapping/v1"])

    ratings_uri, service_map_uri = settings.require_input_uris()

    assert ratings_uri == "/movie-mapping/v1/ratings"
    assert service_map_uri == "/movie-mapping/v1/service-movie-id-map"
    assert settings.output_uri is None


def test_requires_input_uri() -> None:
    settings = AlsJobSettings.from_sources(environ={}, argv=["--output-uri", "/models/als/run"])

    with pytest.raises(ValueError, match="input URI is required"):
        settings.require_input_uris()


@pytest.mark.parametrize(
    ("argv", "message"),
    [
        (["--candidate-size", "9"], "at least 10"),
        (["--positive-rating-threshold", "5.5"], "between 0.5 and 5.0"),
    ],
)
def test_rejects_invalid_evaluation_settings(
    argv: list[str],
    message: str,
) -> None:
    with pytest.raises(ValueError, match=message):
        AlsJobSettings.from_sources(environ={}, argv=argv)


def test_rejects_invalid_als_config() -> None:
    with pytest.raises(argparse.ArgumentTypeError, match="rank > 0"):
        AlsJobSettings.from_sources(
            environ={"RECOMMENDATION_ALS_CONFIGS": "0:0.1:10"},
            argv=[],
        )


@pytest.mark.parametrize("reg_param", ["nan", "inf", "-inf"])
def test_rejects_non_finite_als_reg_param(reg_param: str) -> None:
    with pytest.raises(SystemExit):
        AlsJobSettings.from_sources(
            environ={},
            argv=["--als-config", f"64:{reg_param}:10"],
        )
