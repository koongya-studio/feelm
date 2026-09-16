from __future__ import annotations

import json

import pytest

from streaming.party.redis_store import build_payload, party_candidates_key, publish
from streaming.party.scoring import PartyMember, score_party_candidates


class RecordingRedis:
    def __init__(self) -> None:
        self.values: dict[str, str] = {}

    def set(self, name: str, value: str) -> None:
        self.values[name] = value


def member(user_id: int) -> PartyMember:
    return PartyMember(user_id, (1.0,), (1,))


def test_payload_echoes_input_identity_and_serializes_metrics():
    members = (member(10), member(20))
    result = score_party_candidates(members, {1: (1.0,)})

    payload = build_payload(
        party_id=99,
        model_version="als-v1",
        input_id="snapshot-99-a",
        members=members,
        result=result,
        generated_at="2026-09-16T00:00:00Z",
    )

    assert payload["inputId"] == "snapshot-99-a"
    assert payload["candidates"] == [
        {
            "movieId": 1,
            "partyScore": 0.5,
            "meanFit": 0.5,
            "minFit": 0.5,
            "maxFit": 0.5,
            "stddevFit": 0.0,
            "memberFit": [{"userId": 10, "score": 0.5}, {"userId": 20, "score": 0.5}],
        }
    ]


def test_publish_replaces_one_complete_json_value():
    redis = RecordingRedis()
    payload = build_payload(
        party_id=99,
        model_version="als-v1",
        input_id="snapshot-99-a",
        members=(member(10),),
        result=score_party_candidates((member(10),), {1: (1.0,)}),
    )

    publish(redis, 99, payload)

    assert json.loads(redis.values[party_candidates_key(99)]) == payload


@pytest.mark.parametrize("party_id", [0, -1])
def test_party_key_requires_positive_party_id(party_id):
    with pytest.raises(ValueError, match="positive"):
        party_candidates_key(party_id)


def test_publish_rejects_payload_for_different_party():
    with pytest.raises(ValueError, match="does not match"):
        publish(RecordingRedis(), 99, {"partyId": 100})
