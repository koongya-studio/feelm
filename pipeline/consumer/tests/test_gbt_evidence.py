"""824 version-bound GBT evidence contract and adversarial validation."""

import copy
import json
from pathlib import Path

import numpy as np
import pytest
import xgboost as xgb
from test_gbt_personal import scorer as scorer
from test_gbt_personal import snapshot

from feelm_consumer.gbt.errors import GbtError
from feelm_consumer.gbt.inputs import merge_inputs
from feelm_consumer.gbt.schema import FEATURES, TRANSFORM
from feelm_consumer.gbt_evidence import (
    EVIDENCE_SCHEMA_VERSION,
    EXPLANATION_VERSION,
    GROUPS,
    RANKING_VERSION,
    AttributionBuilder,
    _candidate_evidence,
    _feature_group,
    _specific_evidence,
    empty_evidence,
    validate_evidence,
)
from feelm_consumer.gbt_personal import PersonalScorer


def test_exact_attribution_keeps_candidate_v6_score_and_order(scorer):
    value = snapshot(scorer, [(1, 5), (2, 1), (4, 3)])
    result = PersonalScorer(scorer).calculate(value, generated_at="2026-09-24T00:00:00Z")
    taste = result.candidates["taste"]
    payload = result.evidence.to_payload()

    assert result.candidates["candidateVersion"] == 6
    assert all(item["reasons"] == [] for item in taste)
    assert [item["movieId"] for item in taste] == [item["movieId"] for item in payload["items"]]
    assert [item["score"] for item in taste] == [item["score"] for item in payload["items"]]

    positions = np.asarray([scorer.features.positions[item["movieId"]] for item in taste], np.int64)
    history = merge_inputs(value.ratings, value.dismissed_movie_ids, value.watched_movie_ids)
    matrix = scorer.features.prepare(history.ratings).matrix(positions)
    contributions = scorer.bundle.booster.predict(
        xgb.DMatrix(matrix), pred_contribs=True, approx_contribs=False, validate_features=False
    )
    np.testing.assert_allclose(
        contributions.sum(axis=1),
        np.asarray([item["score"] for item in taste]),
        rtol=1e-6,
        atol=1e-6,
    )


def test_evidence_versions_groups_and_replay_are_deterministic(scorer):
    first_snapshot = snapshot(scorer, [(5, 0.5), (1, 5)], dismissed=[3], generation="first")
    replay_snapshot = snapshot(scorer, [(1, 5), (5, 0.5)], dismissed=[3], generation="replay")
    first = PersonalScorer(scorer).calculate(first_snapshot).evidence.to_payload()
    replay = PersonalScorer(scorer).calculate(replay_snapshot).evidence.to_payload()

    assert first_snapshot.input_hash == replay_snapshot.input_hash
    assert first["evidenceSchemaVersion"] == EVIDENCE_SCHEMA_VERSION
    assert first["explanationVersion"] == EXPLANATION_VERSION
    assert first["featureVersion"] == TRANSFORM
    assert first["rankingVersion"] == RANKING_VERSION
    assert first["candidateVersion"] == 6
    assert first["items"] == replay["items"]
    for item in first["items"]:
        assert 0.0 <= item["coverage"] <= 1.0
        assert len(item["evidence"]) <= 3
        assert item["coverage"] == pytest.approx(sum(entry["coverage"] for entry in item["evidence"]))
        assert all(entry["group"] in GROUPS and entry["direction"] == "POSITIVE" for entry in item["evidence"])
        required = {"evidenceId", "group", "direction", "contribution", "coverage"}
        allowed = required | {"genreCode", "sourceMovieId"}
        assert all(required <= set(entry) <= allowed for entry in item["evidence"])

    changed = PersonalScorer(scorer).calculate(snapshot(scorer, [(1, 4)], generation="changed"))
    first_ids = [entry["evidenceId"] for item in first["items"] for entry in item["evidence"]]
    changed_ids = [entry.evidence_id for item in changed.evidence.items for entry in item.evidence]
    assert first_ids and changed_ids and set(first_ids).isdisjoint(changed_ids)


@pytest.mark.parametrize("k", [1, 10, 100])
def test_k_boundaries_chunking_and_input_order_preserve_scores_and_evidence(scorer, k):
    ratings = [(mid, 0.5 + (mid % 4) * 0.5) for mid in range(1, k + 1)]
    value = snapshot(scorer, ratings)
    reordered = snapshot(scorer, reversed(ratings), generation="reordered")
    small = scorer.score(value.ratings, chunk_size=1)
    large = scorer.score(reordered.ratings, chunk_size=777)
    np.testing.assert_array_equal(small.movie_ids, large.movie_ids)
    np.testing.assert_array_equal(small.scores, large.scores)
    candidate_ids = small.ranked_ids(25).tolist()
    first = AttributionBuilder(scorer).build(value, small, candidate_ids).to_payload()
    second = AttributionBuilder(scorer).build(reordered, large, candidate_ids).to_payload()
    assert first["items"] == second["items"]
    validate_evidence(first, snapshot=value, candidates=candidate_ids,
                      scores=[item["score"] for item in first["items"]])


