import pytest

from common.settings import JobSettings


def test_settings_use_safe_local_defaults() -> None:
    settings = JobSettings.from_sources(environ={}, argv=[])

    assert settings.spark_master is None
    assert settings.spark_app_name == "feelm-recommendation"
    assert settings.input_uri is None
    assert settings.output_uri is None


def test_cli_values_override_environment() -> None:
    settings = JobSettings.from_sources(
        environ={
            "RECOMMENDATION_SPARK_MASTER": "spark://environment:7077",
            "RECOMMENDATION_INPUT_URI": "hdfs://environment/input",
        },
        argv=[
            "--spark-master",
            "local[1]",
            "--input-uri",
            "file:///tmp/input",
            "--output-uri",
            "file:///tmp/output",
        ],
    )

    assert settings.spark_master == "local[1]"
    assert settings.input_uri == "file:///tmp/input"
    assert settings.output_uri == "file:///tmp/output"


def test_io_uris_are_required_by_io_jobs() -> None:
    settings = JobSettings.from_sources(environ={}, argv=[])

    with pytest.raises(ValueError, match="input and output URIs are required"):
        settings.require_io_uris()
