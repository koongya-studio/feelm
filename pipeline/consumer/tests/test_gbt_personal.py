"""627 real GBT, input and full-catalog policy acceptance (no external services)."""

import json
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pytest
from test_gbt_bundle import synthetic_catalog, xgb

from feelm_consumer.gbt.artifacts import load_bundle, sha
from feelm_consumer.gbt.errors import GbtError
from feelm_consumer.gbt.export import export_bundle
from feelm_consumer.gbt.schema import CATALOG_SCHEMA, POLICY
from feelm_consumer.gbt.scorer import Scorer
from feelm_consumer.gbt_personal import PersonalScorer
from feelm_consumer.gbt_snapshot import canonical, create_snapshot, decode_snapshot


@pytest.fixture(scope="module")
def scorer(tmp_path_factory):
    root = tmp_path_factory.mktemp("personal-gbt")
    rng = np.random.default_rng(627)
    matrix = rng.random((100, 71), dtype=np.float32)
    model = xgb.train({"objective": "reg:squarederror", "max_depth": 2, "nthread": 1},
                      xgb.DMatrix(matrix, label=matrix[:, 0] + matrix[:, 44]), num_boost_round=3)
    path = root / "source.json"
    model.save_model(path)
    revision = export_bundle(
        table=synthetic_catalog(1005), model_path=path, output=root / "bundle", model_version="gbt-test627",
        provenance={"input_snapshot": "synthetic627", "candidate_cutoff": "2026-09-23",
                    "training_rule": POLICY, "source_sha256": {"model": sha(path.read_bytes())}},
    )
    return Scorer(load_bundle(root / "bundle", expected_revision=revision))


def snapshot(scorer, ratings=(), dismissed=(), watched=(), generation="g1", user_id=10):
    return create_snapshot(user_id=user_id, generation_id=generation,
                           model_version=scorer.bundle.model_version, serving_revision=scorer.bundle.serving_revision,
                           ratings=ratings, dismissed_movie_ids=dismissed, watched_movie_ids=watched)


def contract_payload(scorer):
    value = snapshot(scorer, [(1, 1.0)], dismissed=[2], watched=range(6, 1006))
    result = PersonalScorer(scorer).calculate(value, generated_at="2026-09-23T00:00:00Z")
    return {"model": {"modelKind": "GBT", "modelVersion": value.model_version,
                      "servingRevision": value.serving_revision},
            "state": result.state, "candidates": result.candidates}


def test_actual_personal_payload_matches_java_reader_fixture(scorer):
    fixture = Path(__file__).parent / "fixtures/gbt-personal-state.json"
    assert contract_payload(scorer) == json.loads(fixture.read_text(encoding="utf-8"))
    # Each CI job archives only its own service. A full checkout verifies the shared copy too.
    backend_fixture = Path(__file__).resolve().parents[3] / "backend/src/test/resources/gbt-personal-state.json"
    if backend_fixture.exists():
        assert json.loads(backend_fixture.read_text(encoding="utf-8")) == contract_payload(scorer)


def decoded(value, raw=None, **overrides):
    args = dict(user_id=value.user_id, generation_id=value.generation_id, model_version=value.model_version,
                serving_revision=value.serving_revision, input_hash=value.input_hash, max_bytes=10**7)
    args.update(overrides)
    return decode_snapshot(canonical(value.to_payload()) if raw is None else raw, **args)


def test_snapshot_roundtrip_source_order_and_first_last(scorer):
    first = snapshot(scorer, [(5, 0.5), (1, 5)], dismissed=[1, 3], watched=[7])
    reordered = snapshot(scorer, [(1, 5.0), (5, 0.5)], dismissed=[3, 1], watched=[7])
    assert first == decoded(first) == reordered
    assert first.input_count == 3
    assert first.excluded_movie_ids == {1, 3, 5, 7}
    assert first.to_payload()["inputs"] == [
        {"movieId": 1, "score": 5.0, "source": "RATING"},
        {"movieId": 3, "score": 1.0, "source": "DISMISS"},
        {"movieId": 5, "score": 0.5, "source": "RATING"},
    ]
    assert snapshot(scorer, [(1, 5)], dismissed=[1, 3], watched=[7]).input_hash != first.input_hash
    assert snapshot(scorer, [(5, .5)], dismissed=[1, 3], watched=[7]).input_hash != first.input_hash


