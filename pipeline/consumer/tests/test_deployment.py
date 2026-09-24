import sys
from unittest.mock import Mock

import pytest

from feelm_consumer import __main__ as personal_entry
from feelm_consumer import gbt_party_runner, gbt_personal_runner, party_main
from feelm_consumer.deployment import gbt_deployment_args


def deployment_env():
    return {
        "CONSUMER_RUNTIME": "gbt",
        "CONSUMER_GBT_BUNDLE_URI": "/fixtures/bundle",
        "CONSUMER_GBT_SERVING_REVISION": "sha256:" + "a" * 64,
        "CONSUMER_GBT_MAX_SNAPSHOT_BYTES": "1048576",
        "CONSUMER_GBT_SNAPSHOT_RETENTION_SECONDS": "3600",
        "CONSUMER_KAFKA_GROUP_ID": "fixture-personal",
    }


@pytest.mark.parametrize("role", ["personal", "party"])
def test_legacy_default_and_explicit_mode(role):
    assert gbt_deployment_args(role, {}) is None
    assert gbt_deployment_args(role, {"CONSUMER_RUNTIME": "legacy"}) is None


@pytest.mark.parametrize("entry,runner,role,settings", [
    (personal_entry, gbt_personal_runner, "personal", "load_settings"),
    (party_main, gbt_party_runner, "party", "load_party_settings"),
])
def test_default_container_commands_dispatch_to_gbt_without_als(monkeypatch, entry, runner, role, settings):
    env = deployment_env()
    for key, value in env.items():
        monkeypatch.setenv(key, value)
    monkeypatch.setattr(sys, "argv", ["consumer"])
    call = Mock()
    monkeypatch.setattr(runner, "main", call)
    monkeypatch.setattr(entry, settings, lambda: pytest.fail("GBT must not read ALS settings"))
    monkeypatch.setattr(entry, "run", lambda _: pytest.fail("GBT must not run ALS"))
    entry.main()
    call.assert_called_once_with(gbt_deployment_args(role, env))
    args = call.call_args.args[0]
    assert ("--publish-live" in args) == (role == "personal")
    assert ("--snapshot-retention-seconds" in args) == (role == "personal")


@pytest.mark.parametrize("name,value", [
    ("CONSUMER_RUNTIME", "gBt"),
    ("CONSUMER_GBT_BUNDLE_URI", ""),
    ("CONSUMER_GBT_SERVING_REVISION", "sha256:bad"),
    ("CONSUMER_GBT_MAX_SNAPSHOT_BYTES", "0"),
    ("CONSUMER_GBT_MAX_SNAPSHOT_BYTES", "67108865"),
    ("CONSUMER_GBT_MAX_SNAPSHOT_BYTES", "1.5"),
    ("CONSUMER_GBT_MAX_SNAPSHOT_BYTES", "9" * 100),
    ("CONSUMER_GBT_SNAPSHOT_RETENTION_SECONDS", "0"),
    ("CONSUMER_GBT_SNAPSHOT_RETENTION_SECONDS", "2147483648"),
    ("CONSUMER_GBT_SNAPSHOT_RETENTION_SECONDS", ""),
])
def test_invalid_deployment_fails_before_transport(monkeypatch, name, value):
    env = deployment_env() | {name: value}
    for key, setting in env.items():
        monkeypatch.setenv(key, setting)
    monkeypatch.setattr(sys, "argv", ["consumer"])
    monkeypatch.setattr(personal_entry, "run", lambda _: pytest.fail("must not fall back"))
    monkeypatch.setattr(gbt_personal_runner, "main", lambda _: pytest.fail("must fail before runner"))
    with pytest.raises(SystemExit, match=name):
        personal_entry.main()


@pytest.mark.parametrize("name", ["CONSUMER_GBT_BUNDLE_URI", "CONSUMER_GBT_SERVING_REVISION",
                                  "CONSUMER_GBT_MAX_SNAPSHOT_BYTES"])
def test_both_roles_require_shared_configuration(name):
    env = deployment_env()
    del env[name]
    for role in ("personal", "party"):
        with pytest.raises(ValueError, match=name):
            gbt_deployment_args(role, env)


def test_discovery_pair_and_errors_do_not_disclose_values():
    env = deployment_env() | {"CONSUMER_GROUP_URI": "hdfs://fixture/groups"}
    with pytest.raises(ValueError, match="must be set together"):
        gbt_deployment_args("personal", env)
    env["CONSUMER_DISCOVERY_POPULARITY_URI"] = "hdfs://fixture/popularity"
    args = gbt_deployment_args("personal", env)
    assert args[-4:] == ["--group-uri", env["CONSUMER_GROUP_URI"],
                         "--popularity-uri", env["CONSUMER_DISCOVERY_POPULARITY_URI"]]
    env["CONSUMER_GBT_BUNDLE_URI"] = "--sensitive-value"
    with pytest.raises(ValueError) as failure:
        gbt_deployment_args("personal", env)
    assert "sensitive-value" not in str(failure.value)


def test_party_needs_no_personal_retention_or_database():
    env = deployment_env()
    del env["CONSUMER_GBT_SNAPSHOT_RETENTION_SECONDS"]
    assert gbt_deployment_args("party", env)


def test_deployment_does_not_mutate_cli_shadow_path(monkeypatch):
    monkeypatch.setenv("CONSUMER_RUNTIME", "gbt")
    monkeypatch.setattr(sys, "argv", ["consumer", "--gbt-bundle", "/fixture"])
    call = Mock()
    monkeypatch.setattr(gbt_personal_runner, "main", call)
    personal_entry.main()
    call.assert_called_once_with()