def test_only_approved_human_groups_map_raw_features():
    expected = {
        "history_log1p": "RATING_PATTERN",
        "country_genre_pos_log1p": "GENRE_PATTERN",
        "collection_pos_log1p": "CONTENT_RELATION",
        "strict_positive_director": "CONTENT_RELATION",
        "reach_log1p": "POPULARITY_SIGNAL",
        "candidate_positive_reach_cross": "POPULARITY_SIGNAL",
        "tmdb_rating_raw_0_1": "AUDIENCE_RATING_SIGNAL",
    }
    assert {name: _feature_group(name) for name in expected} == expected
    assert _feature_group("country_genre_known") is None
    assert _feature_group("history_positive_reach_known_share") is None
    assert _feature_group("strict_positive_anchor") is None
    assert _feature_group("verified_kobis_member") is None
    assert len(FEATURES) == 71


def test_mixed_contributions_have_deterministic_ties_top3_and_coverage(scorer):
    value = snapshot(scorer, [(1, 5)])
    contributions = np.zeros(len(FEATURES), np.float64)
    assigned = {
        "history_log1p": 4.0,
        "country_genre_pos_log1p": 3.0,
        "collection_pos_log1p": 3.0,
        "reach_log1p": 2.0,
        "tmdb_rating_raw_0_1": 1.0,
        "verified_kobis_member": 5.0,
        "country_genre_known": 4.0,
        "strict_negative_director": -100.0,
    }
    for name, contribution in assigned.items():
        contributions[FEATURES.index(name)] = contribution

    item = _candidate_evidence(value, 2, 1.5, contributions).to_payload()
    assert [entry["group"] for entry in item["evidence"]] == [
        "RATING_PATTERN", "CONTENT_RELATION", "GENRE_PATTERN"
    ]
    assert [entry["contribution"] for entry in item["evidence"]] == [4.0, 3.0, 3.0]
    assert item["coverage"] == pytest.approx(10.0 / 22.0)
    assert [entry["coverage"] for entry in item["evidence"]] == pytest.approx([4 / 22, 3 / 22, 3 / 22])
    assert not any(name in json.dumps(item) for name in assigned)


def test_specific_genre_and_source_require_traceable_positive_rating(scorer, monkeypatch):
    contributions = np.zeros(len(FEATURES), np.float64)
    contributions[FEATURES.index("country_genre_pos_log1p")] = 2.0
    contributions[FEATURES.index("collection_pos_log1p")] = 1.0
    specifics = _specific_evidence(
        scorer.features, 11, [(2, 3.5), (1, 4.0)], contributions
    )
    assert specifics == {
        "GENRE_PATTERN": ("TMDB_16", None),
        "CONTENT_RELATION": (None, 1),
    }
    assert _specific_evidence(scorer.features, 11, [(1, 3.5)], contributions) == {}
    assert _specific_evidence(scorer.features, 11, [], contributions) == {}
    # 같은 장르만으로는 country|genre 결합 입력의 근거가 아니다.
    assert _specific_evidence(scorer.features, 11, [(3, 5.0)], contributions) == {}

    axes = list(scorer.features.axes)
    candidate_axes = list(axes[scorer.features.positions[11]])
    candidate_axes[3] = None
    axes[scorer.features.positions[11]] = tuple(candidate_axes)
    monkeypatch.setattr(scorer.features, "axes", axes)
    assert _specific_evidence(scorer.features, 11, [(1, 4.0)], contributions) == {
        "CONTENT_RELATION": (None, 1)
    }

    value = snapshot(scorer, [(1, 4.0)])
    item = _candidate_evidence(value, 11, 1.5, contributions, specifics).to_payload()
    by_group = {entry["group"]: entry for entry in item["evidence"]}
    assert by_group["GENRE_PATTERN"]["genreCode"] == "TMDB_16"
    assert by_group["CONTENT_RELATION"]["sourceMovieId"] == 1
    assert "genre_ids" not in json.dumps(item)
    assert "director_ids" not in json.dumps(item)

    without_specifics = _candidate_evidence(value, 11, 1.5, contributions).to_payload()
    assert {
        entry["evidenceId"] for entry in item["evidence"]
    }.isdisjoint(entry["evidenceId"] for entry in without_specifics["evidence"])

    payload = empty_evidence(value).to_payload()
    payload["items"] = [item]
    validate_evidence(payload, snapshot=value, candidates=[11], scores=[1.5])
    tampered = copy.deepcopy(payload)
    tampered["items"][0]["evidence"][0]["genreCode"] = "TMDB_53"
    with pytest.raises(GbtError, match="INVALID_EVIDENCE_ENTRY"):
        validate_evidence(tampered, snapshot=value, candidates=[11], scores=[1.5])

    unrelated = np.zeros(len(FEATURES), np.float64)
    unrelated[FEATURES.index("country_genre_neg_log1p")] = 2.0
    unrelated[FEATURES.index("strict_negative_series")] = 1.0
    assert _specific_evidence(scorer.features, 11, [(1, 4.0)], unrelated) == {}


