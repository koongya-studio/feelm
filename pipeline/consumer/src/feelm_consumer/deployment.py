"""Select the existing GBT entrypoints from the shared deployment environment."""

import os
import re
from collections.abc import Mapping


def gbt_deployment_args(role: str, env: Mapping[str, str] | None = None) -> list[str] | None:
    """GBT means live deployment; standalone CLI retains its shadow default.

    Only setting names appear in errors. This function opens no connections and
    never changes the active model pointer or decides retention/budget values.
    """
    if role not in {"personal", "party"}:
        raise ValueError("invalid consumer role")
    env = os.environ if env is None else env
    mode = env.get("CONSUMER_RUNTIME", "legacy")
    if mode == "legacy":
        return None
    if mode != "gbt":
        raise ValueError("CONSUMER_RUNTIME must be legacy or gbt")

    def required(name: str) -> str:
        value = env.get(name, "").strip()
        if not value or value.startswith("-") or any(ord(char) < 32 for char in value):
            raise ValueError(f"{name} is required and must be a valid argument")
        return value

    def positive(name: str, maximum: int) -> str:
        value = required(name)
        if not value.isascii() or not value.isdecimal() or len(value) > 10 or not 1 <= int(value) <= maximum:
            raise ValueError(f"{name} must be between 1 and {maximum}")
        return value

    bundle = required("CONSUMER_GBT_BUNDLE_URI")
    revision = required("CONSUMER_GBT_SERVING_REVISION")
    if re.fullmatch(r"sha256:[a-f0-9]{64}", revision) is None:
        raise ValueError("CONSUMER_GBT_SERVING_REVISION must pin a manifest SHA256")
    budget = positive("CONSUMER_GBT_MAX_SNAPSHOT_BYTES", 64 * 1024**2)
    args = ["--gbt-bundle", bundle, "--expected-revision", revision, "--max-snapshot-bytes", budget]
    if role == "personal":
        retention = positive("CONSUMER_GBT_SNAPSHOT_RETENTION_SECONDS", 2**31 - 1)
        args += ["--snapshot-retention-seconds", retention, "--publish-live"]
        group = env.get("CONSUMER_GROUP_URI", "").strip()
        popularity = env.get("CONSUMER_DISCOVERY_POPULARITY_URI", "").strip()
        if bool(group) != bool(popularity):
            raise ValueError("CONSUMER_GROUP_URI and CONSUMER_DISCOVERY_POPULARITY_URI must be set together")
        if group:
            args += ["--group-uri", required("CONSUMER_GROUP_URI"),
                     "--popularity-uri", required("CONSUMER_DISCOVERY_POPULARITY_URI")]
    return args
