"""Offline writer for data-only immutable GBT bundles; not a training entry point."""

import ctypes
import errno
import os
import sys
import tempfile
from pathlib import Path

import pyarrow.parquet as pq

from .artifacts import (
    LIMITS,
    VERSION,
    canonical,
    code_revision,
    load_bundle,
    mapping_revision,
    runtime,
    sha,
    validate_catalog,
    validate_provenance,
)
from .errors import GbtError
from .schema import COEFFICIENT, CONTRACT, FEATURES, POLICY, SEMANTICS, TRANSFORM


def publish_no_replace(staged, final):
    """Atomic directory publication, including an already-existing EMPTY target."""
    if sys.platform == "win32":
        os.rename(staged, final)  # Windows rename never replaces an existing directory.
    elif sys.platform.startswith("linux"):
        libc = ctypes.CDLL(None, use_errno=True)
        rename = libc.renameat2
        rename.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
        rename.restype = ctypes.c_int
        if rename(-100, os.fsencode(staged), -100, os.fsencode(final), 1) != 0:
            error = ctypes.get_errno()
            if error in (errno.EEXIST, errno.ENOTEMPTY):
                raise FileExistsError("BUNDLE_ALREADY_EXISTS")
            raise OSError(error, "BUNDLE_PUBLICATION_FAILED")
    else:
        raise GbtError("UNSUPPORTED_ATOMIC_PUBLICATION_PLATFORM")


def export_bundle(*, table, model_path, output, model_version, provenance):
    if not isinstance(model_version, str) or not VERSION.fullmatch(model_version):
        raise GbtError("INVALID_MODEL_VERSION")
    validate_catalog(table)
    validate_provenance(provenance)
    final = Path(output).absolute()
    if final.exists() or final.is_symlink():
        raise FileExistsError("BUNDLE_ALREADY_EXISTS")
    if any(p.is_symlink() for p in (final.parent, *final.parent.parents)):
        raise GbtError("SYMLINK_NOT_ALLOWED")
    final.parent.mkdir(parents=True, exist_ok=True)
    if any(p.is_symlink() for p in (final.parent, *final.parent.parents)):
        raise GbtError("SYMLINK_NOT_ALLOWED")
    model_path = Path(model_path)
    if not 0 < model_path.stat().st_size <= LIMITS["gbt.json"]:
        raise GbtError("FILE_SIZE_LIMIT")
    with tempfile.TemporaryDirectory(prefix=".gbt-staging-", dir=final.parent) as temp:
        # The outer owned temporary directory remains after publishing its child.
        stage = Path(temp) / "bundle"
        stage.mkdir()
        (stage / "gbt.json").write_bytes(model_path.read_bytes())
        schema = {"features": FEATURES, "coefficient": COEFFICIENT,
                  "policy_version": POLICY, "score_semantics": SEMANTICS}
        (stage / "feature-schema.json").write_bytes(canonical(schema))
        pq.write_table(table.replace_schema_metadata(None), stage / "catalog.parquet", compression="zstd")
        inventory = {}
        for name, limit in LIMITS.items():
            data = (stage / name).read_bytes()
            if not 0 < len(data) <= limit:
                raise GbtError("FILE_SIZE_LIMIT")
            inventory[name] = {"bytes": len(data), "sha256": sha(data)}
        manifest = {"contract": CONTRACT, "model_version": model_version, "transform": TRANSFORM,
                    "score_semantics": SEMANTICS, "runtime": runtime(), "code_revision": code_revision(),
                    "mapping_revision": mapping_revision(table), "catalog_rows": len(table), "files": inventory,
                    "provenance": provenance, "input_contract": "all-k-actual-first-dismiss-1-explicit-exclusions-v1"}
        raw = canonical(manifest)
        revision = "sha256:" + sha(raw)
        (stage / "manifest.json").write_bytes(raw)
        (stage / "_SUCCESS").write_bytes(canonical({"manifest_sha256": sha(raw)}))
        load_bundle(stage, expected_revision=revision)
        publish_no_replace(stage, final)
    return revision
