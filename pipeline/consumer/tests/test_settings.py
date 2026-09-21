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
    assert settings.group_uri is None
    assert settings.discovery_popularity_uri is None
    assert settings.hybrid_alpha == 0.5
    assert settings.dismiss_kafka_topic == "feelm.dismiss.v1"
    assert settings.dismiss_score == 1.0
    assert settings.dismiss_input_version == "dismiss-virtual-rating-v1"


def test_dismiss_policy_settings_are_versioned_and_configurable() -> None:
    settings = load_settings(
        {
            **BASE,
            "CONSUMER_DISMISS_KAFKA_TOPIC": "custom.dismiss.v1",
            "CONSUMER_DISMISS_SCORE": "1.5",
            "CONSUMER_DISMISS_INPUT_VERSION": "dismiss-v2",
        }
    )

    assert settings.dismiss_kafka_topic == "custom.dismiss.v1"
    assert settings.dismiss_score == 1.5
    assert settings.dismiss_input_version == "dismiss-v2"


@pytest.mark.parametrize("score", ["0", "0.7", "5.5", "nan", "inf", "낮게"])
def test_invalid_dismiss_score_is_rejected(score: str) -> None:
    with pytest.raises(SettingsError, match="CONSUMER_DISMISS_SCORE"):
        load_settings({**BASE, "CONSUMER_DISMISS_SCORE": score})


@pytest.mark.parametrize("version", ["", "UPPERCASE", "space version", "../unsafe"])
def test_invalid_dismiss_input_version_is_rejected(version: str) -> None:
    with pytest.raises(SettingsError, match="CONSUMER_DISMISS_INPUT_VERSION"):
        load_settings({**BASE, "CONSUMER_DISMISS_INPUT_VERSION": version})


def test_rating_and_dismiss_topics_must_be_distinct() -> None:
    with pytest.raises(SettingsError, match="must be different"):
        load_settings({**BASE, "CONSUMER_DISMISS_KAFKA_TOPIC": "feelm.rating.v1"})


def test_legacy_minimum_input_setting_does_not_restore_the_gate() -> None:
    settings = load_settings({**BASE, "CONSUMER_MIN_INPUT_COUNT": "10"})

    assert not hasattr(settings, "min_input_count")


def test_content_uri_and_alpha_are_read() -> None:
    settings = load_settings(
        {**BASE, "CONSUMER_CONTENT_URI": "hdfs://namenode/artifacts/content/content-v1", "CONSUMER_HYBRID_ALPHA": "0.25"}
    )

    assert settings.content_uri == "hdfs://namenode/artifacts/content/content-v1"
    assert settings.hybrid_alpha == 0.25


def test_movable_content_uri_is_rejected() -> None:
    with pytest.raises(SettingsError, match="immutable versioned artifact URI"):
        load_settings({**BASE, "CONSUMER_CONTENT_URI": "hdfs://namenode/artifacts/content/latest"})


def test_group_uri_is_optional_and_must_be_immutable() -> None:
    settings = load_settings(
        {
            **BASE,
            "CONSUMER_GROUP_URI": "hdfs://namenode/artifacts/groups/groups-v1",
            "CONSUMER_DISCOVERY_POPULARITY_URI": "hdfs://namenode/artifacts/discovery-popularity/v1",
        }
    )

    assert settings.group_uri == "hdfs://namenode/artifacts/groups/groups-v1"
    assert settings.discovery_popularity_uri == "hdfs://namenode/artifacts/discovery-popularity/v1"
    with pytest.raises(SettingsError, match="immutable versioned artifact URI"):
        load_settings(
            {
                **BASE,
                "CONSUMER_GROUP_URI": "hdfs://namenode/artifacts/groups/latest",
                "CONSUMER_DISCOVERY_POPULARITY_URI": "hdfs://namenode/artifacts/discovery-popularity/v1",
            }
        )


@pytest.mark.parametrize(
    "values",
    [
        {"CONSUMER_GROUP_URI": "hdfs://namenode/artifacts/groups/groups-v1"},
        {"CONSUMER_DISCOVERY_POPULARITY_URI": "hdfs://namenode/artifacts/discovery-popularity/v1"},
    ],
)
def test_group_and_discovery_popularity_must_be_configured_together(values: dict[str, str]) -> None:
    with pytest.raises(SettingsError, match="must be configured together"):
        load_settings({**BASE, **values})


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
