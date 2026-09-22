"""Atomic local/HDFS publication for benchmark datasets and reports."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
from uuid import uuid4

from py4j.protocol import Py4JJavaError


def publish_directory(spark, temporary_uri: str, final_uri: str) -> None:
    source, source_fs, configuration = hadoop_path(spark, temporary_uri)
    destination, destination_fs, _ = hadoop_path(spark, final_uri)
    if source_fs.getUri().toString() != destination_fs.getUri().toString():
        raise ValueError("temporary and final benchmark paths must use the same filesystem")
    scheme = str(source_fs.getUri().getScheme())
    if scheme == "file":
        _publish_local(
            Path(source_fs.makeQualified(source).toUri().getPath()),
            Path(destination_fs.makeQualified(destination).toUri().getPath()),
        )
        return
    if scheme != "hdfs":
        raise ValueError("benchmark outputs support only local and hdfs filesystems")
    context = spark._jvm.org.apache.hadoop.fs.FileContext.getFileContext(  # noqa: SLF001
        source_fs.getUri(), configuration
    )
    options = spark.sparkContext._gateway.new_array(  # noqa: SLF001
        spark._jvm.org.apache.hadoop.fs.Options.Rename, 1  # noqa: SLF001
    )
    options[0] = spark._jvm.org.apache.hadoop.fs.Options.Rename.NONE  # noqa: SLF001
    try:
        context.rename(source, destination, options)
    except Py4JJavaError as error:
        if destination_fs.exists(destination):
            raise FileExistsError(f"benchmark output already exists: {final_uri}") from error
        raise OSError(f"failed to publish benchmark output: {final_uri}") from error


def stage_uri(final_uri: str) -> str:
    stripped = final_uri.rstrip("/")
    name = stripped.rsplit("/", 1)[-1]
    parent = stripped.rsplit("/", 1)[0]
    return f"{parent}/.{name}.tmp-{uuid4().hex}"


def write_json(spark, uri: str, value: object) -> None:
    path, file_system, _ = hadoop_path(spark, uri)
    stream = file_system.create(path, False)
    try:
        payload = json.dumps(
            value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False
        )
        stream.write(bytearray((payload + "\n").encode("utf-8")))
    finally:
        stream.close()


def read_json(spark, uri: str) -> dict[str, object]:
    text = read_bytes(spark, uri).decode("utf-8")
    value = json.loads(text)
    if not isinstance(value, dict):
        raise ValueError("benchmark JSON must contain an object")
    return value


def read_bytes(spark, uri: str) -> bytes:
    path, file_system, _ = hadoop_path(spark, uri)
    if not file_system.exists(path):
        raise FileNotFoundError(f"benchmark file does not exist: {uri}")
    stream = file_system.open(path)
    try:
        return bytes(spark._jvm.org.apache.commons.io.IOUtils.toByteArray(stream))  # noqa: SLF001
    finally:
        stream.close()


def sha256_uri(spark, uri: str) -> str:
    path, file_system, _ = hadoop_path(spark, uri)
    stream = file_system.open(path)
    try:
        return str(spark._jvm.org.apache.commons.codec.digest.DigestUtils.sha256Hex(stream))  # noqa: SLF001
    finally:
        stream.close()


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def visible_files(spark, uri: str) -> dict[str, str]:
    root_path, file_system, _ = hadoop_path(spark, uri)
    root = file_system.makeQualified(root_path).toUri().normalize()
    root_path_text = str(root.getPath()).rstrip("/")
    files: dict[str, str] = {}
    iterator = file_system.listFiles(root_path, True)
    while iterator.hasNext():
        status = iterator.next()
        qualified = file_system.makeQualified(status.getPath()).toUri().normalize()
        absolute = str(qualified.getPath())
        relative = absolute[len(root_path_text) :].lstrip("/")
        if any(part.startswith((".", "_")) for part in relative.split("/")):
            continue
        files[relative] = sha256_uri(spark, status.getPath().toString())
    return files


def delete_owned_path(spark, uri: str) -> None:
    path, file_system, _ = hadoop_path(spark, uri)
    if file_system.exists(path) and not file_system.delete(path, True):
        raise OSError(f"failed to remove temporary benchmark path: {uri}")


def hadoop_path(spark, uri: str):
    configuration = spark.sparkContext._jsc.hadoopConfiguration()  # noqa: SLF001
    path = spark._jvm.org.apache.hadoop.fs.Path(uri)  # noqa: SLF001
    return path, path.getFileSystem(configuration), configuration


def _publish_local(source: Path, destination: Path) -> None:
    import fcntl

    destination.parent.mkdir(parents=True, exist_ok=True)
    parent_fd = os.open(destination.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        fcntl.flock(parent_fd, fcntl.LOCK_EX)
        if os.path.lexists(destination):
            raise FileExistsError(f"benchmark output already exists: {destination}")
        os.rename(source, destination)
    finally:
        os.close(parent_fd)
