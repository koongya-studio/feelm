import json

import pytest

from streaming.trending.redis_store import (
    CURRENT_KEY,
    CURRENT_TTL_SECONDS,
    LAST_GOOD_KEY,
    publish,
    read_snapshot,
)


class Pipeline:
    def __init__(self, redis):
        self.redis = redis
        self.commands = []

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def set(self, key, value, *, ex=None):
        self.commands.append((key, json.loads(value), ex))
        return self

    def execute(self):
        for key, value, _ in self.commands:
            self.redis.values[key] = json.dumps(value)


class Redis:
    def __init__(self):
        self.values = {}

    def get(self, key):
        return self.values.get(key)

    def pipeline(self, *, transaction):
        assert transaction is True
        return Pipeline(self)


def test_publishes_nonempty_snapshot_with_retained_last_good():
    redis = Redis()
    payload = {"schemaVersion": 1, "items": [{"movieId": 550}]}
    publish(redis, payload)
    assert json.loads(redis.values[CURRENT_KEY]) == payload
    assert json.loads(redis.values[LAST_GOOD_KEY]) == payload

    pipe = Pipeline(redis)
    pipe.set(CURRENT_KEY, json.dumps(payload), ex=CURRENT_TTL_SECONDS)
    pipe.set(LAST_GOOD_KEY, json.dumps(payload))
    assert [command[2] for command in pipe.commands] == [900, None]


def test_empty_snapshot_cannot_replace_last_good():
    redis = Redis()
    with pytest.raises(ValueError, match="nonempty"):
        publish(redis, {"schemaVersion": 1, "items": []})
    assert redis.values == {}


def test_read_snapshot_falls_back_to_last_good():
    redis = Redis()
    redis.values[CURRENT_KEY] = '{"schemaVersion":1,"items":[]}'
    redis.values[LAST_GOOD_KEY] = '{"schemaVersion":1,"items":[{"movieId":550}]}'
    assert read_snapshot(redis) == {"schemaVersion": 1, "items": [{"movieId": 550}]}
