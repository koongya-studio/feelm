import pytest

from feelm_consumer.party_scoring import PartyMember, score_party_candidates


def member(user_id: int, vector: tuple[float, ...], movies: tuple[int, ...]) -> PartyMember:
    return PartyMember(user_id, vector, movies)


def test_matches_spark_party_mean_fit_v2_golden_cases() -> None:
    result = score_party_candidates(
        (
            member(10, (1.0, 0.0), (1, 2, 3)),
            member(20, (0.0, 1.0), (1, 2, 3)),
        ),
        {1: (3.0, 0.0), 2: (1.0, 1.0), 3: (0.0, 3.0)},
    )

    assert [candidate.movie_id for candidate in result.candidates] == [2, 1, 3]
    middle, first, last = result.candidates
    assert middle.member_fit == ((10, pytest.approx(0.5)), (20, pytest.approx(0.5)))
    assert (middle.party_score, middle.mean_fit, middle.min_fit, middle.max_fit, middle.stddev_fit) == pytest.approx(
        (0.5, 0.5, 0.5, 0.5, 0.0)
    )
    assert first.member_fit == ((10, pytest.approx(5 / 6)), (20, pytest.approx(1 / 6)))
    assert (first.party_score, first.mean_fit, first.min_fit, first.max_fit, first.stddev_fit) == pytest.approx(
        (0.5, 0.5, 1 / 6, 5 / 6, 1 / 3)
    )
    assert last.member_fit == ((10, pytest.approx(1 / 6)), (20, pytest.approx(5 / 6)))
    assert (last.party_score, last.mean_fit, last.min_fit, last.max_fit, last.stddev_fit) == pytest.approx(
        (0.5, 0.5, 1 / 6, 5 / 6, 1 / 3)
    )
    assert result == score_party_candidates(
        (
            member(10, (1.0, 0.0), (1, 2, 3)),
            member(20, (0.0, 1.0), (1, 2, 3)),
        ),
        {3: (0.0, 3.0), 1: (3.0, 0.0), 2: (1.0, 1.0)},
    )


def test_ties_missing_factors_and_empty_vectors_match_spark_policy() -> None:
    tied = score_party_candidates((member(10, (1.0,), (9, 3, 4)),), {9: (1.0,), 3: (1.0,), 4: (-1.0,)})
    fallback = score_party_candidates(
        (member(10, (), (3, 1, 2)), member(20, (1.0,), (2, 3, 1))),
        {1: (1.0,), 2: (2.0,)},
    )

    assert [candidate.movie_id for candidate in tied.candidates] == [3, 9, 4]
    assert [candidate.mean_fit for candidate in tied.candidates] == pytest.approx([2 / 3, 2 / 3, 1 / 6])
    assert fallback.missing_factor_movie_ids == (3,)
    movie_three = next(candidate for candidate in fallback.candidates if candidate.movie_id == 3)
    assert movie_three.member_fit == ((10, pytest.approx(5 / 6)), (20, pytest.approx(0.5)))


def test_top_500_is_deterministic_at_maximum_input_bounds() -> None:
    members = tuple(member(user_id, (), tuple(range(1, 601))) for user_id in range(1, 5))

    forward = score_party_candidates(members, {})
    repeated = score_party_candidates(members, {})

    assert len(forward.candidates) == 500
    assert forward == repeated
    assert [candidate.movie_id for candidate in forward.candidates[:3]] == [1, 2, 3]


@pytest.mark.parametrize(
    ("members", "error"),
    [
        ((member(20, (1.0,), (1,)), member(10, (1.0,), (1,))), "ascending"),
        ((member(10, (float("nan"),), (1,)),), "finite"),
        ((member(10, (), (1, 1)),), "duplicate"),
    ],
)
def test_invalid_party_inputs_are_rejected(members, error) -> None:
    with pytest.raises(ValueError, match=error):
        score_party_candidates(members, {})
