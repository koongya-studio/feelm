# ruff: noqa: E501

import pytest

from feelm_consumer.settings import SettingsError, load_settings

BASE = {
    "CONSUMER_KAFKA_BOOTSTRAP_SERVERS": "broker:9092",
    "CONSUMER_KAFKA_GROUP_ID": "feelm-foldin",
    "CONSUMER_POSTGRES_DSN": "postgresql://example/db",
    "CONSUMER_REDIS_URL": "redis://example:6379/0",
    "CONSUMER_MODEL_URI": "hdfs://namenode/artifacts/als/als-v1",
    "CONSUMER_MAPPING_URI": "hdfs://namenode/artifacts/mapping/v1/service-movie-id-map",
    "CONSUMER_METADATA_URI": "hdfs://namenode/artifacts/movie-metadata/v1",
}


def test_content_settings_are_optional() -> None:
    settings = load_settings(dict(BASE))

    assert settings.content_uri is None
    assert settings.hybrid_alpha == 0.5


def test_content_uri_and_alpha_are_read() -> None:
    settings = load_settings(
        {**BASE, "CONSUMER_CONTENT_URI": "hdfs://namenode/artifacts/content/content-v1", "CONSUMER_HYBRID_ALPHA": "0.25"}
    )

    assert settings.content_uri == "hdfs://namenode/artifacts/content/content-v1"
    assert settings.hybrid_alpha == 0.25


def test_movable_content_uri_is_rejected() -> None:
    with pytest.raises(SettingsError, match="immutable versioned artifact URI"):
        load_settings({**BASE, "CONSUMER_CONTENT_URI": "hdfs://namenode/artifacts/content/latest"})


@pytest.mark.parametrize(
    "uri",
    [
        "hdfs://namenode/artifacts/content/.content-v1.tmp-deadbeef",
        "hdfs://namenode/artifacts/content/%2Econtent-v1.tmp-deadbeef",
    ],
)
def test_mutable_content_staging_uri_is_rejected(uri: str) -> None:
    with pytest.raises(SettingsError, match="immutable versioned artifact URI"):
        load_settings({**BASE, "CONSUMER_CONTENT_URI": uri})


@pytest.mark.parametrize("alpha", ["-0.1", "1.1", "nan", "inf", "많이"])
def test_invalid_alpha_is_rejected(alpha: str) -> None:
    with pytest.raises(SettingsError, match="CONSUMER_HYBRID_ALPHA"):
        load_settings({**BASE, "CONSUMER_HYBRID_ALPHA": alpha})


@pytest.mark.parametrize("alpha", ["0", "1", "0.75"])
def test_alpha_boundaries_are_accepted(alpha: str) -> None:
    assert load_settings({**BASE, "CONSUMER_HYBRID_ALPHA": alpha}).hybrid_alpha == float(alpha)
