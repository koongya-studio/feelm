from __future__ import annotations

import json

import pytest

from streaming.party.processor import parse_party_refresh, process_party_refresh
from streaming.party.redis_store import party_candidates_key


class RecordingRedis:
    def __init__(self) -> None:
        self.values: dict[str, str] = {}

    def set(self, name: str, value: str) -> None:
        self.values[name] = value


def payload() -> dict[str, object]:
    return {
        "partyId": 99,
        "inputId": "snapshot-99-a",
        "members": [
            {
                "userId": 10,
                "vector": [1.0, 0.0],
                "candidateMovieIds": [100, 200],
            },
            {
                "userId": 20,
                "vector": [0.0, 1.0],
                "candidateMovieIds": [100, 300],
            },
        ],
    }


def test_processes_service_id_candidates_and_publishes_a_complete_snapshot():
    refresh = parse_party_refresh(payload())
    redis = RecordingRedis()

    result = process_party_refresh(
        refresh,
        {100: 1, 200: 2},
        {1: (1.0, 1.0), 2: (2.0, 0.0)},
        redis,
        model_version="als-v1",
    )

    assert {candidate["movieId"] for candidate in result["candidates"]} == {100, 200, 300}
    assert result["fallback"] == {
        "missingItemFactorMovieIds": [300],
        "emptyVectorUserIds": [],
    }
    assert json.loads(redis.values[party_candidates_key(99)]) == result
    assert result["inputId"] == "snapshot-99-a"
    assert result["modelVersion"] == "als-v1"


@pytest.mark.parametrize(
    "mutation, error",
    [
        (lambda body: body.update(partyId=0), "partyId"),
        (lambda body: body.update(inputId=""), "inputId"),
        (lambda body: body.update(members=[]), "party must contain"),
        (lambda body: body["members"][0].update(userId="10"), "member.userId"),
        (lambda body: body["members"][0].update(vector=""), "member.vector"),
    ],
)
def test_rejects_invalid_refresh_payload(mutation, error):
    body = payload()
    mutation(body)

    with pytest.raises(ValueError, match=error):
        parse_party_refresh(body)