@pytest.mark.parametrize("change", ["hash", "source", "rating", "extra", "duplicate", "nan"])
def test_snapshot_rejects_tamper(scorer, change):
    value = snapshot(scorer, [(1, 5)])
    payload = value.to_payload()
    if change == "hash":
        payload["inputHash"] = "sha256:" + "0" * 64
    elif change == "source":
        payload["inputs"][0]["source"] = "DISMISS"
    elif change == "rating":
        payload["ratings"][0][1] = .5
    elif change == "extra":
        payload["arbitraryRedisKey"] = "feelm:user:another:input"
    elif change == "nan":
        payload["ratings"][0][1] = float("nan")
    raw = json.dumps(payload).encode()
    if change == "duplicate":
        raw = raw.replace(b'"userId": 10', b'"userId": 99,"userId": 10')
    with pytest.raises(GbtError):
        decoded(value, raw)


@pytest.mark.parametrize("overrides", [{"user_id": 11}, {"generation_id": "g2"},
                                      {"serving_revision": "sha256:" + "0" * 64},
                                      {"max_bytes": 1}, {"model_version": "another"}])
def test_snapshot_wrong_identity_and_limits(scorer, overrides):
    with pytest.raises(GbtError):
        decoded(snapshot(scorer, [(1, 5)]), **overrides)


@pytest.mark.parametrize("k", [1, 10, 30, 100, 1001])
def test_all_history_negative_and_raw_parity(scorer, k):
    value = snapshot(scorer, [(i, 0.5 + (i % 4) * .5) for i in range(1, k + 1)])
    actual = PersonalScorer(scorer).calculate(decoded(value))
    expected = scorer.score(value.ratings)
    assert actual.state["inputCount"] == k
    assert [item["movieId"] for item in actual.candidates["taste"]] == expected.ranked_ids(500).tolist()
    by_id = dict(zip(expected.movie_ids.tolist(), expected.scores.tolist(), strict=True))
    assert all(item["score"] == by_id[item["movieId"]] for item in actual.candidates["taste"])
    assert actual.state["status"] == "READY"


def test_dismiss_only_actual_wins_and_exclusions(scorer):
    personal = PersonalScorer(scorer)
    virtual = personal.calculate(snapshot(scorer, dismissed=[1], watched=[3]))
    negative = personal.calculate(snapshot(scorer, [(1, 1)], watched=[3]))
    assert virtual.candidates["taste"] == negative.candidates["taste"]
    assert virtual.state["inputCount"] == virtual.state["dismissInputCount"] == 1
    assert virtual.state["ratingCount"] == 0
    assert not {1, 3} & {item["movieId"] for item in virtual.candidates["taste"]}
    assert personal.calculate(snapshot(scorer, [(1, 5)], dismissed=[1])).candidates["taste"] == (
        personal.calculate(snapshot(scorer, [(1, 5)])).candidates["taste"])


def test_cold_start_exhaustion_and_unknown_are_distinct(scorer):
    personal = PersonalScorer(scorer)
    cold = personal.calculate(snapshot(scorer))
    assert cold.state["status"] == "READY" and len(cold.candidates["taste"]) == 500
    assert cold.state["supportedInputCount"] == cold.state["inputCount"] == 0
    empty = personal.calculate(snapshot(scorer, [(1, 1)], watched=range(2, 1006)))
    assert empty.state["status"] == "EXHAUSTED" and empty.candidates["taste"] == []
    with pytest.raises(GbtError, match="UNSUPPORTED_HISTORY"):
        personal.calculate(snapshot(scorer, [(99999, 1)]))
    with pytest.raises(GbtError, match="SCORER_REVISION_MISMATCH"):
        personal.calculate(replace(snapshot(scorer, [(1, 1)]), serving_revision="sha256:" + "0" * 64))


def test_cold_start_popularity_is_deterministic_and_does_not_filter_watched(scorer):
    personal = PersonalScorer(scorer)
    first = personal.calculate(snapshot(scorer), generated_at="2026-09-23T00:00:00Z")
    replay = personal.calculate(snapshot(scorer), generated_at="2026-09-23T00:00:00Z")
    assert first == replay
    expected = sorted(range(1, 1006), key=lambda mid: (
        -max(mid * 10, 7732.457348003972 if mid % 5 == 0 else 0), mid))[:500]
    assert [item["movieId"] for item in first.candidates["taste"]] == expected
    watched = personal.calculate(snapshot(scorer, watched=range(1, 1006)))
    assert watched.candidates["taste"] == first.candidates["taste"]
    assert watched.snapshot.watched_movie_ids == frozenset(range(1, 1006))
    assert watched.state["excludedCounts"]["watchOnly"] == 0
    assert watched.state["inputCount"] == watched.state["ratingCount"] == watched.state["dismissCount"] == 0
    assert all(item["reasons"] == [] for item in first.candidates["taste"])
    assert first.candidates["discovery"] == []


