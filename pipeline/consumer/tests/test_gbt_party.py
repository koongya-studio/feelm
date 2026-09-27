"""747 acceptance: real 626 parity plus explicit party policy boundaries."""

import json
from dataclasses import replace
from types import SimpleNamespace

import numpy as np
import pytest
from test_gbt_bundle import bundle, source  # noqa: F401

from feelm_consumer.gbt.artifacts import load_bundle
from feelm_consumer.gbt.errors import GbtError
from feelm_consumer.gbt.scorer import Scorer
from feelm_consumer.gbt_party import calculate_party, midrank_percentiles, score_party
from feelm_consumer.gbt_party_events import (
    MemberRef,
    PartyRequest,
    canonical,
    parse_request,
    request_hash,
    request_payload,
    validate_request,
)
from feelm_consumer.gbt_snapshot import create_snapshot, decode_snapshot

REVISION = "sha256:" + "a" * 64
MODEL = "synthetic-v26"


def snapshot(uid=1, *, ratings=((1, 4.0),), dismiss=(), watched=(), revision=REVISION):
    return create_snapshot(user_id=uid, generation_id=f"gen-{uid}", serving_revision=revision,
                           model_version=MODEL, ratings=ratings, dismissed_movie_ids=dismiss,
                           watched_movie_ids=watched)


def request_for(snapshots, candidates=None, discovery=None):
    candidates = candidates or [(10, 11, 12)] * len(snapshots)
    discovery = discovery or [()] * len(snapshots)
    members = tuple(MemberRef(s.user_id, s.generation_id, "gbt-input-v1", s.input_hash,
                              tuple(dict.fromkeys((*ids, *other))))
                    for s, ids, other in zip(snapshots, candidates, discovery, strict=True))
    request = PartyRequest(19, REVISION, members, 2, snapshots[0].model_version, snapshots[0].serving_revision)
    return replace(request, input_id=request_hash(request))


class StubScorer:
    def __init__(self, score_maps=None):
        self.bundle = SimpleNamespace(serving_revision=REVISION, model_version=MODEL)
        self.calls = []
        self.features = SimpleNamespace(positions={mid: mid - 10 for mid in (10,11,12)},
                                        movie=np.array([[1.0], [3.0], [3.0]]))
        self.eligible = np.ones(3, dtype=bool)
        self.score_maps = score_maps

    def score(self, ratings, *, dismissed_movie_ids, excluded_movie_ids, candidate_ids):
        self.calls.append((ratings, dismissed_movie_ids, excluded_movie_ids, candidate_ids))
        marker = ratings[0][0] if ratings else min(dismissed_movie_ids)
        raw = self.score_maps[marker] if self.score_maps else dict.fromkeys(candidate_ids, 1.0)
        return SimpleNamespace(status="OK", model_version=MODEL, serving_revision=REVISION,
                               movie_ids=np.array(candidate_ids), scores=np.array([raw[mid] for mid in candidate_ids]))


class Reader:
    def __init__(self, snapshots):
        self.snapshots = {value.user_id: value for value in snapshots}

    def read_snapshot(self, user_id, generation_id, **kwargs):
        value = self.snapshots.get(user_id)
        raw = canonical(value.to_payload()) if value else None
        return decode_snapshot(raw, user_id=user_id, generation_id=generation_id, max_bytes=10**7, **kwargs)


@pytest.mark.parametrize("k", [1, 3, 101])
def test_real_gbt_full_subset_parity_and_all_history(bundle, k):  # noqa: F811
    root, revision = bundle
    scorer = Scorer(load_bundle(root, expected_revision=revision))
    history = tuple((mid, 0.5 + mid % 10 / 2) for mid in range(1, k + 1))
    member = snapshot(ratings=history, dismiss=(130,), watched=(131,), revision=revision)
    empty = snapshot(2, ratings=(), watched=(132,), revision=revision)
    request = request_for((member, empty), [(130, 131, 132, 140, 141), (142, 143, 144)])
    full = scorer.score(member.ratings, dismissed_movie_ids=member.dismissed_movie_ids,
                        excluded_movie_ids=member.watched_movie_ids | empty.watched_movie_ids)
    original_score = scorer.score
    observed = []

    def recording(*args, **kwargs):
        result = original_score(*args, **kwargs)
        observed.append(result)
        return result

    scorer.score = recording
    result = calculate_party(scorer, request, Reader((member, empty)))
    assert len(observed) == 1
    subset = observed[0]
    assert subset.input_count == k + 1
    np.testing.assert_array_equal(subset.scores, full.scores[np.searchsorted(full.movie_ids, subset.movie_ids)])
    assert result.active_member_ids == (1,)
    assert {c.movie_id for c in result.candidates} == {140, 141, 142, 143, 144}
    expected = midrank_percentiles(dict(zip(map(int, subset.movie_ids), map(float, subset.scores), strict=True)))
    assert {c.movie_id: c.mean_fit for c in result.candidates} == expected


