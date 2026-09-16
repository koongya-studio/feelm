from __future__ import annotations

import sys
from types import SimpleNamespace

import pytest

from jobs.streaming import party_candidates
from streaming.party.runtime import redis_writer


class Writer:
    def __init__(self):
        self.pinged = False

    def ping(self):
        self.pinged = True


def test_builds_and_verifies_redis_client(monkeypatch):
    writer = Writer()
    calls = []
    redis = SimpleNamespace(
        Redis=SimpleNamespace(
            from_url=lambda url, **options: calls.append((url, options)) or writer
        )
    )
    monkeypatch.setitem(sys.modules, "redis", redis)

    assert redis_writer("redis://redis:6379/0") is writer
    assert writer.pinged
    assert calls == [
        (
            "redis://redis:6379/0",
            {
                "decode_responses": True,
                "socket_connect_timeout": 5,
                "socket_timeout": 5,
                "health_check_interval": 30,
            },
        )
    ]


def test_requires_redis_url():
    with pytest.raises(ValueError, match="redis_url"):
        redis_writer("")


def test_job_wires_artifacts_redis_and_stream_before_waiting(monkeypatch):
    calls = []
    spark = object()
    loaded = SimpleNamespace(
        model_version="als-v1",
        service_to_model_movie_ids={100: 7},
        model_item_factors={7: (0.1,)},
    )
    query = SimpleNamespace(awaitTermination=lambda: calls.append("await"))
    monkeypatch.setattr(
        party_candidates,
        "create_spark_session",
        lambda master, name: calls.append(("spark", master, name)) or spark,
    )
    monkeypatch.setattr(
        party_candidates,
        "load_party_runtime_artifacts",
        lambda *args, **kwargs: calls.append(("artifacts", args, kwargs)) or loaded,
    )
    monkeypatch.setattr(
        party_candidates,
        "redis_writer",
        lambda url: calls.append(("redis", url)) or "redis-client",
    )
    monkeypatch.setattr(
        party_candidates,
        "party_refresh_handler",
        lambda *args, **kwargs: calls.append(("handler", args, kwargs)) or "message-handler",
    )
    monkeypatch.setattr(
        party_candidates,
        "start_party_refresh_stream",
        lambda *args, **kwargs: calls.append(("stream", args, kwargs)) or query,
    )

    party_candidates.main(
        [
            "--spark-master",
            "local[1]",
            "--kafka-bootstrap-servers",
            "kafka:9092",
            "--checkpoint-uri",
            "hdfs:///checkpoints/party",
            "--redis-url",
            "redis://redis:6379/0",
            "--model-root-uri",
            "hdfs:///models",
            "--model-version",
            "als-v1",
            "--service-movie-map-uri",
            "hdfs:///mapping",
        ]
    )

    assert calls[0] == ("spark", "local[1]", "feelm-party-candidates")
    assert calls[2] == ("redis", "redis://redis:6379/0")
    assert calls[3] == (
        "handler",
        ("als-v1", {100: 7}, {7: (0.1,)}, "redis-client"),
        {},
    )
    assert calls[4][0] == "stream"
    assert calls[-1] == "await"
