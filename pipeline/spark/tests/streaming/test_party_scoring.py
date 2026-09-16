from __future__ import annotations

import pytest

from streaming.party.scoring import PartyMember, score_party_candidates


def member(user_id: int, vector: tuple[float, ...], movies: tuple[int, ...]) -> PartyMember:
    return PartyMember(user_id, vector, movies)


def test_scores_full_member_movie_matrix_then_ranks_mean_normalized_fit():
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
    assert middle.mean_fit == pytest.approx(0.5)
    assert first.member_fit == ((10, pytest.approx(5 / 6)), (20, pytest.approx(1 / 6)))
    assert last.member_fit == ((10, pytest.approx(1 / 6)), (20, pytest.approx(5 / 6)))


def test_ties_use_midrank_and_candidate_order_is_deterministic():
    result = score_party_candidates(
        (member(10, (1.0,), (9, 3, 4)),),
        {9: (1.0,), 3: (1.0,), 4: (-1.0,)},
    )

    assert [candidate.movie_id for candidate in result.candidates] == [3, 9, 4]
    assert [candidate.mean_fit for candidate in result.candidates] == pytest.approx(
        [2 / 3, 2 / 3, 1 / 6]
    )


def test_missing_item_factor_uses_candidate_rank_and_remains_observable():
    result = score_party_candidates(
        (member(10, (1.0,), (1, 2)),),
        {1: (1.0,)},
    )

    assert {candidate.movie_id for candidate in result.candidates} == {1, 2}
    missing = next(candidate for candidate in result.candidates if candidate.movie_id == 2)
    assert missing.mean_fit == 0.25
    assert result.missing_factor_movie_ids == (2,)


def test_content_only_user_and_movie_are_included_with_rank_fallback():
    result = score_party_candidates(
        (member(10, (), (3, 1, 2)), member(20, (1.0,), (2, 3, 1))),
        {1: (1.0,), 2: (2.0,)},
    )

    assert {candidate.movie_id for candidate in result.candidates} == {1, 2, 3}
    assert result.missing_factor_movie_ids == (3,)
    content = next(candidate for candidate in result.candidates if candidate.movie_id == 3)
    assert content.member_fit == ((10, pytest.approx(5 / 6)), (20, pytest.approx(0.5)))


def test_all_content_only_members_still_produce_candidates():
    result = score_party_candidates((member(10, (), (1, 2)),), {})

    assert [candidate.movie_id for candidate in result.candidates] == [1, 2]
    assert result.missing_factor_movie_ids == (1, 2)


@pytest.mark.parametrize(
    "members, factors, error",
    [
        ((member(20, (1.0,), (1,)), member(10, (1.0,), (1,))), {1: (1.0,)}, "ascending"),
        ((member(10, (1.0,), (1,)), member(20, (1.0, 2.0), (1,))), {1: (1.0,)}, "dimension"),
        ((member(10, (float("nan"),), (1,)),), {1: (1.0,)}, "finite"),
        ((member(10, (1.0,), (1,)),), {1: (1.0, 2.0)}, "dimension"),
        ((member(10, (), (1, 1)),), {}, "duplicate"),
    ],
)
def test_invalid_snapshot_or_factor_is_rejected(members, factors, error):
    with pytest.raises(ValueError, match=error):
        score_party_candidates(members, factors)
