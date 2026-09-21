from datetime import UTC
from struct import pack

import pytest

from feelm_consumer.events import EventValidationError, parse_event
from feelm_consumer.settings import SettingsError, load_settings


def test_parse_event_accepts_matching_utc_key() -> None:
    event = parse_event(
        b'{"eventId":"123e4567-e89b-12d3-a456-426614174000","userId":7,"eventType":"RATING","occurredAt":"2026-09-13T00:00:00Z","payload":{"action":"CREATE","movieId":1,"score":5.0}}',
        pack(">q", 7),
    )
    assert event.user_id == 7
    assert event.occurred_at.tzinfo == UTC


def test_parse_event_accepts_onboarding_with_long_key() -> None:
    event = parse_event(
        b'{"eventId":"e30a81d7-e228-4cd8-a39f-cc1bb9e278f8","userId":7,"eventType":"ONBOARDING","occurredAt":"2026-09-17T14:14:36.563314658Z","payload":{"preferenceCount":7,"ratings":[{"movieId":1,"score":4.0}]}}',
        pack(">q", 7),
    )
    assert event.user_id == 7
    assert event.action == "ONBOARDING"


def test_parse_event_rejects_mismatched_key() -> None:
    with pytest.raises(EventValidationError):
        parse_event(
            b'{"eventId":"123e4567-e89b-12d3-a456-426614174000","userId":7,"eventType":"RATING","occurredAt":"2026-09-13T00:00:00Z","payload":{"action":"CREATE","movieId":1,"score":5.0}}',
            pack(">q", 8),
        )


@pytest.mark.parametrize("key", [None, b"", b"7", b"\x00" * 7, b"\x00" * 9])
def test_parse_event_rejects_missing_or_non_long_key(key: bytes | None) -> None:
    with pytest.raises(EventValidationError, match="invalid partition key"):
        parse_event(
            b'{"eventId":"123e4567-e89b-12d3-a456-426614174000","userId":7,"eventType":"RATING","occurredAt":"2026-09-13T00:00:00Z","payload":{"action":"CREATE","movieId":1,"score":5.0}}',
            key,
        )


def test_parse_event_rejects_invalid_half_step_score() -> None:
    with pytest.raises(EventValidationError):
        parse_event(
            b'{"eventId":"123e4567-e89b-12d3-a456-426614174000","userId":7,"eventType":"RATING","occurredAt":"2026-09-13T00:00:00Z","payload":{"action":"UPDATE","movieId":1,"score":4.2}}',
            pack(">q", 7),
        )


def test_parse_event_rejects_empty_onboarding_submission() -> None:
    with pytest.raises(EventValidationError):
        parse_event(
            b'{"eventId":"123e4567-e89b-12d3-a456-426614174000","userId":7,"eventType":"ONBOARDING","occurredAt":"2026-09-13T00:00:00Z","payload":{"preferenceCount":0}}',
            pack(">q", 7),
        )


def test_settings_reports_names_but_not_values() -> None:
    with pytest.raises(SettingsError, match="CONSUMER_REDIS_URL") as error:
        load_settings({"CONSUMER_KAFKA_BOOTSTRAP_SERVERS": "broker"})
    assert "broker" not in str(error.value)


def test_settings_rejects_unbounded_retry_configuration() -> None:
    with pytest.raises(SettingsError, match="CONSUMER_RETRY_ATTEMPTS"):
        load_settings({
            "CONSUMER_KAFKA_BOOTSTRAP_SERVERS": "broker",
            "CONSUMER_KAFKA_GROUP_ID": "group",
            "CONSUMER_POSTGRES_DSN": "secret",
            "CONSUMER_REDIS_URL": "secret",
            "CONSUMER_MODEL_URI": "model",
            "CONSUMER_MAPPING_URI": "mapping",
            "CONSUMER_METADATA_URI": "metadata",
            "CONSUMER_RETRY_ATTEMPTS": "0",
        })


def test_settings_rejects_unbounded_paused_partition_restart_configuration() -> None:
    with pytest.raises(SettingsError, match="CONSUMER_MAX_PAUSED_POLLS"):
        load_settings({
            "CONSUMER_KAFKA_BOOTSTRAP_SERVERS": "broker",
            "CONSUMER_KAFKA_GROUP_ID": "group",
            "CONSUMER_POSTGRES_DSN": "secret",
            "CONSUMER_REDIS_URL": "secret",
            "CONSUMER_MODEL_URI": "model",
            "CONSUMER_MAPPING_URI": "mapping",
            "CONSUMER_METADATA_URI": "metadata",
            "CONSUMER_MAX_PAUSED_POLLS": "0",
        })


@pytest.mark.parametrize("alias", ["latest", "current"])
def test_settings_rejects_movable_artifact_aliases(alias: str) -> None:
    with pytest.raises(SettingsError, match="immutable versioned artifact URI"):
        load_settings({
            "CONSUMER_KAFKA_BOOTSTRAP_SERVERS": "broker",
            "CONSUMER_KAFKA_GROUP_ID": "group",
            "CONSUMER_POSTGRES_DSN": "secret",
            "CONSUMER_REDIS_URL": "secret",
            "CONSUMER_MODEL_URI": f"hdfs://namenode/artifacts/models/{alias}",
            "CONSUMER_MAPPING_URI": "hdfs://namenode/artifacts/mapping/snapshot-1",
            "CONSUMER_METADATA_URI": "hdfs://namenode/artifacts/metadata/snapshot-1",
        })
