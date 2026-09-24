"""Exercise both standard entrypoints through artifact loading, without a server."""

import sys
from unittest.mock import Mock

import pytest
from test_deployment import deployment_env
from test_gbt_bundle import bundle, source  # noqa: F401

from feelm_consumer import __main__, gbt_personal_runner, party_main, party_runner
from feelm_consumer.gbt_snapshot import create_snapshot


@pytest.mark.parametrize("role", ["personal", "party"])
def test_deployed_entrypoint_loads_pinned_bundle_and_predicts(monkeypatch, bundle, role):  # noqa: F811
    root, revision = bundle
    env = deployment_env() | {
        "CONSUMER_GBT_BUNDLE_URI": str(root), "CONSUMER_GBT_SERVING_REVISION": revision,
        "CONSUMER_POSTGRES_DSN": "postgresql://fixture/db",
        "CONSUMER_REDIS_URL": "redis://fixture/0", "CONSUMER_KAFKA_BOOTSTRAP_SERVERS": "fixture:9092",
        "PARTY_CONSUMER_REDIS_URL": "redis://fixture/0",
        "PARTY_CONSUMER_KAFKA_BOOTSTRAP_SERVERS": "fixture:9092",
        "PARTY_CONSUMER_KAFKA_GROUP_ID": "fixture-party",
    }
    for key, value in env.items():
        monkeypatch.setenv(key, value)
    monkeypatch.setattr(sys, "argv", ["consumer"])
    monkeypatch.setattr("redis.Redis.from_url", Mock(return_value=Mock()))
    monkeypatch.setattr("confluent_kafka.Consumer", Mock())
    results = []

    def personal_loop(_consumer, store, _connect, personal, **kwargs):
        assert store.namespace == "feelm"  # deployment cannot silently publish shadow results
        snapshot = create_snapshot(user_id=1, generation_id="fixture-gen", serving_revision=revision,
                                   model_version="synthetic-v26", ratings=[(1, 4.0)],
                                   dismissed_movie_ids=(), watched_movie_ids=())
        results.append(personal.calculate(snapshot).state["status"])

    def party_loop(settings, *, gbt_runtime):
        assert settings.model_uri == ""
        result = gbt_runtime.scorer.score([(1, 4.0)], candidate_ids=[2, 3, 4])
        assert result.movie_ids.tolist() == [2, 3, 4]
        results.append(result.status)

    monkeypatch.setattr(gbt_personal_runner, "run_consumer", personal_loop)
    monkeypatch.setattr(party_runner, "run", party_loop)
    if role == "personal":
        __main__.main()
        assert results == ["READY"]
    else:
        party_main.main()
        assert results == ["OK"]
