"""Manifest and no-overwrite publication tests for the artifact transfer tool."""

from __future__ import annotations

import json
import shutil
from pathlib import Path
from unittest.mock import Mock

import pytest
from pyspark.sql import SparkSession

from jobs.batch import artifact_transfer
from jobs.batch.artifact_transfer import build_manifest, main, publish_staged_artifact

ARTIFACT = "content"
RELATIVE = "content/content-fixture-v1"


@pytest.fixture(scope="module")
def spark():
    session = (
        SparkSession.builder.master("local[1]")
        .appName("artifact-transfer-test")
        .config("spark.ui.enabled", "false")
        .getOrCreate()
    )
    session.sparkContext.setLogLevel("ERROR")
    yield session
    session.stop()


def _artifact_root(tmp_path: Path) -> Path:
    root = tmp_path / "bundle"
    version = root / RELATIVE
    (version / "metadata").mkdir(parents=True)
    (version / "features").mkdir(parents=True)
    (version / "features" / "part-0.parquet").write_bytes(b"features-0")
    (version / "metadata" / "part-0.parquet").write_bytes(b"metadata-0")
    # _SUCCESS carries publication meaning and belongs to the manifest; the
    # .crc sidecar is local checksum bookkeeping that never reaches HDFS.
    (version / "features" / "_SUCCESS").write_bytes(b"")
    (version / "features" / ".part-0.parquet.crc").write_bytes(b"crc")
    return root


def test_manifest_covers_every_file_except_crc_sidecars(tmp_path) -> None:
    root = _artifact_root(tmp_path)

    manifest = build_manifest(root, {ARTIFACT: RELATIVE})
    entry = manifest["artifacts"][ARTIFACT]

    assert [file["path"] for file in entry["files"]] == [
        "features/_SUCCESS",
        "features/part-0.parquet",
        "metadata/part-0.parquet",
    ]
    assert entry["file_count"] == 3
    assert entry["bytes"] == len(b"features-0") + len(b"metadata-0")
    assert all(len(file["sha256"]) == 64 for file in entry["files"])

    (root / "empty").mkdir()
    with pytest.raises(ValueError, match="no files"):
        build_manifest(root, {"empty": "empty"})
    with pytest.raises(FileNotFoundError, match="does not exist"):
        build_manifest(root, {"missing": "content/absent"})


def test_manifest_command_writes_json_without_overwriting(tmp_path, capsys) -> None:
    root = _artifact_root(tmp_path)
    output = tmp_path / "manifest.json"

    main(
        [
            "manifest",
            "--root",
            str(root),
            "--artifact",
            f"{ARTIFACT}={RELATIVE}",
            "--output",
            str(output),
        ]
    )

    manifest = json.loads(output.read_text(encoding="utf-8"))
    assert manifest["artifacts"][ARTIFACT]["file_count"] == 3
    assert json.loads(capsys.readouterr().out)[ARTIFACT] == manifest["artifacts"][ARTIFACT]["bytes"]
    with pytest.raises(FileExistsError, match="manifest already exists"):
        main(
            [
                "manifest",
                "--root",
                str(root),
                "--artifact",
                f"{ARTIFACT}={RELATIVE}",
                "--output",
                str(output),
            ]
        )


def _staged(tmp_path: Path, root: Path, suffix: str = "0") -> tuple[Path, Path]:
    """Copy the local artifact next to where it will be published."""
    parent = tmp_path / "remote" / "content"
    staged = parent / f".content-fixture-v1.tmp-{suffix}"
    shutil.copytree(root / RELATIVE, staged)
    return staged, parent / "content-fixture-v1"


def test_publish_verifies_staged_copy_then_renames(spark, tmp_path) -> None:
    root = _artifact_root(tmp_path)
    manifest = build_manifest(root, {ARTIFACT: RELATIVE})
    staged, final = _staged(tmp_path, root)

    published = publish_staged_artifact(
        spark,
        staged_uri=staged.as_uri(),
        final_uri=final.as_uri(),
        manifest=manifest,
        artifact=ARTIFACT,
    )

    assert published == final.as_uri()
    assert (final / "features" / "part-0.parquet").read_bytes() == b"features-0"
    assert not staged.exists()

    staged_again, _ = _staged(tmp_path, root, "1")
    with pytest.raises(FileExistsError, match="already exists"):
        publish_staged_artifact(
            spark,
            staged_uri=staged_again.as_uri(),
            final_uri=final.as_uri(),
            manifest=manifest,
            artifact=ARTIFACT,
            verify_sha256=False,
        )
    assert (final / "features" / "part-0.parquet").read_bytes() == b"features-0"


