"""626 isolated runtime acceptance tests. Install gbt-runtime-requirements.txt to run."""

import importlib.util
import json
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
import pyarrow as pa
import pytest

xgb = pytest.importorskip("xgboost", reason="GBT isolated runtime is not in the legacy ALS image")

from feelm_consumer.gbt.artifacts import (  # noqa: E402
    canonical,
    compare_mapping,
    load_bundle,
    sha,
)
from feelm_consumer.gbt.errors import GbtError  # noqa: E402
from feelm_consumer.gbt.export import export_bundle, publish_no_replace  # noqa: E402
from feelm_consumer.gbt.features import Features  # noqa: E402
from feelm_consumer.gbt.inputs import merge_inputs  # noqa: E402
from feelm_consumer.gbt.schema import CATALOG_SCHEMA, FEATURES, POLICY  # noqa: E402
from feelm_consumer.gbt.scorer import Scorer  # noqa: E402


def synthetic_catalog(count=150):
    rows = []
    for mid in range(1, count + 1):
        rows.append({"service_movie_id": mid, "tmdb_id": 100000 + mid,
                     "collection_ids": [1 + mid % 10], "director_ids": [1 + mid % 7],
                     "top5_cast_ids": [1 + mid % 11, 30 + mid % 13], "genre_ids": [16 if mid % 2 else 18],
                     "keyword_ids": [1 + mid % 17], "origin_country_codes": ["KR" if mid % 3 else "JP"],
                     "production_country_codes": [], "tmdb_vote_count": float(mid * 10),
                     "tmdb_vote_average": 7.0, "kobis_movie_code": "20200001" if mid % 5 == 0 else None,
                     "kobis_link_status": "VERIFIED" if mid % 5 == 0 else None,
                     "kobis_value_valid": mid % 5 == 0,
                     "kobis_audience_cumulative": 10000000.0 if mid % 5 == 0 else None,
                     "eligible_movie": True})
    return pa.Table.from_pylist(rows, schema=CATALOG_SCHEMA)


@pytest.fixture
def source(tmp_path):
    rng = np.random.default_rng(626)
    x = rng.random((100, 71), dtype=np.float32)
    y = x[:, 0] + x[:, 44] + x[:, 70]
    model = xgb.train({"objective": "reg:squarederror", "max_depth": 2, "nthread": 1, "seed": 626},
                      xgb.DMatrix(x, label=y), num_boost_round=3)
    path = tmp_path / "source.json"
    model.save_model(path)
    return {"table": synthetic_catalog(), "model_path": path, "model_version": "synthetic-v26",
            "provenance": {"input_snapshot": "synthetic", "candidate_cutoff": "2026-09-21",
                           "training_rule": POLICY, "source_sha256": {"model": sha(path.read_bytes())}}}


@pytest.fixture
def bundle(tmp_path, source):
    root = tmp_path / "bundle"
    revision = export_bundle(**source, output=root)
    return root, revision


def repin(root):
    """Adversarial *authorized* manifest: still must satisfy semantic checks."""
    manifest = json.loads((root / "manifest.json").read_bytes())
    for name, entry in manifest["files"].items():
        p = root / name
        if p.is_file():
            entry.update(bytes=p.stat().st_size, sha256=sha(p.read_bytes()))
    raw = canonical(manifest)
    (root / "manifest.json").write_bytes(raw)
    (root / "_SUCCESS").write_bytes(canonical({"manifest_sha256": sha(raw)}))
    return "sha256:" + sha(raw)


def test_gbt_bundle_inventory_and_roundtrip(bundle):
    root, revision = bundle
    b = load_bundle(root, expected_revision=revision)
    assert b.booster.num_features() == len(FEATURES) == 71
    assert b.table.column_names == CATALOG_SCHEMA.names
    assert set(p.name for p in root.iterdir()) == {
        "gbt.json", "catalog.parquet", "feature-schema.json", "manifest.json", "_SUCCESS"}
    command = ("from feelm_consumer.gbt.artifacts import load_bundle; "
               "import sys; b=load_bundle(sys.argv[1],expected_revision=sys.argv[2]); print(b.serving_revision)")
    result = subprocess.run([sys.executable, "-c", command, str(root), revision],
                            check=True, capture_output=True, text=True)
    assert result.stdout.strip() == revision


