"""Immutable content bundle validation and no-overwrite publication tests."""

from __future__ import annotations

import errno
import json
import multiprocessing
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from pathlib import Path
from threading import Barrier

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


def _claim_empty_target(root, writer_ready, target_claimed, writer_waiting):
    import batch.content.artifacts as artifacts

    parent = Path(root) / "content-artifacts"
    assert writer_ready.wait(timeout=10)
    with artifacts._publication_lock(parent):
        (parent / "content-v1").mkdir()
        target_claimed.set()
        assert writer_waiting.wait(timeout=10)


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


@pytest.mark.parametrize("collision_errno", [errno.EEXIST, errno.ENOTEMPTY])
def test_rename_collision_errnos_are_normalized_without_deleting_winner(
    tmp_path, monkeypatch, collision_errno
):
    import batch.content.artifacts as artifacts

    target = tmp_path / "content-artifacts" / "content-v1"

    def collide_with_winner(source, destination):
        assert Path(source).is_dir()
        assert Path(destination) == target
        target.mkdir()
        (target / "winner").write_text("preserve", encoding="utf-8")
        raise OSError(collision_errno, "rename collision")

    monkeypatch.setattr(artifacts.os, "rename", collide_with_winner)
    with pytest.raises(FileExistsError, match="content artifact already exists"):
        _write(tmp_path)
    assert (target / "winner").read_text(encoding="utf-8") == "preserve"
    assert not list(target.parent.glob(".content-v1.stage-*"))


def test_same_version_race_keeps_one_complete_winner_and_allows_a_new_version(
    tmp_path, monkeypatch
):
    import batch.content.artifacts as artifacts

    original_lock = artifacts._publication_lock
    publication_barrier = Barrier(2)

    @contextmanager
    def synchronized_lock(parent):
        publication_barrier.wait(timeout=10)
        with original_lock(parent):
            yield

    monkeypatch.setattr(artifacts, "_publication_lock", synchronized_lock)
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
    assert load_content_artifact(outcomes[0])["manifest"]["version"] == "content-v1"
    assert (outcomes[0] / "features.json").is_file()
    assert (outcomes[0] / "manifest.json").is_file()
    assert (outcomes[0] / "_SUCCESS").is_file()
    assert not list(outcomes[0].parent.glob(".content-v1.stage-*"))

    monkeypatch.setattr(artifacts, "_publication_lock", original_lock)
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


def test_cross_process_empty_target_claim_is_preserved(tmp_path, monkeypatch):
    import batch.content.artifacts as artifacts

    context = multiprocessing.get_context("spawn")
    writer_ready = context.Event()
    target_claimed = context.Event()
    writer_waiting = context.Event()
    original_lock = artifacts._publication_lock

    @contextmanager
    def coordinated_lock(parent):
        writer_ready.set()
        assert target_claimed.wait(timeout=10)
        writer_waiting.set()
        with original_lock(parent):
            yield

    monkeypatch.setattr(artifacts, "_publication_lock", coordinated_lock)
    process = context.Process(
        target=_claim_empty_target,
        args=(tmp_path, writer_ready, target_claimed, writer_waiting),
    )
    try:
        process.start()
        with pytest.raises(FileExistsError, match="content artifact already exists"):
            _write(tmp_path)
        process.join(timeout=20)
        assert process.exitcode == 0
        target = tmp_path / "content-artifacts" / "content-v1"
        assert target.is_dir()
        assert not list(target.iterdir())
        assert not list(target.parent.glob(".content-v1.stage-*"))
    finally:
        if process.is_alive():
            process.terminate()
            process.join(timeout=10)


def test_marker_failure_rolls_back_before_waiting_writer_retries(tmp_path, monkeypatch):
    import batch.content.artifacts as artifacts

    original_lock = artifacts._publication_lock
    original_touch = artifacts.Path.touch
    publication_barrier = Barrier(2)
    marker_failures = 1

    @contextmanager
    def synchronized_lock(parent):
        publication_barrier.wait(timeout=10)
        with original_lock(parent):
            yield

    def fail_first_marker(path, *, exist_ok=False):
        nonlocal marker_failures
        if path.name == "_SUCCESS" and marker_failures:
            marker_failures -= 1
            raise OSError("marker failure")
        return original_touch(path, exist_ok=exist_ok)

    monkeypatch.setattr(artifacts, "_publication_lock", synchronized_lock)
    monkeypatch.setattr(artifacts.Path, "touch", fail_first_marker)
    with ThreadPoolExecutor(max_workers=2) as executor:
        futures = [executor.submit(_write, tmp_path) for _ in range(2)]
        outcomes = []
        failures = []
        for future in futures:
            try:
                outcomes.append(future.result())
            except OSError as error:
                failures.append(error)
    assert len(outcomes) == 1
    assert len(failures) == 1
    assert str(failures[0]) == "marker failure"
    location = outcomes[0]
    assert load_content_artifact(location)["manifest"]["version"] == "content-v1"
    assert (location / "_SUCCESS").is_file()
    assert not list(location.parent.glob(".content-v1.stage-*"))
