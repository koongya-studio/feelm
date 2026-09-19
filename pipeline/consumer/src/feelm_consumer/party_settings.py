from __future__ import annotations

from dataclasses import dataclass
from os import environ

from feelm_consumer.settings import SettingsError, validate_immutable_artifact_uri


@dataclass(frozen=True)
class PartySettings:
    kafka_bootstrap_servers: str
    kafka_topic: str
    kafka_group_id: str
    redis_url: str
    model_uri: str
    mapping_uri: str
    metadata_uri: str
    retry_attempts: int
    retry_backoff_seconds: float
    kafka_poll_timeout_seconds: float
    max_paused_polls: int


def load_party_settings(env: dict[str, str] | None = None) -> PartySettings:
    values = environ if env is None else env
    required = {
        "kafka_bootstrap_servers": "PARTY_CONSUMER_KAFKA_BOOTSTRAP_SERVERS",
        "kafka_group_id": "PARTY_CONSUMER_KAFKA_GROUP_ID",
        "redis_url": "PARTY_CONSUMER_REDIS_URL",
        "model_uri": "PARTY_CONSUMER_MODEL_URI",
        "mapping_uri": "PARTY_CONSUMER_MAPPING_URI",
        "metadata_uri": "PARTY_CONSUMER_METADATA_URI",
    }
    result = {field: values.get(name, "").strip() for field, name in required.items()}
    result["kafka_topic"] = values.get("PARTY_CONSUMER_KAFKA_TOPIC", "feelm.party-refresh.v1").strip()
    missing = [name for field, name in required.items() if not result[field]]
    if not result["kafka_topic"]:
        missing.append("PARTY_CONSUMER_KAFKA_TOPIC")
    if missing:
        raise SettingsError("missing required Party Consumer settings: " + ", ".join(missing))
    for field in ("model_uri", "mapping_uri", "metadata_uri"):
        validate_immutable_artifact_uri(result[field], required[field])

    def positive_int(name: str, default: int, maximum: int) -> int:
        try:
            value = int(values.get(name, str(default)).strip())
        except ValueError as exc:
            raise SettingsError(f"{name} must be an integer") from exc
        if not 1 <= value <= maximum:
            raise SettingsError(f"{name} is outside its allowed range")
        return value

    def positive_float(name: str, default: float, maximum: float) -> float:
        try:
            value = float(values.get(name, str(default)).strip())
        except ValueError as exc:
            raise SettingsError(f"{name} must be a number") from exc
        if not 0 < value <= maximum:
            raise SettingsError(f"{name} is outside its allowed range")
        return value

    return PartySettings(
        **result,
        retry_attempts=positive_int("PARTY_CONSUMER_RETRY_ATTEMPTS", 3, 10),
        retry_backoff_seconds=positive_float("PARTY_CONSUMER_RETRY_BACKOFF_SECONDS", 0.25, 30.0),
        kafka_poll_timeout_seconds=positive_float("PARTY_CONSUMER_KAFKA_POLL_TIMEOUT_SECONDS", 1.0, 30.0),
        max_paused_polls=positive_int("PARTY_CONSUMER_MAX_PAUSED_POLLS", 30, 10_000),
    )
