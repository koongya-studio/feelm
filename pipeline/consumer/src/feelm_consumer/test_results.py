from feelm_consumer.artifacts import MovieMetadata
from feelm_consumer.ranking import RankedMovie
from feelm_consumer.results import build_results


def test_results_share_version_and_emit_only_taste() -> None:
    vector, candidates = build_results(
        model_version="als-v1",
        vector=[1.0],
        rating_count=2,
        factor_backed_input_count=1,
        candidates=[RankedMovie(7, 0.5)],
        candidate_version=3,
        movie_metadata={7: MovieMetadata("테스트 영화", ("DRAMA",), "FLAVOR_X")},
        generated_at="2026-09-13T00:00:00Z",
    )

    assert [value["candidateVersion"] for value in (vector, candidates)] == [3, 3]
    assert vector["factorBackedInputCount"] == 1
    assert candidates["items"] == [
        {
            "movieId": 7,
            "type": "TASTE",
            "score": 0.5,
            "flavorCode": "FLAVOR_X",
        }
    ]
