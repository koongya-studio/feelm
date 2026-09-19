import pytest

from feelm_consumer.party_settings import load_party_settings
from feelm_consumer.settings import SettingsError


def environment() -> dict[str, str]:
    return {
        "PARTY_CONSUMER_KAFKA_BOOTSTRAP_SERVERS": "broker:9092",
        "PARTY_CONSUMER_KAFKA_GROUP_ID": "feelm-party-consumer-v1",
        "PARTY_CONSUMER_REDIS_URL": "redis://secret",
        "PARTY_CONSUMER_MODEL_URI": "hdfs:///models/als-v1",
        "PARTY_CONSUMER_MAPPING_URI": "hdfs:///mapping/revision-a",
        "PARTY_CONSUMER_METADATA_URI": "hdfs:///metadata/revision-a",
    }


def test_uses_separate_prefix_topic_and_group() -> None:
    settings = load_party_settings(environment())

    assert settings.kafka_topic == "feelm.party-refresh.v1"
    assert settings.kafka_group_id == "feelm-party-consumer-v1"
    assert settings.redis_url == "redis://secret"


def test_rejects_missing_or_movable_artifacts_without_reading_consumer_settings() -> None:
    values = environment()
    values["CONSUMER_MODEL_URI"] = "hdfs:///models/unrelated"
    values["PARTY_CONSUMER_MODEL_URI"] = "hdfs:///models/latest"

    with pytest.raises(SettingsError, match="immutable"):
        load_party_settings(values)

    del values["PARTY_CONSUMER_REDIS_URL"]
    with pytest.raises(SettingsError, match="PARTY_CONSUMER_REDIS_URL"):
        load_party_settings(values)
