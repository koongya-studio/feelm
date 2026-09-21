"""Immutable local content artifact bundles used by offline hybrid evaluation.

The writer deliberately accepts a caller-provided directory.  Production HDFS
publication is a separate adapter; this module keeps validation and atomic
no-overwrite semantics testable without smuggling an HDFS URI into reports.
"""

from __future__ import annotations

import errno
import hashlib
import json
import os
import shutil
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from uuid import uuid4

MANIFEST = "manifest.json"
FEATURES = "features.json"
SUCCESS = "_SUCCESS"
SCHEMA_VERSION = "content-artifacts-v1"


def content_artifact_uri(output_uri: str, version: str) -> str:
    _identifier(version, "version")
    return f"{output_uri.rstrip('/')}/content-artifacts/{version}"


def write_content_artifact(
    output_uri: str | Path,
    *,
    version: str,
    features: list[dict[str, object]],
    vocabulary: dict[str, int],
    prior: list[float],
    als_model_version: str,
    mapping_snapshot: str,
) -> Path:
    """Validate, stage, reload-check, then atomically publish an immutable bundle."""
    _identifier(version, "version")
    _identifier(als_model_version, "als_model_version")
    _identifier(mapping_snapshot, "mapping_snapshot")
    # Keep the local writer on the same public layout as the contract.  A
    # future HDFS adapter can use ``content_artifact_uri`` directly; this
    # writer intentionally remains local-only.
    target = Path(content_artifact_uri(str(output_uri), version))
    if (target / SUCCESS).is_file():
        raise FileExistsError(f"content artifact already exists: {target}")
    _validate_features(features, vocabulary, prior)
    stage = target.parent / f".{version}.stage-{uuid4().hex}"
    published = False
    try:
        stage.mkdir(parents=True)
        payload = {"features": features, "vocabulary": vocabulary, "prior": prior}
        _write_json(stage / FEATURES, payload)
        digest = _sha256(stage / FEATURES)
        manifest = {
            "schema_version": SCHEMA_VERSION,
            "version": version,
            "als_model_version": als_model_version,
            "mapping_snapshot": mapping_snapshot,
            "feature_count": len(features),
            "vocabulary_size": len(vocabulary),
            "features_sha256": digest,
        }
        _write_json(stage / MANIFEST, manifest)
        # Staging is deliberately not public.  Its self-validation must not
        # weaken the reader's completion-marker gate.
        _load_content_artifact(stage, expected_version=version, require_success=False)
        # Serialize the final no-overwrite decision across local processes.
        # POSIX rename may otherwise replace an empty destination directory.
        with _publication_lock(target.parent):
            if os.path.lexists(target):
                raise FileExistsError(f"content artifact already exists: {target}")
            try:
                os.rename(stage, target)
            except OSError as error:
                if error.errno in {errno.EEXIST, errno.ENOTEMPTY} and os.path.lexists(target):
                    raise FileExistsError(
                        f"content artifact already exists: {target}"
                    ) from error
                raise
            published = True
            # A public reader rejects the short interval between rename and
            # this final mutation. Staging is never marked complete, so it
            # cannot be mistaken for a published bundle by a concurrent reader.
            try:
                (target / SUCCESS).touch(exist_ok=False)
            except Exception:
                shutil.rmtree(target, ignore_errors=True)
                if not target.exists():
                    published = False
                raise
        return target
    except Exception:
        cleanup = target if published else stage
        if cleanup.exists():
            shutil.rmtree(cleanup, ignore_errors=True)
        raise


@contextmanager
def _publication_lock(parent: Path) -> Iterator[None]:
    """Serialize final local publication across threads and processes."""
    if os.name == "nt":
        import msvcrt

        lock_path = parent / ".content-artifact.publish.lock"
        with lock_path.open("a+b") as lock_file:
            lock_file.seek(0, os.SEEK_END)
            if lock_file.tell() == 0:
                lock_file.write(b"\0")
                lock_file.flush()
            lock_file.seek(0)
            msvcrt.locking(lock_file.fileno(), msvcrt.LK_LOCK, 1)
            try:
                yield
            finally:
                lock_file.seek(0)
                msvcrt.locking(lock_file.fileno(), msvcrt.LK_UNLCK, 1)
        return

    import fcntl

    flags = os.O_RDONLY
    if hasattr(os, "O_DIRECTORY"):
        flags |= os.O_DIRECTORY
    parent_fd = os.open(parent, flags)
    try:
        fcntl.flock(parent_fd, fcntl.LOCK_EX)
        yield
    finally:
        os.close(parent_fd)


