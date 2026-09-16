from feelm_consumer.artifacts import MovieMetadata
from feelm_consumer.ranking import RankedMovie
from feelm_consumer.results import build_results
from feelm_consumer.xai import CandidateReason


def test_results_emit_taste_group_and_empty_discovery_shell() -> None:
    vector, candidates = build_results(
        model_version="als-v1",
        vector=[1.0],
        rating_count=2,
        factor_backed_input_count=1,
        candidates=[RankedMovie(7, 0.5)],
        movie_metadata={7: MovieMetadata("테스트 영화", ("DRAMA",), "FLAVOR_X")},
        reasons={7: CandidateReason("SHARED_GENRE", (3,), ("DRAMA",))},
        generated_at="2026-09-13T00:00:00Z",
        generation_id="generation-1",
    )

    assert [value["candidateVersion"] for value in (vector, candidates)] == [4, 4]
    assert [value["generationId"] for value in (vector, candidates)] == ["generation-1", "generation-1"]
    assert vector["factorBackedInputCount"] == 1
    assert candidates["taste"] == [
        {
            "movieId": 7,
            "score": 0.5,
            "reason": {"code": "SHARED_GENRE", "sourceMovieIds": [3], "matchedGenreCodes": ["DRAMA"]},
        }
    ]
    assert candidates["discovery"] == []
