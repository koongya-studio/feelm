from feelm_consumer.discovery import DiscoveryContributor, DiscoveryReason
from feelm_consumer.ranking import RankedMovie
from feelm_consumer.results import build_results
from feelm_consumer.xai import CandidateReason


def test_results_emit_taste_group_and_empty_discovery_shell() -> None:
    vector, candidates = build_results(
        model_version="als-v1",
        vector=[1.0],
        rating_count=2,
        factor_backed_input_count=1,
        candidates=[RankedMovie(7, 4.32)],
        reasons={7: CandidateReason("SHARED_GENRE", (3,), ("DRAMA",))},
        generated_at="2026-09-13T00:00:00Z",
        generation_id="generation-1",
    )

    assert [value["candidateVersion"] for value in (vector, candidates)] == [6, 6]
    assert [value["generationId"] for value in (vector, candidates)] == ["generation-1", "generation-1"]
    assert vector["factorBackedInputCount"] == 1
    assert candidates["taste"] == [
        {
            "movieId": 7,
            "score": 4.32,
            "reasons": [
                {"code": "SHARED_GENRE", "sourceMovieIds": [3], "matchedGenreCodes": ["DRAMA"]}
            ],
        }
    ]
    assert candidates["discovery"] == []


def test_results_emit_discovery_with_aggregate_affinity_reason() -> None:
    _, candidates = build_results(
        model_version="als-v1",
        vector=[1.0],
        rating_count=10,
        factor_backed_input_count=8,
        candidates=[RankedMovie(7, 4.32)],
        discovery_candidates=[RankedMovie(8, 3.91)],
        reasons={
            7: CandidateReason("TOP_RATED_SOURCE", (3,), ()),
        },
        discovery_reasons={
            8: DiscoveryReason(
                direction_source="RATING",
                contributor_count=4,
                top_contributors=(
                    DiscoveryContributor(4, "POSITIVE_ALIGNMENT"),
                    DiscoveryContributor(5, "NEGATIVE_CONTRAST"),
                ),
                top_contribution_coverage=0.75,
                viewed_count_in_group=0,
                affinity_rank=2,
                popularity_rank_in_group=1,
            )
        },
        generated_at="2026-09-15T00:00:00Z",
    )

    assert candidates["taste"] == [
        {
            "movieId": 7,
            "score": 4.32,
            "reasons": [
                {"code": "TOP_RATED_SOURCE", "sourceMovieIds": [3], "matchedGenreCodes": []}
            ],
        }
    ]
    assert candidates["discovery"] == [
        {
            "movieId": 8,
            "score": 3.91,
            "reasons": [
                {
                    "code": "DISCOVERY_AGGREGATE_AFFINITY",
                    "directionSource": "RATING",
                    "contributorCount": 4,
                    "topContributors": [
                        {"movieId": 4, "relation": "POSITIVE_ALIGNMENT"},
                        {"movieId": 5, "relation": "NEGATIVE_CONTRAST"},
                    ],
                    "topContributionCoverage": 0.75,
                    "viewedCountInGroup": 0,
                    "affinityRank": 2,
                    "popularityRankInGroup": 1,
                }
            ],
        }
    ]
    assert "discoveryTrace" not in candidates


def test_results_emit_empty_reasons_when_candidate_has_no_evidence() -> None:
    _, candidates = build_results(
        model_version="als-v1",
        vector=[],
        rating_count=0,
        factor_backed_input_count=0,
        candidates=[RankedMovie(7, 0.0)],
        discovery_candidates=[RankedMovie(8, 0.0)],
        reasons={7: CandidateReason("TOP_RATED_SOURCE", (), ())},
        discovery_reasons={8: CandidateReason("DISCOVERY_PERSONAL_SCORE", (), ())},
    )

    assert candidates["taste"] == [{"movieId": 7, "score": 0.0, "reasons": []}]
    assert candidates["discovery"] == [{"movieId": 8, "score": 0.0, "reasons": []}]