@pytest.mark.parametrize(
    "case",
    [
        "missing_file",
        "missing_success_marker",
        "extra_file",
        "extra_hidden_file",
        "size",
        "sha256",
        "not_sibling",
        "hidden_final",
    ],
)
def test_publish_rejects_staged_copies_that_differ(spark, tmp_path, case) -> None:
    root = _artifact_root(tmp_path)
    manifest = build_manifest(root, {ARTIFACT: RELATIVE})
    staged, final = _staged(tmp_path, root)
    staged_uri, final_uri = staged.as_uri(), final.as_uri()

    if case == "missing_file":
        (staged / "metadata" / "part-0.parquet").unlink()
        message = "staged file list differs from manifest"
    elif case == "missing_success_marker":
        # An upload that dropped the completion marker is not the same artifact.
        (staged / "features" / "_SUCCESS").unlink()
        message = "staged file list differs from manifest"
    elif case == "extra_file":
        (staged / "metadata" / "part-1.parquet").write_bytes(b"extra")
        message = "staged file list differs from manifest"
    elif case == "extra_hidden_file":
        (staged / ".shadow").mkdir()
        (staged / ".shadow" / "a.parquet").write_bytes(b"shadow")
        message = "staged file list differs from manifest"
    elif case == "size":
        (staged / "features" / "part-0.parquet").write_bytes(b"features-0-longer")
        message = "staged file size differs from manifest"
    elif case == "sha256":
        # Same byte count, different content: only the digest can detect it, and
        # the digest must be compared without asking for it.
        (staged / "features" / "part-0.parquet").write_bytes(b"features-1")
        message = "staged file SHA-256 differs from manifest"
    elif case == "not_sibling":
        staged_uri = (staged.parent.parent / ".elsewhere.tmp-0").as_uri()
        shutil.copytree(staged, staged.parent.parent / ".elsewhere.tmp-0")
        message = "different siblings of one parent"
    else:
        final_uri = (final.parent / ".content-fixture-v1").as_uri()
        message = "final artifact name must not be hidden"

    with pytest.raises(ValueError, match=message):
        publish_staged_artifact(
            spark,
            staged_uri=staged_uri,
            final_uri=final_uri,
            manifest=manifest,
            artifact=ARTIFACT,
        )
    assert not final.exists()


def test_publish_command_verifies_digests_unless_explicitly_skipped(tmp_path, monkeypatch) -> None:
    root = _artifact_root(tmp_path)
    manifest_path = tmp_path / "manifest.json"
    manifest_path.write_text(
        json.dumps(build_manifest(root, {ARTIFACT: RELATIVE})), encoding="utf-8"
    )
    publish = Mock(return_value="published")
    monkeypatch.setattr(artifact_transfer, "create_spark_session", Mock())
    monkeypatch.setattr(artifact_transfer, "publish_staged_artifact", publish)
    arguments = [
        "publish",
        "--manifest",
        str(manifest_path),
        "--artifact",
        ARTIFACT,
        "--staged-uri",
        "hdfs://namenode/artifacts/content/.v1.staging",
        "--final-uri",
        "hdfs://namenode/artifacts/content/v1",
    ]

    main(arguments)
    assert publish.call_args.kwargs["verify_sha256"] is True

    main([*arguments, "--skip-sha256-verify"])
    assert publish.call_args.kwargs["verify_sha256"] is False


def test_publish_rejects_a_manifest_without_the_artifact(spark, tmp_path) -> None:
    root = _artifact_root(tmp_path)
    manifest = build_manifest(root, {ARTIFACT: RELATIVE})
    staged, final = _staged(tmp_path, root)

    with pytest.raises(ValueError, match="manifest does not describe artifact als"):
        publish_staged_artifact(
            spark,
            staged_uri=staged.as_uri(),
            final_uri=final.as_uri(),
            manifest=manifest,
            artifact="als",
            verify_sha256=False,
        )
    with pytest.raises(ValueError, match="manifest does not describe artifact"):
        publish_staged_artifact(
            spark,
            staged_uri=staged.as_uri(),
            final_uri=final.as_uri(),
            manifest={"schema_version": 0, "artifacts": manifest["artifacts"]},
            artifact=ARTIFACT,
            verify_sha256=False,
        )
    assert not final.exists()