@pytest.mark.parametrize("attack", ["tamper", "missing", "extra", "partial", "order", "revision", "runtime", "shape"])
def test_gbt_bundle_rejects_tamper_and_partial(bundle, attack):
    root, revision = bundle
    if attack == "tamper":
        path = root / "gbt.json"
        data = path.read_bytes()
        path.write_bytes(b"x" + data[1:])
    elif attack == "missing":
        (root / "catalog.parquet").unlink()
    elif attack == "extra":
        (root / "extra").write_bytes(b"x")
    elif attack == "partial":
        (root / "_SUCCESS").unlink()
    elif attack == "revision":
        revision = "sha256:" + "0" * 64
    elif attack == "order":
        schema = json.loads((root / "feature-schema.json").read_bytes())
        schema["features"].reverse()
        (root / "feature-schema.json").write_bytes(canonical(schema))
        revision = repin(root)
    elif attack == "runtime":
        manifest = json.loads((root / "manifest.json").read_bytes())
        manifest["runtime"]["numpy"] = "0.0.0"
        (root / "manifest.json").write_bytes(canonical(manifest))
        revision = repin(root)
    else:
        model = xgb.train({"objective": "reg:squarederror", "nthread": 1},
                          xgb.DMatrix(np.ones((2, 2)), label=[1, 2]), num_boost_round=1)
        model.save_model(root / "gbt.json")
        revision = repin(root)
    with pytest.raises(GbtError):
        load_bundle(root, expected_revision=revision)


@pytest.mark.parametrize("path", ["../outside", "/absolute", "C:/absolute", "nested/gbt.json"])
def test_gbt_bundle_paths_are_confined(bundle, path):
    root, _ = bundle
    manifest = json.loads((root / "manifest.json").read_bytes())
    manifest["files"][path] = manifest["files"].pop("gbt.json")
    raw = canonical(manifest)
    (root / "manifest.json").write_bytes(raw)
    (root / "_SUCCESS").write_bytes(canonical({"manifest_sha256": sha(raw)}))
    with pytest.raises(GbtError):
        load_bundle(root, expected_revision="sha256:" + sha(raw))


def test_gbt_bundle_paths_are_confined_symlink(bundle, tmp_path):
    root, revision = bundle
    link = tmp_path / "link"
    try:
        link.symlink_to(root, target_is_directory=True)
    except OSError:
        pytest.skip("Windows symlink privilege unavailable; must run on Linux too")
    with pytest.raises(GbtError):
        load_bundle(link, expected_revision=revision)


def test_gbt_bundle_paths_are_confined_size(bundle):
    root, revision = bundle
    with (root / "gbt.json").open("r+b") as stream:
        stream.truncate(8 * 1024**2 + 1)
    with pytest.raises(GbtError):
        load_bundle(root, expected_revision=revision)


def test_gbt_mapping_identity_and_support(source):
    with pytest.raises(GbtError, match="EMPTY_LIVE_MAPPING"):
        compare_mapping(source["table"], [])
    pairs = [(i, 100000 + i) for i in range(2, 152)]
    report = compare_mapping(source["table"], pairs)
    assert report["supported_count"] == 149
    assert report["added_count"] == report["removed_count"] == 1
    assert report["activation_allowed"]
    with pytest.raises(GbtError, match="MAPPING_REBOUND"):
        compare_mapping(source["table"], [(1, 999)])
    with pytest.raises(GbtError, match="DUPLICATE_LIVE_MAPPING"):
        compare_mapping(source["table"], [(1, 100001), (2, 100001)])


@pytest.mark.parametrize("k", [0, 1, 3, 17, 37, 101, 8518])
def test_gbt_inputs_preserve_all_k(k):
    merged = merge_inputs([(i, 0.5 + (i % 10) / 2) for i in range(1, k + 1)])
    assert len(merged.ratings) == len(merged.sources) == len(merged.excluded) == k
    if k:
        features = Features(synthetic_catalog(k + 1)).prepare(merged.ratings)
        matrix = features.matrix(np.array([k]))
        assert matrix[0, 0] == np.log1p(np.float32(k))


def test_gbt_actual_rating_wins_dismiss(bundle):
    root, revision = bundle
    s = Scorer(load_bundle(root, expected_revision=revision))
    baseline = s.score([(1, 5.0)])
    both = s.score([(1, 5.0)], dismissed_movie_ids=[1])
    np.testing.assert_array_equal(baseline.scores, both.scores)
    assert both.actual_count == 1 and both.dismiss_count == 0
    dismiss = s.score([], dismissed_movie_ids=[1])
    negative = s.score([(1, 1.0)])
    np.testing.assert_array_equal(dismiss.scores, negative.scores)
    assert dismiss.dismiss_count == 1 and dismiss.actual_count == 0
    assert s.score([]).status == "NEEDS_COLD_START"
    np.testing.assert_array_equal(s.score([(1, 5)]).scores, baseline.scores)