def test_k0_and_exhausted_have_no_gbt_attribution(scorer):
    personal = PersonalScorer(scorer)
    cold = personal.calculate(snapshot(scorer))
    exhausted = personal.calculate(snapshot(scorer, [(1, 1)], watched=range(2, 1006)))
    assert cold.candidates["candidateVersion"] == exhausted.candidates["candidateVersion"] == 6
    assert cold.evidence.items == exhausted.evidence.items == ()
    assert all(item["reasons"] == [] for item in cold.candidates["taste"])
    with pytest.raises(GbtError, match="UNSUPPORTED_HISTORY"):
        personal.calculate(snapshot(scorer, [(99999, 1)]))


@pytest.mark.parametrize(
    "attack",
    [
        "root-version",
        "candidate-score",
        "candidate-id",
        "model-version",
        "serving-revision",
        "unknown-group",
        "direction",
        "duplicate-id",
        "nonfinite",
        "infinity",
        "zero",
        "negative",
        "coverage",
        "entry-coverage",
        "too-many",
    ],
)
def test_validator_rejects_tamper_and_cross_execution(scorer, attack):
    value = snapshot(scorer, [(1, 5)])
    result = PersonalScorer(scorer).calculate(value)
    payload = copy.deepcopy(result.evidence.to_payload())
    candidates = [item["movieId"] for item in result.candidates["taste"]]
    scores = [item["score"] for item in result.candidates["taste"]]
    item = next(item for item in payload["items"] if item["evidence"])
    entry = item["evidence"][0]
    if attack == "root-version":
        payload["inputVersion"] = "sha256:" + "0" * 64
    elif attack == "candidate-score":
        payload["items"][0]["score"] += 1
    elif attack == "candidate-id":
        payload["items"][0]["movieId"] += 1
    elif attack == "model-version":
        payload["modelVersion"] = "another-model"
    elif attack == "serving-revision":
        payload["servingRevision"] = "sha256:" + "0" * 64
    elif attack == "unknown-group":
        entry["group"] = "RELEASE_PERIOD"
    elif attack == "direction":
        entry["direction"] = "NEGATIVE"
    elif attack == "duplicate-id":
        if len(item["evidence"]) < 2:
            item["evidence"].append(copy.deepcopy(entry))
            item["coverage"] += entry["coverage"]
        else:
            item["evidence"][1]["evidenceId"] = entry["evidenceId"]
    elif attack == "nonfinite":
        entry["contribution"] = float("nan")
    elif attack == "infinity":
        entry["contribution"] = float("inf")
    elif attack == "zero":
        entry["contribution"] = 0.0
    elif attack == "negative":
        entry["contribution"] = -1.0
    elif attack == "coverage":
        item["coverage"] = 1.1
    elif attack == "entry-coverage":
        entry["coverage"] = 0.0
    else:
        item["evidence"] = [copy.deepcopy(entry) for _ in range(4)]
    with pytest.raises(GbtError):
        validate_evidence(payload, snapshot=value, candidates=candidates, scores=scores)


def test_attribution_score_mismatch_fails_closed(scorer, monkeypatch):
    value = snapshot(scorer, [(1, 5)])
    raw = scorer.score(value.ratings)
    candidate_ids = raw.ranked_ids(3).tolist()
    original = scorer.bundle.booster.predict

    def shifted(*args, **kwargs):
        result = original(*args, **kwargs)
        result[:, -1] += 1.0
        return result

    monkeypatch.setattr(scorer.bundle.booster, "predict", shifted)
    with pytest.raises(GbtError, match="ATTRIBUTION_SCORE_MISMATCH"):
        AttributionBuilder(scorer).build(value, raw, candidate_ids)


@pytest.mark.parametrize(
    ("attack", "error"),
    [("shape", "INVALID_ATTRIBUTION_SHAPE"), ("nonfinite", "NONFINITE_ATTRIBUTION")],
)
def test_invalid_attribution_matrix_fails_closed(scorer, monkeypatch, attack, error):
    value = snapshot(scorer, [(1, 5)])
    raw = scorer.score(value.ratings)
    candidate_ids = raw.ranked_ids(3).tolist()
    original = scorer.bundle.booster.predict

    def invalid(*args, **kwargs):
        result = original(*args, **kwargs)
        if attack == "shape":
            return result[:, :-1]
        result[0, 0] = np.nan
        return result

    monkeypatch.setattr(scorer.bundle.booster, "predict", invalid)
    with pytest.raises(GbtError, match=error):
        AttributionBuilder(scorer).build(value, raw, candidate_ids)


def test_backend_handoff_fixture_passes_strict_validator(scorer):
    value = snapshot(scorer, [(1, 1.0)], dismissed=[2], watched=range(6, 1006))
    payload = json.loads((Path(__file__).parent / "fixtures/gbt-evidence-v2.json").read_text())
    candidates = [item["movieId"] for item in payload["items"]]
    scores = [item["score"] for item in payload["items"]]
    validate_evidence(payload, snapshot=value, candidates=candidates, scores=scores)
