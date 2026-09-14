"""Immutable content bundle validation and no-overwrite publication tests."""

from __future__ import annotations

import json
from concurrent.futures import ThreadPoolExecutor

import pytest

from batch.content.artifacts import (
    content_artifact_uri,
    load_content_artifact,
    write_content_artifact,
)
from batch.evaluation.content_hybrid_run import _selection_path


def _write(tmp_path):
    return write_content_artifact(
        tmp_path,
        version="content-v1",
        als_model_version="als-v1",
        mapping_snapshot="map-v1",
        vocabulary={"genre:1": 0, "keyword:2": 1},
        prior=[0.1] * 10,
        features=[{"tmdb_id": 10, "indices": [0, 1], "values": [0.5, 0.5]}],
    )


def test_artifact_roundtrip_and_exact_lineage(tmp_path):
    location = _write(tmp_path)
    loaded = load_content_artifact(
        location,
        expected_version="content-v1",
        als_model_version="als-v1",
        mapping_snapshot="map-v1",
    )
    assert loaded["features"] == [{"tmdb_id": 10, "indices": [0, 1], "values": [0.5, 0.5]}]
    assert location == tmp_path / "content-artifacts" / "content-v1"
    assert str(location) == content_artifact_uri(str(tmp_path), "content-v1")
    assert (location / "_SUCCESS").exists()
    with pytest.raises(ValueError, match="ALS model"):
        load_content_artifact(location, als_model_version="als-v2")
    with pytest.raises(FileExistsError):
        _write(tmp_path)


@pytest.mark.parametrize("invalid", [".", "..", "nested/value", r"nested\value", "nul\x00value"])
@pytest.mark.parametrize("component", ["version", "als_model_version", "mapping_snapshot"])
def test_artifact_and_selection_paths_reject_special_version_components(
    tmp_path, component, invalid
):
    artifact_versions = {
        "version": "content-v1",
        "als_model_version": "als-v1",
        "mapping_snapshot": "map-v1",
    }
    artifact_versions[component] = invalid
    selection_versions = {
        "content_version": artifact_versions["version"],
        "als_model_version": artifact_versions["als_model_version"],
        "mapping_snapshot": artifact_versions["mapping_snapshot"],
    }

    with pytest.raises(ValueError, match="path segment"):
        write_content_artifact(
            tmp_path,
            **artifact_versions,
            vocabulary={"genre:1": 0},
            prior=[0.1] * 10,
            features=[],
        )
    with pytest.raises(ValueError, match="path segment"):
        _selection_path(tmp_path, **selection_versions)


def test_valid_version_tuples_have_distinct_canonical_artifact_and_selection_paths(tmp_path):
    left = {"content_version": "a--b", "als_model_version": "c", "mapping_snapshot": "d"}
    right = {"content_version": "a", "als_model_version": "b", "mapping_snapshot": "c--d"}
    collision_inputs = (
        {"content_version": "content-v1", "als_model_version": ".", "mapping_snapshot": "."},
        {
            "content_version": "content-v1",
            "als_model_version": "anything",
            "mapping_snapshot": "..",
        },
    )

    assert content_artifact_uri(str(tmp_path), left["content_version"]) != content_artifact_uri(
        str(tmp_path), right["content_version"]
    )
    assert _selection_path(tmp_path, **left).resolve() != _selection_path(
        tmp_path, **right
    ).resolve()
    for versions in collision_inputs:
        with pytest.raises(ValueError, match="path segment"):
            _selection_path(tmp_path, **versions)


def test_artifact_rejects_hash_and_feature_corruption(tmp_path):
    location = _write(tmp_path)
    features = json.loads((location / "features.json").read_text())
    features["features"][0]["indices"] = [1, 0]
    (location / "features.json").write_text(json.dumps(features))
    with pytest.raises(ValueError, match="hash mismatch"):
        load_content_artifact(location)


def test_failed_validation_never_publishes_a_partial_bundle(tmp_path):
    with pytest.raises(ValueError, match="contiguous"):
        write_content_artifact(
            tmp_path,
            version="bad",
            als_model_version="als-v1",
            mapping_snapshot="map-v1",
            vocabulary={"genre:1": 2},
            prior=[0.1] * 10,
            features=[],
        )
    assert not (tmp_path / "content-artifacts" / "bad").exists()
    assert not list((tmp_path / "content-artifacts").glob(".bad.stage-*"))


def test_complete_marker_is_published_with_the_verified_bundle(tmp_path):
    location = _write(tmp_path)
    assert (location / "_SUCCESS").exists()
    assert load_content_artifact(location)["manifest"]["version"] == "content-v1"


def test_public_reader_rejects_missing_marker_and_publish_failures_cleanup(tmp_path, monkeypatch):
    location = _write(tmp_path)
    (location / "_SUCCESS").unlink()
    with pytest.raises(ValueError, match="_SUCCESS missing"):
        load_content_artifact(location)

    import batch.content.artifacts as artifacts

    def fail_marker(self, **kwargs):
        raise OSError("marker failure")

    monkeypatch.setattr(artifacts.Path, "touch", fail_marker)
    with pytest.raises(OSError, match="marker failure"):
        _write(tmp_path / "marker")
    assert not list((tmp_path / "marker" / "content-artifacts").glob(".content-v1.stage-*"))
    assert not (tmp_path / "marker" / "content-artifacts" / "content-v1").exists()


def test_staging_reader_rejects_and_rename_failure_leaves_no_public_bundle(tmp_path, monkeypatch):
    import batch.content.artifacts as artifacts

    original_rename = artifacts.os.rename

    def observe_staging(source, target):
        with pytest.raises(ValueError, match="_SUCCESS missing"):
            load_content_artifact(source)
        original_rename(source, target)

    monkeypatch.setattr(artifacts.os, "rename", observe_staging)
    _write(tmp_path / "reader")

    def fail_rename(source, target):
        raise OSError("rename failure")

    monkeypatch.setattr(artifacts.os, "rename", fail_rename)
    with pytest.raises(OSError, match="rename failure"):
        _write(tmp_path / "rename")
    assert not (tmp_path / "rename" / "content-artifacts" / "content-v1").exists()
    assert not list((tmp_path / "rename" / "content-artifacts").glob(".content-v1.stage-*"))


def test_same_version_race_keeps_one_complete_winner_and_allows_a_new_version(tmp_path):
    with ThreadPoolExecutor(max_workers=2) as executor:
        futures = [executor.submit(_write, tmp_path) for _ in range(2)]
        outcomes = []
        failures = []
        for future in futures:
            try:
                outcomes.append(future.result())
            except FileExistsError as error:
                failures.append(error)
    assert len(outcomes) == 1
    assert len(failures) == 1
    assert outcomes[0].name == "content-v1"
    with pytest.raises(FileExistsError):
        _write(tmp_path)
    location = write_content_artifact(
        tmp_path,
        version="content-v2",
        als_model_version="als-v1",
        mapping_snapshot="map-v1",
        vocabulary={"genre:1": 0},
        prior=[0.1] * 10,
        features=[{"tmdb_id": 10, "indices": [0], "values": [1.0]}],
    )
    assert location.name == "content-v2"