@pytest.mark.parametrize("ratings", [[(True, 4)], [(2**63, 4)], [(0, 4)], [("1", 4)],
                                     [(1, True)], [(1, float("nan"))], [(1, 3.1)], [(1, 5.5)],
                                     [(1, 1), (1, 4)], [(1, "4")], [(1, 10**400)]])
def test_gbt_unknown_ids_and_invalid_inputs(ratings):
    with pytest.raises(GbtError):
        merge_inputs(ratings)


def test_gbt_unknown_ids_and_invalid_inputs_support(bundle):
    root, revision = bundle
    s = Scorer(load_bundle(root, expected_revision=revision))
    with pytest.raises(GbtError, match="UNSUPPORTED_HISTORY") as exc:
        s.score([(999, 4)])
    assert exc.value.details == {"input_count": 1, "unsupported_count": 1}
    with pytest.raises(GbtError, match="UNSUPPORTED_CANDIDATE"):
        s.score([(1, 4)], candidate_ids=[999])
    with pytest.raises(GbtError):
        s.score([(1, 4)], candidate_ids=[True])


def test_gbt_explicit_exclusion_modes(bundle):
    root, revision = bundle
    s = Scorer(load_bundle(root, expected_revision=revision))
    taste = s.score([(1, 4)], dismissed_movie_ids=[2])
    party = s.score([(1, 4)], dismissed_movie_ids=[2], excluded_movie_ids=[3, 999])
    assert 3 in taste.movie_ids and 3 not in party.movie_ids
    assert not {1, 2} & set(taste.movie_ids)
    assert party.input_count == taste.input_count == 2
    assert s.score([(1, 4)], candidate_ids=[]).movie_ids.size == 0


def test_gbt_full_subset_parity(bundle):
    root, revision = bundle
    s = Scorer(load_bundle(root, expected_revision=revision))
    ratings = [(1, 5), (2, 0.5), (4, 3)]
    full = s.score(ratings, chunk_size=7)
    subset = s.score(list(reversed(ratings)), candidate_ids=list(full.movie_ids[::3][::-1]), chunk_size=1)
    positions = np.searchsorted(full.movie_ids, subset.movie_ids)
    np.testing.assert_array_equal(subset.scores, full.scores[positions])
    prepared = s.features.prepare(merge_inputs(ratings).ratings)
    axis = np.array([s.features.positions[mid] for mid in full.movie_ids])
    np.testing.assert_array_equal(prepared.matrix(axis)[::3], prepared.matrix(axis[::3]))
    requests = [ratings, [(1, 0.5)], [(2, 3.0), (3, 4.5)]]
    expected = [s.score(r) for r in requests]
    with ThreadPoolExecutor(max_workers=3) as executor:
        actual = list(executor.map(s.score, requests * 3))
    for i, result in enumerate(actual):
        np.testing.assert_array_equal(result.scores, expected[i % 3].scores)


def test_gbt_export_no_overwrite_and_retry(tmp_path, source, monkeypatch):
    output = tmp_path / "final"
    output.mkdir()  # Even an empty directory must survive.
    with pytest.raises(FileExistsError):
        export_bundle(**source, output=output)
    assert not list(output.iterdir())
    stage = tmp_path / "staged"
    stage.mkdir()
    with pytest.raises(FileExistsError):
        publish_no_replace(stage, output)
    fresh = tmp_path / "new-version"
    import feelm_consumer.gbt.export as module
    original = module.load_bundle
    monkeypatch.setattr(module, "load_bundle", lambda *a, **kw: (_ for _ in ()).throw(GbtError("INJECTED_FAILURE")))
    with pytest.raises(GbtError):
        export_bundle(**source, output=fresh)
    assert not fresh.exists()
    monkeypatch.setattr(module, "load_bundle", original)

    def attempt(_):
        try:
            return export_bundle(**source, output=fresh)
        except FileExistsError:
            return "CONFLICT"
    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(executor.map(attempt, range(2)))
    assert results.count("CONFLICT") == 1
    revision = next(r for r in results if r != "CONFLICT")
    load_bundle(fresh, expected_revision=revision)
    before = (fresh / "manifest.json").read_bytes()
    with pytest.raises(FileExistsError):
        export_bundle(**source, output=fresh)
    assert before == (fresh / "manifest.json").read_bytes()