def test_common_population_all_member_exclusions_and_actual_first_dismiss():
    first = snapshot(ratings=((1, 0.5),), dismiss=(1, 2), watched=(3,))
    second = snapshot(2, ratings=((4, 2.0),), dismiss=(5,), watched=(6,))
    third = snapshot(3, ratings=(), watched=(7,))
    request = request_for((first, second, third), [(1, 3, 7, 10), (2, 4, 5, 11), (6, 12)], [(11,), (), ()])
    scorer = StubScorer()
    result = score_party(scorer, request, (first, second, third))
    assert result.status == "OK" and result.population_count == 3
    assert result.active_member_ids == (1, 2)
    assert all(call[2] == set(range(1, 8)) and call[3] == (10, 11, 12) for call in scorer.calls)
    assert scorer.calls[0][0] == ((1, 0.5),)
    assert result.candidates[0].member_fit == ((1, 0.5), (2, 0.5))
    assert [c.movie_id for c in result.candidates] == [10, 11, 12]


@pytest.mark.parametrize("kind", ["negative", "dismiss", "mixed_zero", "all_zero", "exhausted"])
def test_zero_and_negative_have_explicit_results(kind):
    if kind == "negative":
        snapshots = (snapshot(ratings=((1, 0.5),)),)
    elif kind == "dismiss":
        snapshots = (snapshot(ratings=(), dismiss=(1,)),)
    elif kind == "mixed_zero":
        snapshots = (snapshot(ratings=()), snapshot(2, ratings=(), dismiss=(1,)))
    elif kind == "all_zero":
        snapshots = (snapshot(ratings=()), snapshot(2, ratings=(), watched=(10,)))
    else:
        snapshots = (snapshot(watched=(10, 11, 12)),)
    scorer = StubScorer()
    result = score_party(scorer, request_for(snapshots), snapshots)
    expected = {"exhausted": "EXHAUSTED"}.get(kind, "OK")
    assert result.status == expected
    assert bool(result.candidates) == (expected == "OK")
    if kind == "mixed_zero":
        assert result.active_member_ids == (2,)
        assert result.candidates[0].mean_fit == 0.5
    if kind == "all_zero":
        assert not scorer.calls and not result.active_member_ids
    if kind == "dismiss":
        assert scorer.calls[0][0] == () and scorer.calls[0][1] == frozenset({1})


def test_midrank_equal_mean_and_tie_breakers():
    assert midrank_percentiles({10: 0.0, 11: 0.0, 12: 10.0}) == {10: 1/3, 11: 1/3, 12: 5/6}
    # Rank patterns: movie10=(1,5,3), movie11=(3,3,3) have the same mean;
    # movie11 wins minFit. Movies12 and13 are identical and use movie ID.
    snapshots = tuple(snapshot(i, ratings=((i, 4.0),)) for i in range(1, 4))
    raw = {1: {10: 1, 11: 3, 12: 5, 13: 5, 14: 2},
           2: {10: 5, 11: 3, 12: 1, 13: 1, 14: 4},
           3: {10: 3, 11: 3, 12: 1, 13: 1, 14: 5}}
    result = score_party(StubScorer(raw), request_for(snapshots, [(10, 11, 12, 13, 14)] * 3), snapshots)
    by_id = {c.movie_id: c for c in result.candidates}
    for mid in raw[1]:
        fits = [midrank_percentiles(raw[uid])[mid] for uid in (1, 2, 3)]
        assert by_id[mid].mean_fit == sum(fits) / 3
    order = [c.movie_id for c in result.candidates]
    assert order.index(11) < order.index(10)
    assert order.index(12) < order.index(13)
    assert sorted(result.candidates, key=lambda c: (-c.mean_fit, -c.min_fit, c.stddev_fit, c.movie_id)) == list(
        result.candidates)


def test_four_member_2400_population_is_normalized_before_top500():
    snapshots = tuple(snapshot(i, ratings=((i, 4.0),)) for i in range(1, 5))
    taste = [tuple(range(100 + i * 600, 600 + i * 600)) for i in range(4)]
    discovery = [tuple(range(600 + i * 600, 700 + i * 600)) for i in range(4)]
    scorer = StubScorer()
    result = score_party(scorer, request_for(snapshots, taste, discovery), snapshots)
    assert result.population_count == 2400 and len(result.candidates) == 500
    assert len(scorer.calls) == 4 and all(len(call[3]) == 2400 for call in scorer.calls)
    assert all(c.mean_fit == 0.5 for c in result.candidates)


def test_stddev_breaks_equal_mean_equal_min_tie():
    snapshots = tuple(snapshot(i, ratings=((i, 4.0),)) for i in range(1, 4))
    raw = {1: {10: 1, 11: 2, 12: 3}, 2: {10: 1, 11: 2, 12: 3}, 3: {10: 3, 11: 1, 12: 2}}
    result = score_party(StubScorer(raw), request_for(snapshots), snapshots)
    by_id = {c.movie_id: c for c in result.candidates}
    assert by_id[10].mean_fit == by_id[11].mean_fit
    assert by_id[10].min_fit == by_id[11].min_fit
    assert by_id[11].stddev_fit < by_id[10].stddev_fit
    assert [c.movie_id for c in result.candidates].index(11) < [c.movie_id for c in result.candidates].index(10)