def test_cold_start_popularity_boundaries(scorer):
    import pyarrow as pa

    rows = synthetic_catalog(6).to_pylist()
    for row, votes in zip(rows, [None, float("nan"), -1, 50.5, 10, 10], strict=True):
        row.update(tmdb_vote_count=votes, kobis_link_status=None, kobis_value_valid=False)
    rows[0].update(kobis_audience_cumulative=100000, kobis_link_status="VERIFIED", kobis_value_valid=True)
    rows[1].update(kobis_audience_cumulative=float("inf"), kobis_link_status="VERIFIED", kobis_value_valid=True)
    rows[5]["eligible_movie"] = False
    small = Scorer(replace(scorer.bundle, table=pa.Table.from_pylist(rows, schema=CATALOG_SCHEMA)))
    result = PersonalScorer(small).calculate(snapshot(small))
    assert [item["movieId"] for item in result.candidates["taste"]] == [1, 5, 2, 3, 4]
    assert all(np.isfinite(item["score"]) for item in result.candidates["taste"])
    assert result.state["status"] == "READY" and result.state["eligibleCandidateCount"] == 5
    empty = Scorer(replace(scorer.bundle, table=pa.Table.from_pylist([], schema=CATALOG_SCHEMA)))
    result = PersonalScorer(empty).calculate(snapshot(empty))
    assert result.state["status"] == "EXHAUSTED" and result.candidates["taste"] == []


@pytest.fixture(scope="module")
def cold_scorer(tmp_path_factory, scorer):
    root = tmp_path_factory.mktemp("cold-gbt")
    path = root / "source.json"
    scorer.bundle.booster.save_model(path)
    revision = export_bundle(
        table=synthetic_catalog(6), model_path=path, output=root / "bundle", model_version="gbt-cold627",
        provenance={"input_snapshot": "synthetic-cold627", "candidate_cutoff": "2026-09-23",
                    "training_rule": POLICY, "source_sha256": {"model": sha(path.read_bytes())}},
    )
    return Scorer(load_bundle(root / "bundle", expected_revision=revision))


def cold_contract_payload(scorer):
    personal = PersonalScorer(scorer)
    values = {"before": snapshot(scorer, [(1, 5)], user_id=58501, generation="before"),
              "cold": snapshot(scorer, watched=[1], user_id=58501, generation="cold"),
              "next": snapshot(scorer, [(2, 1)], user_id=58502, generation="next")}
    payload = {"model": {"modelKind": "GBT", "modelVersion": scorer.bundle.model_version,
                         "servingRevision": scorer.bundle.serving_revision}}
    for name, value in values.items():
        result = personal.calculate(value, generated_at="2026-09-23T00:00:00Z")
        payload[name] = {"state": result.state, "candidates": result.candidates}
    return payload


def test_cold_start_payload_matches_backend_kafka_fixture(cold_scorer):
    payload = cold_contract_payload(cold_scorer)
    fixture = Path(__file__).parent / "fixtures/gbt-personal-cold-state.json"
    assert json.loads(fixture.read_text(encoding="utf-8")) == payload
    backend_fixture = Path(__file__).resolve().parents[3] / "backend/src/test/resources/gbt-personal-cold-state.json"
    if backend_fixture.exists():
        assert json.loads(backend_fixture.read_text(encoding="utf-8")) == payload


def test_discovery_receives_population_beyond_taste_limit(scorer, monkeypatch):
    import feelm_consumer.gbt_personal as module

    captured = {}
    def select(_groups, population, ratings, **kwargs):
        captured["count"] = len(population)
        captured["ratings"] = ratings
        item = population[-1]
        reason = SimpleNamespace(payload=lambda: {"code": "DISCOVERY_AGGREGATE_AFFINITY"})
        return SimpleNamespace(candidates=[item], reasons={item.movie_id: reason})
    monkeypatch.setattr(module, "select_discovery_candidates", select)
    personal = PersonalScorer(scorer)
    # The selection boundary is replaced solely to assert candidate population,
    # while existing test_discovery.py verifies group policy itself.
    personal.groups = object()
    personal.popularity = SimpleNamespace(candidates=())
    actual = personal.calculate(snapshot(scorer, [(1, 5)]))
    assert captured["count"] == 1004 and len(captured["ratings"]) == 1
    assert actual.candidates["discovery"][0]["movieId"] not in {
        item["movieId"] for item in actual.candidates["taste"]}
