import numpy as np

from feelm_consumer.artifacts import ArtifactBundle
from feelm_consumer.party_events import PartyRefresh
from feelm_consumer.party_scoring import PartyMember
from feelm_consumer.party_service import calculate_party_result


def bundle() -> ArtifactBundle:
    return ArtifactBundle(
        model_version="als-v1",
        input_snapshot="snapshot-a",
        reg_param=0.1,
        factors={1: np.asarray([1.0, 1.0]), 2: np.asarray([2.0, 0.0])},
        service_to_movielens={100: 1, 200: 2},
        movie_metadata={},
        mapping_revision="sha256:" + "a" * 64,
        movie_metadata_revision="sha256:" + "b" * 64,
    )


def test_builds_spring_compatible_complete_result() -> None:
    refresh = PartyRefresh(
        99,
        "sha256:party-input",
        (
            PartyMember(10, (1.0, 0.0), (100, 200)),
            PartyMember(20, (0.0, 1.0), (100, 300)),
        ),
    )

    result = calculate_party_result(bundle(), refresh, generated_at="2026-09-18T00:00:00Z")

    assert result["partyId"] == 99
    assert result["modelVersion"] == "als-v1"
    assert result["algorithmVersion"] == "party-mean-fit-v2"
    assert result["inputId"] == "sha256:party-input"
    assert {candidate["movieId"] for candidate in result["candidates"]} == {100, 200, 300}
    assert result["fallback"] == {"missingItemFactorMovieIds": [300], "emptyVectorUserIds": []}
