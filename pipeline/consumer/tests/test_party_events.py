import json
from struct import pack

import pytest

from feelm_consumer.party_events import PartyEventValidationError, parse_party_refresh


def payload() -> dict[str, object]:
    return {
        "partyId": 99,
        "inputId": "sha256:" + "a" * 64,
        "members": [
            {"userId": 10, "vector": [1.0], "candidateMovieIds": [100, 200]},
            {"userId": 20, "vector": [], "candidateMovieIds": [200, 300]},
        ],
    }


def test_accepts_direct_payload_with_long_key() -> None:
    result = parse_party_refresh(json.dumps(payload()).encode(), pack(">q", 99))

    assert result.party_id == 99
    assert [member.user_id for member in result.members] == [10, 20]


def test_rejects_common_event_envelope() -> None:
    with pytest.raises(PartyEventValidationError, match="partyId"):
        parse_party_refresh(json.dumps({"payload": payload()}).encode(), pack(">q", 99))


@pytest.mark.parametrize(
    ("mutation", "key", "error"),
    [
        (lambda body: None, pack(">q", 100), "does not match"),
        (lambda body: None, b"99", "8-byte"),
        (lambda body: body.update(inputId="sha256:not-a-digest"), pack(">q", 99), "sha256"),
        (lambda body: body.update(members=list(reversed(body["members"]))), pack(">q", 99), "sorted"),
        (lambda body: body["members"][0].update(candidateMovieIds=[1, 1]), pack(">q", 99), "duplicate"),
        (lambda body: body["members"][0].update(candidateMovieIds=list(range(1, 602))), pack(">q", 99), "600"),
        (lambda body: body["members"][1].update(vector=[1.0, 2.0]), pack(">q", 99), "dimension"),
        (lambda body: body["members"][0].update(vector=[True]), pack(">q", 99), "numbers"),
        (lambda body: body["members"][0].update(vector=[10**400]), pack(">q", 99), "finite"),
        (lambda body: body["members"][0].update(userId=2**63), pack(">q", 99), "signed long"),
        (
            lambda body: body["members"][0].update(candidateMovieIds=[2**63]),
            pack(">q", 99),
            "signed long",
        ),
    ],
)
def test_rejects_invalid_key_order_duplicates_and_bounds(mutation, key, error) -> None:
    body = payload()
    mutation(body)

    with pytest.raises(PartyEventValidationError, match=error):
        parse_party_refresh(json.dumps(body).encode(), key)


def test_rejects_poison_json() -> None:
    with pytest.raises(PartyEventValidationError, match="JSON"):
        parse_party_refresh(b"not-json", pack(">q", 99))
