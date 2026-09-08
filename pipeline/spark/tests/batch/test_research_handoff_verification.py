"""Artifact checks must fail for damaged files and escaped snapshot paths."""

import hashlib

import pytest

from jobs.batch.verify_research_handoff import verify


def test_reject_changed_content(tmp_path):
    path = tmp_path / "evidence.json"
    path.write_bytes(b"{}")
    entry = {"bytes": 2, "sha256": hashlib.sha256(b"{}").hexdigest()}
    verify(tmp_path, "evidence.json", entry)
    path.write_bytes(b"[]")
    with pytest.raises(ValueError, match="size/hash mismatch"):
        verify(tmp_path, "evidence.json", entry)


def test_reject_path_outside_snapshot(tmp_path):
    snapshot = tmp_path / "snapshot"
    snapshot.mkdir()
    (tmp_path / "outside.json").write_bytes(b"{}")
    entry = {"bytes": 2, "sha256": hashlib.sha256(b"{}").hexdigest()}
    with pytest.raises(ValueError, match="outside root"):
        verify(snapshot, "../outside.json", entry)
