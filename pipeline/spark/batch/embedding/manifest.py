"""File inventory and checksums for an immutable embedding artifact."""

from __future__ import annotations

import hashlib
import json

from pyspark.sql import SparkSession

from batch.train.artifacts import _hadoop_path

CONTRACT_VERSION = "movie-embeddings-v1"


def file_checksums(spark: SparkSession, version_uri: str) -> dict[str, str]:
    """Hash every Parquet data file, including files in nested partitions."""
    root, filesystem, _ = _hadoop_path(spark, version_uri)
    checksums: dict[str, str] = {}
    for directory in ("embeddings", "metadata"):
        path = spark._jvm.org.apache.hadoop.fs.Path(root, directory)  # noqa: SLF001
        if not filesystem.exists(path):
            raise ValueError(f"embedding artifact is missing {directory}")
        _collect_files(spark, filesystem, path, directory, checksums)
    if not any(path.startswith("embeddings/") for path in checksums):
        raise ValueError("embedding artifact has no embedding data files")
    if not any(path.startswith("metadata/") for path in checksums):
        raise ValueError("embedding artifact has no metadata data files")
    return dict(sorted(checksums.items()))


def _collect_files(spark, filesystem, directory, relative, checksums):
    for status in filesystem.listStatus(directory):
        path = status.getPath()
        name = path.getName()
        if name.startswith(".") or name.startswith("_"):
            continue
        child = f"{relative}/{name}"
        if status.isDirectory():
            _collect_files(spark, filesystem, path, child, checksums)
        elif status.isFile():
            digest = hashlib.sha256()
            stream = filesystem.open(path)
            buffer = spark.sparkContext._gateway.new_array(spark._jvm.byte, 64 * 1024)  # noqa: SLF001
            try:
                while (length := stream.read(buffer)) != -1:
                    digest.update(bytes((value + 256) % 256 for value in buffer[:length]))
            finally:
                stream.close()
            checksums[child] = digest.hexdigest()


def manifest_json(metadata, checksums: dict[str, str]) -> str:
    """Serialize the artifact's model and file lineage deterministically."""
    record = {
        "contract_version": CONTRACT_VERSION,
        "embedding_version": metadata["embedding_version"],
        "model_id": metadata["model_id"],
        "model_revision": metadata["model_revision"],
        "dimension": metadata["dimension"],
        "query_prefix": metadata["query_prefix"],
        "max_length": metadata["max_length"],
        "mapping_revision": metadata["mapping_revision"],
        "row_count": metadata["row_count"],
        "text_template_version": metadata["text_template_version"],
        "input_snapshot_sha256": metadata["input_snapshot_sha256"],
        "files": checksums,
    }
    return json.dumps(record, sort_keys=True, separators=(",", ":"))


def verify_manifest(spark: SparkSession, version_uri: str, metadata) -> None:
    manifest = spark.read.parquet(f"{version_uri}/manifest")
    if manifest.count() != 1:
        raise ValueError("embedding manifest must contain exactly one row")
    stored = manifest.first()["json"]
    try:
        record = json.loads(stored)
    except (TypeError, ValueError) as error:
        raise ValueError("embedding manifest is invalid JSON") from error
    if record.get("contract_version") != CONTRACT_VERSION:
        raise ValueError("embedding manifest contract version mismatch")
    try:
        actual = manifest_json(metadata, file_checksums(spark, version_uri))
    except Exception as error:
        raise ValueError("embedding manifest file checksum mismatch") from error
    if stored != actual:
        raise ValueError("embedding manifest or file checksum mismatch")
