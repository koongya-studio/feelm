"""Transfer immutable artifact versions from local disk to local storage or HDFS.

``manifest`` hashes every visible file of local artifact directories.
``publish`` checks a staged copy (uploaded next to its final path) against that
manifest and publishes it with the same no-overwrite rename as the artifact
writers.  Shell ``hdfs dfs -mv`` must not be used because it moves a directory
*into* an existing destination instead of failing.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
from urllib.parse import urlsplit

from batch.train.artifacts import _hadoop_path, _path_exists, _publish_directory
from common.spark import create_spark_session

MANIFEST_SCHEMA_VERSION = 1
SUPPORTED_SCHEMES = {"", "file", "hdfs"}


def is_contract_file(relative_path: str) -> bool:
    """Cover every artifact file except Hadoop checksum sidecars.

    ``_SUCCESS`` and any other marker carries publication meaning, so it is part
    of the manifest: a staged copy that lost it, or that carries an extra hidden
    file, must not publish.  ``.crc`` sidecars are local ``ChecksumFileSystem``
    bookkeeping that never reaches the destination, so both sides ignore them.
    """
    parts = [part for part in relative_path.split("/") if part]
    return bool(parts) and not parts[-1].endswith(".crc")


def build_manifest(root: Path, artifacts: dict[str, str]) -> dict[str, object]:
    """Return relative path, byte size and SHA-256 of every artifact file."""
    manifest: dict[str, object] = {"schema_version": MANIFEST_SCHEMA_VERSION, "artifacts": {}}
    for name, relative in sorted(artifacts.items()):
        base = root / relative
        if not base.is_dir():
            raise FileNotFoundError(f"artifact directory does not exist: {relative}")
        files = []
        for directory, dirnames, filenames in os.walk(base):
            dirnames.sort()
            for filename in sorted(filenames):
                path = Path(directory) / filename
                file_relative = path.relative_to(base).as_posix()
                if is_contract_file(file_relative):
                    files.append(
                        {
                            "path": file_relative,
                            "bytes": path.stat().st_size,
                            "sha256": _local_sha256(path),
                        }
                    )
        if not files:
            raise ValueError(f"artifact directory has no files: {relative}")
        manifest["artifacts"][name] = {
            "path": relative,
            "file_count": len(files),
            "bytes": sum(entry["bytes"] for entry in files),
            "files": files,
        }
    return manifest


def publish_staged_artifact(
    spark,
    *,
    staged_uri: str,
    final_uri: str,
    manifest: dict[str, object],
    artifact: str,
    verify_sha256: bool = True,
) -> str:
    """Verify a staged upload against the manifest, then publish without overwrite.

    The contract requires the file list, byte sizes *and* SHA-256 to match, so
    digests are compared by default; ``verify_sha256=False`` is an explicit
    operator choice that only sizes are checked.
    """
    _validate_uri(staged_uri, "staged URI")
    _validate_uri(final_uri, "final URI")
    staged_parent, staged_name = staged_uri.rstrip("/").rsplit("/", 1)
    final_parent, final_name = final_uri.rstrip("/").rsplit("/", 1)
    if staged_parent != final_parent or staged_name == final_name:
        raise ValueError("staged and final URIs must be different siblings of one parent")
    if final_name.startswith((".", "_")):
        raise ValueError("final artifact name must not be hidden")
    entries = manifest.get("artifacts", {}).get(artifact) if isinstance(manifest, dict) else None
    if manifest.get("schema_version") != MANIFEST_SCHEMA_VERSION or not entries:
        raise ValueError(f"manifest does not describe artifact {artifact}")
    if _path_exists(spark, final_uri):
        raise FileExistsError(f"artifact version already exists: {final_uri}")
    if not _path_exists(spark, staged_uri):
        raise FileNotFoundError(f"staged artifact does not exist: {staged_uri}")

    expected = {entry["path"]: entry for entry in entries["files"]}
    actual = _staged_files(spark, staged_uri)
    if set(actual) != set(expected):
        missing = sorted(set(expected) - set(actual))[:3]
        extra = sorted(set(actual) - set(expected))[:3]
        raise ValueError(
            f"staged file list differs from manifest (missing={missing}, extra={extra})"
        )
    for relative, (size, path) in sorted(actual.items()):
        if size != expected[relative]["bytes"]:
            raise ValueError(f"staged file size differs from manifest: {relative}")
        if verify_sha256 and (
            _hadoop_sha256(spark, staged_uri, path) != expected[relative]["sha256"]
        ):
            raise ValueError(f"staged file SHA-256 differs from manifest: {relative}")
    _publish_directory(spark, staged_uri, final_uri)
    if not _path_exists(spark, final_uri) or _path_exists(spark, staged_uri):
        raise OSError(f"artifact publication did not complete: {final_uri}")
    return final_uri


def _staged_files(spark, staged_uri: str) -> dict[str, tuple[int, object]]:
    path, file_system, _ = _hadoop_path(spark, staged_uri)
    base = file_system.makeQualified(path).toUri().getPath().rstrip("/")
    files: dict[str, tuple[int, object]] = {}
    iterator = file_system.listFiles(path, True)
    while iterator.hasNext():
        status = iterator.next()
        full = status.getPath().toUri().getPath()
        relative = full[len(base) :].lstrip("/")
        if is_contract_file(relative):
            files[relative] = (int(status.getLen()), status.getPath())
    return files


def _hadoop_sha256(spark, staged_uri: str, path) -> str:
    _, file_system, _ = _hadoop_path(spark, staged_uri)
    jvm = spark._jvm  # noqa: SLF001
    digest = jvm.java.security.MessageDigest.getInstance("SHA-256")
    stream = file_system.open(path)
    try:
        digest_stream = jvm.java.security.DigestInputStream(stream, digest)
        digest_stream.transferTo(jvm.java.io.OutputStream.nullOutputStream())
    finally:
        stream.close()
    return bytes(digest.digest()).hex()


def _local_sha256(path: Path) -> str:
    sha = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            sha.update(block)
    return sha.hexdigest()


def _validate_uri(uri: str, label: str) -> None:
    parsed = urlsplit(uri or "")
    if not uri or parsed.scheme not in SUPPORTED_SCHEMES or parsed.query or parsed.fragment:
        raise ValueError(f"{label} must be a local or hdfs path")
    if len([segment for segment in parsed.path.split("/") if segment]) < 2:
        raise ValueError(f"{label} must name a versioned artifact directory")


def main(arguments: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    manifest_parser = commands.add_parser("manifest", help="hash local artifact directories")
    manifest_parser.add_argument("--root", required=True, help="local bundle root directory")
    manifest_parser.add_argument(
        "--artifact", action="append", required=True, help="name=relative/path (repeatable)"
    )
    manifest_parser.add_argument("--output", required=True, help="manifest JSON path")
    publish_parser = commands.add_parser("publish", help="verify a staged copy and publish it")
    publish_parser.add_argument("--manifest", required=True)
    publish_parser.add_argument("--artifact", required=True)
    publish_parser.add_argument("--staged-uri", required=True)
    publish_parser.add_argument("--final-uri", required=True)
    publish_parser.add_argument(
        "--skip-sha256-verify",
        action="store_true",
        help="compare sizes only (contract requires digests; use only with a recorded reason)",
    )
    publish_parser.add_argument("--spark-master")
    args = parser.parse_args(arguments)

    if args.command == "manifest":
        artifacts = {}
        for value in args.artifact:
            name, separator, relative = value.partition("=")
            if not separator or not name or not relative or name in artifacts:
                raise ValueError("--artifact must be a unique name=relative/path")
            artifacts[name] = relative
        manifest = build_manifest(Path(args.root), artifacts)
        output = Path(args.output)
        if output.exists():
            raise FileExistsError(f"manifest already exists: {output}")
        output.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({name: item["bytes"] for name, item in manifest["artifacts"].items()}))
        return

    manifest = json.loads(Path(args.manifest).read_text(encoding="utf-8"))
    spark = create_spark_session(args.spark_master, "feelm-artifact-transfer")
    try:
        print(
            publish_staged_artifact(
                spark,
                staged_uri=args.staged_uri,
                final_uri=args.final_uri,
                manifest=manifest,
                artifact=args.artifact,
                verify_sha256=not args.skip_sha256_verify,
            )
        )
    finally:
        spark.stop()


if __name__ == "__main__":
    main()