def load_content_artifact(
    directory: str | Path,
    *,
    expected_version: str | None = None,
    als_model_version: str | None = None,
    mapping_snapshot: str | None = None,
) -> dict[str, object]:
    """Reload and verify a bundle before it can be associated with a run."""
    return _load_content_artifact(
        directory,
        expected_version=expected_version,
        als_model_version=als_model_version,
        mapping_snapshot=mapping_snapshot,
        require_success=True,
    )


def _load_content_artifact(
    directory: str | Path,
    *,
    expected_version: str | None = None,
    als_model_version: str | None = None,
    mapping_snapshot: str | None = None,
    require_success: bool,
) -> dict[str, object]:
    """Validate either private staging or a public, completed artifact."""
    directory = Path(directory)
    if require_success and not (directory / SUCCESS).is_file():
        raise ValueError("content artifact is not publicly complete (_SUCCESS missing)")
    manifest = _read_json(directory / MANIFEST)
    if manifest.get("schema_version") != SCHEMA_VERSION:
        raise ValueError("unsupported content artifact schema")
    if expected_version is not None and manifest.get("version") != expected_version:
        raise ValueError("content artifact version mismatch")
    if als_model_version is not None and manifest.get("als_model_version") != als_model_version:
        raise ValueError("ALS model version mismatch")
    if mapping_snapshot is not None and manifest.get("mapping_snapshot") != mapping_snapshot:
        raise ValueError("mapping snapshot mismatch")
    payload = _read_json(directory / FEATURES)
    if manifest.get("features_sha256") != _sha256(directory / FEATURES):
        raise ValueError("content artifact hash mismatch")
    features = payload.get("features")
    vocabulary = payload.get("vocabulary")
    prior = payload.get("prior")
    if (
        not isinstance(features, list)
        or not isinstance(vocabulary, dict)
        or not isinstance(prior, list)
    ):
        raise ValueError("content artifact payload is malformed")
    _validate_features(features, vocabulary, prior)
    if manifest.get("feature_count") != len(features) or manifest.get("vocabulary_size") != len(
        vocabulary
    ):
        raise ValueError("content artifact count mismatch")
    return {"manifest": manifest, **payload}


def _validate_features(
    features: list[dict[str, object]], vocabulary: dict[str, int], prior: list[float]
) -> None:
    if sorted(vocabulary.values()) != list(range(len(vocabulary))):
        raise ValueError("vocabulary indexes must be contiguous and zero based")
    if len(prior) != 10 or any(
        not isinstance(value, int | float) or not 0 <= value <= 1 for value in prior
    ):
        raise ValueError("prior must contain ten finite probabilities")
    if abs(sum(prior) - 1.0) > 1e-12:
        raise ValueError("prior must sum to one")
    ids: set[int] = set()
    for feature in features:
        movie_id = feature.get("tmdb_id")
        indexes = feature.get("indices")
        values = feature.get("values")
        if (
            isinstance(movie_id, bool)
            or not isinstance(movie_id, int)
            or movie_id <= 0
            or movie_id in ids
        ):
            raise ValueError("feature TMDB IDs must be unique positive integers")
        ids.add(movie_id)
        if (
            not isinstance(indexes, list)
            or not isinstance(values, list)
            or len(indexes) != len(values)
        ):
            raise ValueError("feature indexes and values must have equal lengths")
        if (
            indexes != sorted(indexes)
            or len(indexes) != len(set(indexes))
            or any(not isinstance(i, int) or i < 0 or i >= len(vocabulary) for i in indexes)
        ):
            raise ValueError("feature indexes are invalid")
        if any(
            not isinstance(value, int | float)
            or value != value
            or value in (float("inf"), float("-inf"))
            for value in values
        ):
            raise ValueError("feature values must be finite")


def _write_json(path: Path, value: object) -> None:
    serialized = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False
    )
    path.write_text(serialized, encoding="utf-8")


def _read_json(path: Path) -> dict[str, object]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"cannot read content artifact: {path}") from error
    if not isinstance(value, dict):
        raise ValueError("content artifact JSON must be an object")
    return value


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _identifier(value: str, label: str) -> None:
    # Both layouts serialize these values as filesystem components. Reject
    # separators for either host platform so their meaning is not OS-dependent.
    separators = {"/", "\\"}
    if os.altsep is not None:
        separators.add(os.altsep)
    if (
        not isinstance(value, str)
        or not value
        or value in {".", ".."}
        or "\x00" in value
        or any(char.isspace() for char in value)
        or any(separator in value for separator in separators)
    ):
        raise ValueError(f"{label} must be a non-empty path segment")
