"""Validate an immutable manifest for all S15P21E106-118 outputs."""

from __future__ import annotations

import hashlib
import json
import posixpath
import re
from pathlib import PurePosixPath

REQUIRED_OUTPUTS = {
    "ratings",
    "movies",
    "movie-id-map",
    "service-movie-id-map",
    "validation-report",
}
REQUIRED_SOURCES = {"ratings", "movies", "links", "service_movie_export"}
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


class ManifestCleanupError(OSError):
    """Report failure to remove an incomplete manifest after a write error."""


def validate_input_snapshot(
    spark,
    *,
    input_uri: str,
    manifest_uri: str,
    expected_snapshot: str,
    inputs,
) -> dict[str, object]:
    """Verify the manifest hash, every listed file, and the 118 validation report."""
    manifest_bytes = _read_bytes(spark, manifest_uri)
    actual_snapshot = f"sha256:{hashlib.sha256(manifest_bytes).hexdigest()}"
    if actual_snapshot != expected_snapshot:
        raise ValueError("input manifest hash does not match input snapshot")
    try:
        manifest = json.loads(manifest_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("input manifest must be valid UTF-8 JSON") from error
    if not isinstance(manifest, dict) or not isinstance(manifest.get("files"), dict):
        raise ValueError("input manifest files must be an object")
    preprocessing_revision = manifest.get("preprocessing_revision")
    if not isinstance(preprocessing_revision, str) or not preprocessing_revision.strip():
        raise ValueError("input manifest requires preprocessing revision")
    preprocessing_settings = manifest.get("preprocessing_settings")
    if not isinstance(preprocessing_settings, dict) or not preprocessing_settings:
        raise ValueError("input manifest requires preprocessing settings as a non-empty object")
    sources = manifest.get("sources")
    if not isinstance(sources, dict) or set(sources) != REQUIRED_SOURCES:
        raise ValueError(
            "input manifest sources must contain ratings, movies, links, and service_movie_export"
        )

    manifest_parent = _parent_uri(spark, manifest_uri)
    input_location = _qualified_location(spark, input_uri)
    covered_outputs = set()
    input_files = set()
    for relative, expected_digest in manifest["files"].items():
        if not isinstance(relative, str) or not isinstance(expected_digest, str):
            raise ValueError("input manifest file entries must be string SHA-256 mappings")
        relative_path = PurePosixPath(relative)
        if relative_path.is_absolute() or ".." in relative_path.parts or not relative_path.parts:
            raise ValueError("input manifest file path must be safe and relative")
        if _has_hidden_component(relative_path):
            raise ValueError("hidden files and completion markers must not be manifest entries")
        if not SHA256_PATTERN.fullmatch(expected_digest):
            raise ValueError("input manifest file hash must be lowercase SHA-256")
        file_uri = f"{manifest_parent.rstrip('/')}/{relative_path.as_posix()}"
        file_location = _qualified_location(spark, file_uri)
        if _is_within(file_location, input_location):
            relative_input = posixpath.relpath(file_location[2], input_location[2])
            covered_outputs.add(relative_input.split("/", 1)[0])
            input_files.add(relative_input)
        actual_digest = _sha256_uri(spark, file_uri)
        if actual_digest != expected_digest:
            raise ValueError(f"input file changed or hash mismatched: {relative}")

    missing = sorted(REQUIRED_OUTPUTS - covered_outputs)
    if missing:
        raise ValueError(f"input manifest is missing required 118 outputs: {missing}")
    for role, relative in sources.items():
        if not isinstance(relative, str):
            raise ValueError(f"input manifest source {role} must reference a manifest file")
        relative_path = PurePosixPath(relative)
        if (
            relative_path.is_absolute()
            or ".." in relative_path.parts
            or not relative_path.parts
            or _has_hidden_component(relative_path)
            or relative_path.as_posix() not in manifest["files"]
        ):
            raise ValueError(f"input manifest source {role} must reference a hashed manifest file")

    actual_input_files = _visible_files_under(spark, input_uri)
    if input_files != actual_input_files:
        missing_files = sorted(input_files - actual_input_files)
        unlisted_files = sorted(actual_input_files - input_files)
        raise ValueError(
            "input manifest file set does not match visible input files: "
            f"missing={missing_files}, unlisted={unlisted_files}"
        )

    rows = spark.read.json(f"{input_uri.rstrip('/')}/validation-report").take(2)
    if len(rows) != 1:
        raise ValueError("validation report must contain exactly one record")
    report = rows[0].asDict(recursive=True)
    if report.get("all_checks_passed") is not True:
        raise ValueError("validation report is not complete")
    outputs = report.get("outputs") or {}
    service_mapping = report.get("service_mapping") or {}
    expected_counts = {
        "ratings": inputs.ratings_count,
        "service_movie_id_map": inputs.service_movie_count,
    }
    for name, actual in expected_counts.items():
        if outputs.get(name) != actual:
            raise ValueError(f"validation report {name} count does not match loaded inputs")
    if service_mapping.get("matched") != inputs.matched_service_movie_count:
        raise ValueError(
            "validation report matched service mapping count does not match loaded inputs"
        )
    return manifest


def create_input_manifest(
    spark,
    *,
    input_uri: str,
    manifest_uri: str,
    source_uris: dict[str, str],
    preprocessing_revision: str,
    preprocessing_settings: dict[str, object],
) -> str:
    """Create a deterministic no-overwrite manifest for sources and 118 outputs."""
    if set(source_uris) != REQUIRED_SOURCES:
        raise ValueError(
            "input manifest sources must contain ratings, movies, links, and service_movie_export"
        )
    if not isinstance(preprocessing_revision, str) or not preprocessing_revision.strip():
        raise ValueError("input manifest requires preprocessing revision")
    if not isinstance(preprocessing_settings, dict) or not preprocessing_settings:
        raise ValueError("input manifest requires preprocessing settings as a non-empty object")

    manifest_parent = _parent_uri(spark, manifest_uri)
    files: dict[str, str] = {}
    sources: dict[str, str] = {}
    for relative_input in sorted(_visible_files_under(spark, input_uri)):
        file_uri = f"{input_uri.rstrip('/')}/{relative_input}"
        relative_manifest = _relative_to_manifest_parent(spark, file_uri, manifest_parent)
        files[relative_manifest] = _sha256_uri(spark, file_uri)
    for role in sorted(REQUIRED_SOURCES):
        source_uri = source_uris[role]
        relative_manifest = _relative_to_manifest_parent(spark, source_uri, manifest_parent)
        if _has_hidden_component(PurePosixPath(relative_manifest)):
            raise ValueError("hidden source files must not be manifest entries")
        files[relative_manifest] = _sha256_uri(spark, source_uri)
        sources[role] = relative_manifest

    manifest = {
        "files": files,
        "preprocessing_revision": preprocessing_revision,
        "preprocessing_settings": preprocessing_settings,
        "sources": sources,
    }
    serialized = json.dumps(
        manifest,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    _write_bytes_no_overwrite(spark, manifest_uri, serialized)
    return f"sha256:{hashlib.sha256(serialized).hexdigest()}"


def _read_bytes(spark, uri: str) -> bytes:
    path, file_system = _hadoop_path(spark, uri)
    if not file_system.exists(path):
        raise FileNotFoundError(f"input manifest does not exist: {uri}")
    stream = file_system.open(path)
    try:
        return bytes(spark._jvm.org.apache.commons.io.IOUtils.toByteArray(stream))  # noqa: SLF001
    finally:
        stream.close()


def _sha256_uri(spark, uri: str) -> str:
    path, file_system = _hadoop_path(spark, uri)
    if not file_system.exists(path):
        raise FileNotFoundError(f"input manifest file does not exist: {uri}")
    stream = file_system.open(path)
    try:
        return str(spark._jvm.org.apache.commons.codec.digest.DigestUtils.sha256Hex(stream))  # noqa: SLF001
    finally:
        stream.close()


def _parent_uri(spark, uri: str) -> str:
    path, _ = _hadoop_path(spark, uri)
    parent = path.getParent()
    if parent is None:
        raise ValueError("input manifest URI requires a parent directory")
    return parent.toString()


def _relative_to_manifest_parent(spark, uri: str, manifest_parent: str) -> str:
    location = _qualified_location(spark, uri)
    parent = _qualified_location(spark, manifest_parent)
    if location[:2] != parent[:2] or not _is_within(location, parent):
        raise ValueError("manifest files must be descendants of the manifest parent")
    relative = posixpath.relpath(location[2], parent[2])
    relative_path = PurePosixPath(relative)
    if relative == "." or _has_hidden_component(relative_path):
        raise ValueError("manifest file path must be a visible file below its parent")
    return relative_path.as_posix()


def _qualified_location(spark, uri: str) -> tuple[str, str, str]:
    path, file_system = _hadoop_path(spark, uri)
    qualified = file_system.makeQualified(path).toUri().normalize()
    return (
        str(qualified.getScheme() or "file"),
        str(qualified.getAuthority() or ""),
        posixpath.normpath(str(qualified.getPath())),
    )


def _is_within(candidate: tuple[str, str, str], parent: tuple[str, str, str]) -> bool:
    if candidate[:2] != parent[:2]:
        return False
    parent_path = parent[2]
    return (
        candidate[2] == parent_path
        or parent_path == "/"
        or candidate[2].startswith(parent_path.rstrip("/") + "/")
    )


def _has_hidden_component(path: PurePosixPath) -> bool:
    return any(part.startswith((".", "_")) for part in path.parts)


def _visible_files_under(spark, uri: str) -> set[str]:
    root_path, file_system = _hadoop_path(spark, uri)
    root_location = _qualified_location(spark, uri)
    files = set()
    iterator = file_system.listFiles(root_path, True)
    while iterator.hasNext():
        status = iterator.next()
        location = _qualified_location(spark, status.getPath().toString())
        if not _is_within(location, root_location):
            raise ValueError("input filesystem returned a file outside the input path")
        relative = posixpath.relpath(location[2], root_location[2])
        relative_path = PurePosixPath(relative)
        if not _has_hidden_component(relative_path):
            files.add(relative_path.as_posix())
    return files


def _hadoop_path(spark, uri: str):
    configuration = spark.sparkContext._jsc.hadoopConfiguration()  # noqa: SLF001
    path = spark._jvm.org.apache.hadoop.fs.Path(uri)  # noqa: SLF001
    return path, path.getFileSystem(configuration)


def _write_bytes_no_overwrite(spark, uri: str, value: bytes) -> None:
    path, file_system = _hadoop_path(spark, uri)
    if file_system.exists(path):
        raise FileExistsError(f"input manifest already exists: {uri}")
    parent = path.getParent()
    if parent is None or not file_system.exists(parent):
        raise FileNotFoundError("input manifest parent directory does not exist")
    stream = file_system.create(path, False)
    write_error = None
    try:
        stream.write(bytearray(value))
    except BaseException as error:
        write_error = error
    finally:
        try:
            stream.close()
        except BaseException as error:
            if write_error is None:
                write_error = error

    if write_error is None:
        return

    try:
        deleted = file_system.delete(path, False)
    except BaseException as cleanup_error:
        cleanup_failure = ManifestCleanupError(
            f"failed to delete incomplete input manifest {uri}: {cleanup_error}"
        )
        raise write_error from cleanup_failure
    if not deleted:
        cleanup_failure = ManifestCleanupError(
            f"failed to delete incomplete input manifest: {uri}"
        )
        raise write_error from cleanup_failure
    raise write_error