def test_gbt_reload_and_previous_bundle(bundle, tmp_path, source):
    root, revision = bundle
    previous = Scorer(load_bundle(root, expected_revision=revision)).score([(1, 4)])
    bad = tmp_path / "next"
    export_bundle(**source, output=bad)
    (bad / "gbt.json").write_bytes(b"broken")
    with pytest.raises(GbtError):
        load_bundle(bad, expected_revision=revision)
    restored = Scorer(load_bundle(root, expected_revision=revision)).score([(1, 4)])
    np.testing.assert_array_equal(previous.scores, restored.scores)


def test_audit_gbt_bundle_privacy_and_contents(bundle):
    root, revision = bundle
    table = load_bundle(root, expected_revision=revision).table
    assert set(table.column_names) == set(CATALOG_SCHEMA.names)
    for name in ("user_id", "nickname", "ratings", "title", "overview", "password", "dsn"):
        assert name not in table.column_names
    for path in root.iterdir():
        assert path.suffix not in {".py", ".pkl", ".pickle", ".html", ".npz"}


@pytest.mark.parametrize("value", [[], None, 42, "manifest"])
def test_gbt_bundle_rejects_tamper_and_partial_nonobject(bundle, value):
    root, _ = bundle
    raw = canonical(value)
    (root / "manifest.json").write_bytes(raw)
    (root / "_SUCCESS").write_bytes(canonical({"manifest_sha256": sha(raw)}))
    with pytest.raises(GbtError, match="INVALID_MANIFEST"):
        load_bundle(root, expected_revision="sha256:" + sha(raw))


def test_gbt_bundle_rejects_tamper_and_partial_multioutput(source, tmp_path):
    x = np.random.default_rng(626).random((30, 71), dtype=np.float32)
    booster = xgb.train({"objective": "reg:squarederror", "nthread": 1},
                        xgb.DMatrix(x, label=np.column_stack((x[:, 0], x[:, 1]))), num_boost_round=2)
    path = tmp_path / "multi.json"
    booster.save_model(path)
    with pytest.raises(GbtError, match="MODEL_OUTPUT_MISMATCH"):
        export_bundle(**{**source, "model_path": path}, output=tmp_path / "multi-bundle")
    assert not (tmp_path / "multi-bundle").exists()


def test_gbt_bundle_rejects_tamper_and_partial_calendar(source, tmp_path):
    wrong = {**source, "provenance": {**source["provenance"], "candidate_cutoff": "2026-99-99"}}
    with pytest.raises(GbtError, match="INVALID_PROVENANCE"):
        export_bundle(**wrong, output=tmp_path / "bad-date")


def test_gbt_bundle_paths_are_confined_export_ancestors(source, tmp_path):
    outside = tmp_path / "outside"
    outside.mkdir()
    link = tmp_path / "link"
    try:
        link.symlink_to(outside, target_is_directory=True)
    except OSError:
        pytest.skip("Windows symlink privilege unavailable; tested on Linux")
    with pytest.raises(GbtError, match="SYMLINK_NOT_ALLOWED"):
        export_bundle(**source, output=link / "escaped" / "final")
    assert not (outside / "escaped").exists()


def test_audit_gbt_bundle_privacy_and_contents_parity_failure():
    spec = importlib.util.spec_from_file_location(
        "parity_tool", Path(__file__).parents[1] / "tools/verify_gbt_frozen_raw_parity.py")
    tool = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(tool)
    axis = np.array([101, 102], np.int64)
    with pytest.raises(AssertionError, match="PARITY_ID_MISMATCH") as exc:
        tool.parity(axis, np.array([2., 1.]), np.array([101, 103]), np.array([2., 1.]))
    assert "101" not in str(exc.value) and "102" not in str(exc.value) and "103" not in str(exc.value)
    with pytest.raises(AssertionError, match="INVALID_PARITY_SCORES"):
        tool.parity(axis, np.array([2., 1.]), axis, np.array([2., np.nan]))
    with pytest.raises(AssertionError, match="INVALID_PARITY_SCORES"):
        tool.parity(axis, np.array([2., 1.]), axis, np.array([[2., 1.]]))
