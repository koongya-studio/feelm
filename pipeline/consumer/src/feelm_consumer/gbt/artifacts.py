"""Pinned, data-only immutable bundle loader. Never imports code from a bundle."""

import hashlib
import importlib.metadata
import json
import re
import sys
from dataclasses import dataclass
from datetime import date
from pathlib import Path

import numpy as np
import pyarrow as pa
import pyarrow.fs as fs
import pyarrow.parquet as pq
import xgboost as xgb

from .errors import GbtError
from .schema import CATALOG_SCHEMA, COEFFICIENT, CONTRACT, FEATURES, POLICY, SEMANTICS, TRANSFORM

LIMITS = {"gbt.json": 8 * 1024**2, "catalog.parquet": 256 * 1024**2, "feature-schema.json": 65536}
VERSION = re.compile(r"[a-z0-9][a-z0-9._-]{0,49}\Z")
DIGEST = re.compile(r"[a-f0-9]{64}\Z")
MAX_ROWS = 500_000


def canonical(value):
    text = json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
    return (text + "\n").encode()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def json_value(data):
    def pairs(items):
        out = {}
        for k, v in items:
            if k in out:
                raise GbtError("DUPLICATE_JSON_KEY")
            out[k] = v
        return out
    try:
        value = json.loads(data, object_pairs_hook=pairs, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
        if canonical(value) != data:
            raise GbtError("NONCANONICAL_JSON")
        return value
    except (ValueError, TypeError, UnicodeError) as exc:
        raise GbtError("INVALID_CONTROL_JSON") from exc


def runtime():
    return {"python": f"{sys.version_info.major}.{sys.version_info.minor}",
            **{name: importlib.metadata.version(name) for name in ("numpy", "pyarrow", "scipy")},
            "xgboost": xgb.__version__}


def code_revision():
    root = Path(__file__).parent
    # Normalizing CRLF only makes Git's text checkout behavior irrelevant.
    entries = {p.name: sha(p.read_bytes().replace(b"\r\n", b"\n")) for p in sorted(root.glob("*.py"))}
    return "sha256:" + sha(canonical(entries))


def validate_catalog(table):
    if not table.schema.equals(CATALOG_SCHEMA, check_metadata=False) or not 0 < len(table) <= MAX_ROWS:
        raise GbtError("CATALOG_SCHEMA")
    if table.nbytes > 512 * 1024**2:
        raise GbtError("CATALOG_TOO_LARGE")
    for name in ("service_movie_id", "tmdb_id", "eligible_movie"):
        if table[name].null_count:
            raise GbtError("CATALOG_NULL")
    ids, tmdb = (table[n].to_numpy() for n in ("service_movie_id", "tmdb_id"))
    if (np.any(ids <= 0) or np.any(tmdb <= 0) or np.any(np.diff(ids) <= 0)
            or len(np.unique(tmdb)) != len(tmdb)):
        raise GbtError("INVALID_MAPPING")
    for name in ("collection_ids", "director_ids", "top5_cast_ids", "genre_ids", "keyword_ids",
                 "origin_country_codes", "production_country_codes"):
        for row in table[name].to_pylist():
            if row is None:
                continue
            if len(row) > 1024 or any(v is None for v in row) or len(row) != len(set(row)):
                raise GbtError("INVALID_TOKENS")
            if name.endswith("_ids"):
                if any(v <= 0 for v in row):
                    raise GbtError("INVALID_TOKENS")
            elif any(not re.fullmatch("[A-Z]{2}", v) for v in row):
                raise GbtError("INVALID_COUNTRY")
    # Public measurements may be absent/nonfinite: frozen masks intentionally handle them.
    for row in table.select(["kobis_movie_code", "kobis_link_status", "kobis_value_valid",
                             "kobis_audience_cumulative"]).to_pylist():
        if str(row["kobis_link_status"] or "").startswith("VERIFIED"):
            code = row["kobis_movie_code"]
            if not code or not re.fullmatch(r"[0-9]{8}", code):
                raise GbtError("INVALID_KOBIS_CROSSWALK")


def mapping_revision(table):
    pairs = np.column_stack([table[n].to_numpy() for n in ("service_movie_id", "tmdb_id")]).astype("<i8")
    return "sha256:" + sha(pairs.tobytes())


def compare_mapping(table, live_pairs):
    from .inputs import movie_id

    live, tmdb_seen = {}, set()
    for mid, tid in live_pairs:
        mid, tid = movie_id(mid), movie_id(tid)
        if mid in live or tid in tmdb_seen:
            raise GbtError("DUPLICATE_LIVE_MAPPING")
        live[mid] = tid
        tmdb_seen.add(tid)
    if not live:
        raise GbtError("EMPTY_LIVE_MAPPING")
    snapshot = dict(zip(table["service_movie_id"].to_pylist(), table["tmdb_id"].to_pylist(), strict=True))
    shared = live.keys() & snapshot.keys()
    rebound = sum(live[k] != snapshot[k] for k in shared)
    report = {"current_count": len(live), "supported_count": len(shared) - rebound,
              "added_count": len(live.keys() - snapshot.keys()), "removed_count": len(snapshot.keys() - live.keys()),
              "rebound_count": rebound, "activation_allowed": rebound == 0,
              "support_fraction": (len(shared) - rebound) / len(live) if live else 0}
    if rebound:
        raise GbtError("MAPPING_REBOUND", **report)
    return report


@dataclass(frozen=True)
class Bundle:
    model_version: str
    serving_revision: str
    table: pa.Table
    booster: xgb.Booster
    manifest: dict


def _read_files(uri, expected_revision):
    if not isinstance(expected_revision, str) or not re.fullmatch(r"sha256:[a-f0-9]{64}", expected_revision):
        raise GbtError("EXPECTED_REVISION_REQUIRED")
    text = str(uri)
    local = "://" not in text or text.startswith("file://")
    if local:
        if text.startswith("file://"):
            filesystem, base = fs.FileSystem.from_uri(text)
        else:
            filesystem, base = fs.LocalFileSystem(), str(Path(text).absolute())
        root = Path(base)
        if any(p.is_symlink() for p in (root, *root.parents)):
            raise GbtError("SYMLINK_NOT_ALLOWED")
        if not root.is_dir() or any(p.is_symlink() or not p.is_file() for p in root.iterdir()):
            raise GbtError("INVALID_BUNDLE_DIRECTORY")
    elif text.startswith("hdfs://"):
        filesystem, base = fs.FileSystem.from_uri(text)
    else:
        raise GbtError("UNSUPPORTED_BUNDLE_URI")
    base = base.rstrip("/\\")
    infos = filesystem.get_file_info(fs.FileSelector(base, recursive=True))
    expected = set(LIMITS) | {"manifest.json", "_SUCCESS"}
    actual = {}
    for info in infos:
        name = info.path.replace("\\", "/").removeprefix(base.replace("\\", "/") + "/")
        if info.type != fs.FileType.File or name not in expected or name in actual:
            raise GbtError("INVENTORY_MISMATCH")
        actual[name] = info
    if set(actual) != expected:
        raise GbtError("INVENTORY_MISMATCH")
    limits = {**LIMITS, "manifest.json": 1024**2, "_SUCCESS": 1024}
    for name, info in actual.items():
        if not 0 < info.size <= limits[name]:
            raise GbtError("FILE_SIZE_LIMIT")

    def read(name):
        with filesystem.open_input_file(actual[name].path) as handle:
            data = handle.read(limits[name] + 1)
        if len(data) != actual[name].size or len(data) > limits[name]:
            raise GbtError("FILE_CHANGED_WHILE_LOADING")
        return data

    raw = read("manifest.json")
    digest = sha(raw)
    if expected_revision != "sha256:" + digest or json_value(read("_SUCCESS")) != {"manifest_sha256": digest}:
        raise GbtError("REVISION_MISMATCH")
    manifest = json_value(raw)
    if not isinstance(manifest, dict):
        raise GbtError("INVALID_MANIFEST")
    files = manifest.get("files")
    if not isinstance(files, dict) or set(files) != set(LIMITS):
        raise GbtError("INVALID_INVENTORY")
    contents = {}
    for name in LIMITS:
        entry = files[name]
        if (not isinstance(entry, dict) or set(entry) != {"bytes", "sha256"}
                or type(entry["bytes"]) is not int or entry["bytes"] != actual[name].size
                or not isinstance(entry["sha256"], str) or not DIGEST.fullmatch(entry["sha256"])):
            raise GbtError("INVALID_INVENTORY")
        contents[name] = read(name)
        if sha(contents[name]) != entry["sha256"]:
            raise GbtError("FILE_DIGEST_MISMATCH")
    return manifest, contents


def load_bundle(uri, *, expected_revision, threads=2):
    if type(threads) is not int or not 1 <= threads <= 64:
        raise GbtError("INVALID_THREAD_COUNT")
    try:
        manifest, contents = _read_files(uri, expected_revision)
        expected_keys = {"contract", "model_version", "transform", "score_semantics", "runtime", "code_revision",
                         "mapping_revision", "catalog_rows", "files", "provenance", "input_contract"}
        if (set(manifest) != expected_keys or manifest["contract"] != CONTRACT
                or not isinstance(manifest["model_version"], str) or not VERSION.fullmatch(manifest["model_version"])
                or manifest["transform"] != TRANSFORM or manifest["score_semantics"] != SEMANTICS
                or manifest["input_contract"] != "all-k-actual-first-dismiss-1-explicit-exclusions-v1"):
            raise GbtError("BUNDLE_CONTRACT_MISMATCH")
        if manifest["runtime"] != runtime() or manifest["code_revision"] != code_revision():
            raise GbtError("RUNTIME_OR_CODE_MISMATCH")
        schema = json_value(contents["feature-schema.json"])
        if schema != {"features": FEATURES, "coefficient": COEFFICIENT, "policy_version": POLICY,
                      "score_semantics": SEMANTICS}:
            raise GbtError("FEATURE_SCHEMA_MISMATCH")
        reader = pq.ParquetFile(pa.BufferReader(contents["catalog.parquet"]))
        if reader.metadata.num_rows > MAX_ROWS or reader.metadata.num_columns != len(CATALOG_SCHEMA):
            raise GbtError("CATALOG_SIZE_LIMIT")
        if sum(reader.metadata.row_group(i).total_byte_size for i in range(reader.num_row_groups)) > 512 * 1024**2:
            raise GbtError("CATALOG_SIZE_LIMIT")
        table = reader.read()
        validate_catalog(table)
        if (type(manifest["catalog_rows"]) is not int or len(table) != manifest["catalog_rows"]
                or mapping_revision(table) != manifest["mapping_revision"]):
            raise GbtError("MAPPING_REVISION_MISMATCH")
        validate_provenance(manifest["provenance"])
        booster = xgb.Booster()
        booster.load_model(bytearray(contents["gbt.json"]))
        booster.set_param({"nthread": threads})
        if booster.num_features() != 71 or (booster.feature_names is not None and booster.feature_names != FEATURES):
            raise GbtError("MODEL_FEATURE_MISMATCH")
        config = json.loads(booster.save_config())["learner"]
        if config["objective"]["name"] != "reg:squarederror":
            raise GbtError("MODEL_OBJECTIVE_MISMATCH")
        if (config["gradient_booster"]["name"] != "gbtree"
                or int(config["learner_model_param"]["num_target"]) != 1):
            raise GbtError("MODEL_OUTPUT_MISMATCH")
        probe = booster.inplace_predict(np.zeros((1, 71), np.float32))
        if probe.shape != (1,) or not np.isfinite(probe).all():
            raise GbtError("MODEL_OUTPUT_MISMATCH")
        return Bundle(manifest["model_version"], expected_revision, table, booster, manifest)
    except GbtError:
        raise
    except (ValueError, TypeError, KeyError, OSError, pa.ArrowException, xgb.core.XGBoostError) as exc:
        raise GbtError("BUNDLE_LOAD_FAILED") from exc


def validate_provenance(value):
    keys = {"input_snapshot", "candidate_cutoff", "training_rule", "source_sha256"}
    if not isinstance(value, dict) or set(value) != keys:
        raise GbtError("INVALID_PROVENANCE")
    if (not isinstance(value["input_snapshot"], str) or not VERSION.fullmatch(value["input_snapshot"])
            or not isinstance(value["candidate_cutoff"], str)
            or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value["candidate_cutoff"])
            or value["training_rule"] != POLICY):
        raise GbtError("INVALID_PROVENANCE")
    try:
        date.fromisoformat(value["candidate_cutoff"])
    except ValueError as exc:
        raise GbtError("INVALID_PROVENANCE") from exc
    hashes = value["source_sha256"]
    if not isinstance(hashes, dict) or not hashes or len(hashes) > 32:
        raise GbtError("INVALID_PROVENANCE")
    for name, digest in hashes.items():
        if (not isinstance(name, str) or not VERSION.fullmatch(name) or not isinstance(digest, str)
                or not DIGEST.fullmatch(digest)):
            raise GbtError("INVALID_PROVENANCE")
