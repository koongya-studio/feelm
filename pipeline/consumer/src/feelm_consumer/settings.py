from __future__ import annotations

from dataclasses import dataclass
from math import isfinite
from os import environ
from urllib.parse import unquote, urlsplit


class SettingsError(ValueError):
    pass


MOVABLE_ARTIFACT_ALIASES = frozenset({"latest", "current", "stable", "production", "prod"})


def validate_immutable_artifact_uri(value: str, setting_name: str) -> str:
    """Require an explicit artifact path rather than a movable publication alias."""
    parsed = urlsplit(value)
    segments = [unquote(segment).lower() for segment in parsed.path.split("/") if segment]
    staging = bool(segments and segments[-1].startswith(".") and ".tmp-" in segments[-1])
    if not segments or staging or any(segment in MOVABLE_ARTIFACT_ALIASES for segment in segments):
        raise SettingsError(f"{setting_name} must be an immutable versioned artifact URI")
    return value


@dataclass(frozen=True)
class Settings:
    kafka_bootstrap_servers: str
    kafka_topic: str
    kafka_group_id: str
    postgres_dsn: str
    redis_url: str
    model_uri: str
    mapping_uri: str
    metadata_uri: str
    content_uri: str | None
    hybrid_alpha: float
    min_input_count: int
    retry_attempts: int
    retry_backoff_seconds: float
    kafka_poll_timeout_seconds: float
    max_paused_polls: int


def load_settings(env: dict[str, str] | None = None) -> Settings:
    values = environ if env is None else env
    required = {
        "kafka_bootstrap_servers": "CONSUMER_KAFKA_BOOTSTRAP_SERVERS",
        "kafka_group_id": "CONSUMER_KAFKA_GROUP_ID",
        "postgres_dsn": "CONSUMER_POSTGRES_DSN",
        "redis_url": "CONSUMER_REDIS_URL",
        "model_uri": "CONSUMER_MODEL_URI",
        "mapping_uri": "CONSUMER_MAPPING_URI",
        "metadata_uri": "CONSUMER_METADATA_URI",
    }
    result = {field: values.get(name, "").strip() for field, name in required.items()}
    result["kafka_topic"] = values.get("CONSUMER_KAFKA_TOPIC", "feelm.rating.v1").strip()
    missing = [name for field, name in required.items() if not result[field]]
    if missing:
        raise SettingsError("missing required Consumer settings: " + ", ".join(missing))
    for field in ("model_uri", "mapping_uri", "metadata_uri"):
        validate_immutable_artifact_uri(result[field], required[field])
    # Optional: without it the Consumer keeps its ALS-only behaviour.
    content_uri = values.get("CONSUMER_CONTENT_URI", "").strip() or None
    if content_uri is not None:
        validate_immutable_artifact_uri(content_uri, "CONSUMER_CONTENT_URI")
    def positive_int(name: str, default: int, maximum: int) -> int:
        raw = values.get(name, str(default)).strip()
        try:
            value = int(raw)
        except ValueError as exc:
            raise SettingsError(f"{name} must be an integer") from exc
        if not 1 <= value <= maximum:
            raise SettingsError(f"{name} is outside its allowed range")
        return value

    def positive_float(name: str, default: float, maximum: float) -> float:
        raw = values.get(name, str(default)).strip()
        try:
            value = float(raw)
        except ValueError as exc:
            raise SettingsError(f"{name} must be a number") from exc
        if not 0 < value <= maximum:
            raise SettingsError(f"{name} is outside its allowed range")
        return value

    def bounded_int(name: str, default: int, minimum: int, maximum: int) -> int:
        raw = values.get(name, str(default)).strip()
        try:
            value = int(raw)
        except ValueError as exc:
            raise SettingsError(f"{name} must be an integer") from exc
        if not minimum <= value <= maximum:
            raise SettingsError(f"{name} is outside its allowed range")
        return value

    def unit_float(name: str, default: float) -> float:
        raw = values.get(name, str(default)).strip()
        try:
            value = float(raw)
        except ValueError as exc:
            raise SettingsError(f"{name} must be a number") from exc
        if not isfinite(value) or not 0 <= value <= 1:
            raise SettingsError(f"{name} must be between 0 and 1")
        return value

    return Settings(
        **result,
        content_uri=content_uri,
        # Fixed ALS weight of the hybrid mix.  It only applies to movies that both
        # components support, so it never turns the mix into a single component;
        # clear CONSUMER_CONTENT_URI for that.  See pipeline/consumer/README.md.
        hybrid_alpha=unit_float("CONSUMER_HYBRID_ALPHA", 0.5),
        # Ratings plus onboarding preferences a user needs before candidates are
        # computed at all.  Fold-in from a handful of inputs is noise, and the
        # product does not expose recommendations below this count either.
        min_input_count=bounded_int("CONSUMER_MIN_INPUT_COUNT", 10, 0, 1000),
        retry_attempts=positive_int("CONSUMER_RETRY_ATTEMPTS", 3, 10),
        retry_backoff_seconds=positive_float("CONSUMER_RETRY_BACKOFF_SECONDS", 0.25, 30.0),
        kafka_poll_timeout_seconds=positive_float("CONSUMER_KAFKA_POLL_TIMEOUT_SECONDS", 1.0, 30.0),
        max_paused_polls=positive_int("CONSUMER_MAX_PAUSED_POLLS", 30, 10_000),
    )
