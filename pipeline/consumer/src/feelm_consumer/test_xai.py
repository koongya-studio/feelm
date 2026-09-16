from feelm_consumer.artifacts import MovieMetadata
from feelm_consumer.profile import ProfileInput
from feelm_consumer.ranking import RankedMovie
from feelm_consumer.xai import build_candidate_reasons


def test_candidate_reason_uses_highest_rated_shared_genre_sources_deterministically() -> None:
    metadata = {
        1: MovieMetadata("one", ("DRAMA", "THRILLER"), None),
        2: MovieMetadata("two", ("DRAMA",), None),
        3: MovieMetadata("three", ("THRILLER",), None),
    }

    reasons = build_candidate_reasons(
        [RankedMovie(3, 0.9)],
        [ProfileInput(2, 5.0, "rating"), ProfileInput(1, 5.0, "rating")],
        metadata,
    )

    assert reasons[3].payload() == {
        "code": "SHARED_GENRE",
        "sourceMovieIds": [1],
        "matchedGenreCodes": ["THRILLER"],
    }


def test_candidate_reason_keeps_a_top_rated_source_when_genres_do_not_overlap() -> None:
    metadata = {
        1: MovieMetadata("one", ("DRAMA",), None),
        2: MovieMetadata("two", ("ACTION",), None),
    }

    reasons = build_candidate_reasons(
        [RankedMovie(2, 0.9)], [ProfileInput(1, 4.5, "rating")], metadata
    )

    assert reasons[2].payload() == {
        "code": "TOP_RATED_SOURCE",
        "sourceMovieIds": [1],
        "matchedGenreCodes": [],
    }


def test_candidate_reason_uses_onboarding_source_when_no_rating_source_exists() -> None:
    metadata = {
        1: MovieMetadata("onboarding", ("MYSTERY",), None),
        2: MovieMetadata("discovery", ("MYSTERY", "THRILLER"), None),
    }

    reasons = build_candidate_reasons(
        [RankedMovie(2, 0.9)], [ProfileInput(1, 4.271231616529622, "onboarding")], metadata
    )

    assert reasons[2].payload() == {
        "code": "SHARED_GENRE",
        "sourceMovieIds": [1],
        "matchedGenreCodes": ["MYSTERY"],
    }
