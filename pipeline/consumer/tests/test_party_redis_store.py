import json

import pytest

from feelm_consumer.party_redis_store import (
    StalePartyModelError,
    StalePartyPartitionFenceError,
    party_candidates_key,
    publish_party,
)


class RecordingRedis:
    def __init__(self, result: int = 1) -> None:
        self.result = result
        self.call: tuple[object, ...] = ()

    def eval(self, *args: object) -> int:
        self.call = args
        return self.result


def payload() -> dict[str, object]:
    return {
        "partyId": 99,
        "modelVersion": "als-v1",
        "algorithmVersion": "party-mean-fit-v2",
        "inputId": "sha256:input",
        "generatedAt": "2026-09-18T00:00:00Z",
        "candidates": [{"movieId": 1}],
    }


def test_publish_is_one_lua_call_guarded_by_model_and_partition_fence() -> None:
    store = RecordingRedis()

    publish_party(store, 99, "als-v1", payload(), ("fence-key", "fence-token"))

    assert store.call[1:7] == (
        3,
        party_candidates_key(99),
        "feelm:model:current",
        "fence-key",
        "als-v1",
        "fence-token",
    )
    assert json.loads(store.call[7]) == payload()


@pytest.mark.parametrize(
    ("result", "error"),
    [(-1, StalePartyPartitionFenceError), (-2, StalePartyModelError)],
)
def test_publish_rejects_stale_writer_without_fallback_write(result, error) -> None:
    with pytest.raises(error):
        publish_party(RecordingRedis(result), 99, "als-v1", payload(), ("fence", "token"))