@pytest.mark.parametrize("attack", ["version", "hash", "key", "member", "generation", "revision", "duplicate",
                                    "extra", "taste_limit", "discovery_limit", "negative_id", "bool_id", "no_members",
                                    "too_many_members", "order", "vector"])
def test_request_validation(attack):
    values = (snapshot(), snapshot(2, ratings=()))
    request = request_for(values)
    payload = request_payload(request)
    key = request.party_id.to_bytes(8, "big", signed=True)
    if attack == "version":
        payload["schemaVersion"] = 3
    elif attack == "hash":
        payload["inputId"] = "sha256:" + "b" * 64
    elif attack == "key":
        key = b"19"
    elif attack == "member":
        payload["members"][1]["userId"] = 1
    elif attack == "generation":
        payload["members"][0]["generationId"] = "../escape"
    elif attack == "revision":
        payload["servingRevision"] = "latest"
    elif attack == "duplicate":
        payload["members"][0]["candidateMovieIds"] = [10, 10]
    elif attack in {"extra", "vector"}:
        payload["members"][0][attack] = []
    elif attack == "taste_limit":
        payload["members"][0]["candidateMovieIds"] = list(range(1, 602))
    elif attack == "discovery_limit":
        payload["members"][0]["candidateMovieIds"] = list(range(1, 702))
    elif attack == "negative_id":
        payload["members"][0]["candidateMovieIds"] = [-1]
    elif attack == "bool_id":
        payload["partyId"] = True
    elif attack == "no_members":
        payload["members"] = []
    elif attack == "too_many_members":
        payload["members"] *= 3
    else:
        payload["members"].reverse()
    with pytest.raises(GbtError):
        parse_request(json.dumps(payload), key)


def test_valid_request_and_invalid_json_boundaries():
    request = request_for((snapshot(),))
    assert parse_request(canonical(request_payload(request)), (19).to_bytes(8, "big")) == request
    for raw in (b"\xff", b"[", b'{"version":1,"version":2}', b" " * (256 * 1024 + 1)):
        with pytest.raises(GbtError):
            parse_request(raw, (19).to_bytes(8, "big"))


@pytest.mark.parametrize("field,value", [("user_id", 2), ("generation_id", "other"),
                                        ("serving_revision", "sha256:" + "b" * 64),
                                        ("model_version", "other"), ("ratings", ((1, 1.0),))])
def test_snapshot_identity_is_verified_even_with_injected_reader(field, value):
    original = snapshot()
    request = request_for((original,))
    with pytest.raises(GbtError, match="PARTY_SNAPSHOT_MISMATCH"):
        score_party(StubScorer(), request, (replace(original, **{field: value}),))


def test_model_snapshot_count_and_scorer_population_errors():
    values = (snapshot(),)
    request = request_for(values)
    with pytest.raises(GbtError, match="PARTY_SNAPSHOT_COUNT"):
        score_party(StubScorer(), request, ())
    changed = replace(request, model_version="other")
    changed = replace(changed, input_id=request_hash(changed))
    validate_request(changed)
    with pytest.raises(GbtError, match="PARTY_MODEL_MISMATCH"):
        score_party(StubScorer(), changed, values)
    scorer = StubScorer()
    scorer.score = lambda *args, **kwargs: SimpleNamespace(
        status="OK", model_version=MODEL, serving_revision=REVISION, movie_ids=[10], scores=[1.0])
    with pytest.raises(GbtError, match="PARTY_POPULATION_MISMATCH"):
        score_party(scorer, request, values)


def test_party_without_inputs_uses_popularity():
    values = (snapshot(ratings=()), snapshot(2, ratings=()))
    scorer = StubScorer()
    result = score_party(scorer, request_for(values), values)
    assert result.recommendation_mode == "POPULARITY"
    assert [c.movie_id for c in result.candidates] == [11, 12, 10]
    assert not scorer.calls
    assert result.candidates[0].to_payload()["meanFit"] is None
    assert result.candidates[0].to_payload()["memberFit"] == []


def test_party_popularity_keeps_exclusions_and_exhaustion():
    values = (snapshot(ratings=(), watched=(10,11,12)),)
    result = score_party(StubScorer(), request_for(values), values)
    assert result.status == "EXHAUSTED" and result.recommendation_mode == "POPULARITY"


@pytest.mark.parametrize("invalid", ["unknown", "ineligible", "nonfinite"])
def test_party_popularity_does_not_mask_invalid_candidates(invalid):
    values = (snapshot(ratings=()),)
    scorer = StubScorer()
    if invalid == "unknown":
        scorer.features.positions.clear()
    elif invalid == "ineligible":
        scorer.eligible[0] = False
    else:
        scorer.features.movie[0,0] = np.nan
    with pytest.raises(GbtError):
        score_party(scorer, request_for(values), values)
