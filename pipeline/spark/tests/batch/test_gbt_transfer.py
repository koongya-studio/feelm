"""626: byte-only GBT inventory through the existing Spark publication path."""

import shutil

import pytest
from pyspark.sql import SparkSession

from jobs.batch.artifact_transfer import build_manifest, publish_staged_artifact


@pytest.fixture(scope="module")
def spark():
    session = SparkSession.builder.master("local[1]").appName("gbt-transfer-test").getOrCreate()
    session.sparkContext.setLogLevel("ERROR")
    yield session
    session.stop()


@pytest.mark.parametrize("attack", ["missing", "extra", "size", "sha256", "none"])
def test_gbt_transfer_manifest_rejects_bad_stage(spark, tmp_path, attack):
    root = tmp_path / "source"
    source = root / "gbt" / "fixture"
    source.mkdir(parents=True)
    for name in ("gbt.json", "catalog.parquet", "feature-schema.json", "manifest.json", "_SUCCESS"):
        (source / name).write_bytes(b"fixture-content")
    manifest = build_manifest(root, {"gbt": "gbt/fixture"})
    assert manifest["artifacts"]["gbt"]["file_count"] == 5
    stage, final = tmp_path / ".stage", tmp_path / "final"
    shutil.copytree(source, stage)
    if attack == "missing":
        (stage / "_SUCCESS").unlink()
    elif attack == "extra":
        (stage / "extra").write_bytes(b"x")
    elif attack == "size":
        (stage / "gbt.json").write_bytes(b"x")
    elif attack == "sha256":
        (stage / "gbt.json").write_bytes(b"fixture-contEnt")
    if attack != "none":
        with pytest.raises(ValueError):
            publish_staged_artifact(spark, staged_uri=stage.as_uri(), final_uri=final.as_uri(),
                                    manifest=manifest, artifact="gbt")
        assert not final.exists()
    else:
        publish_staged_artifact(spark, staged_uri=stage.as_uri(), final_uri=final.as_uri(),
                                manifest=manifest, artifact="gbt")
        assert not stage.exists()
        shutil.copytree(source, stage)
        with pytest.raises(FileExistsError):
            publish_staged_artifact(spark, staged_uri=stage.as_uri(), final_uri=final.as_uri(),
                                    manifest=manifest, artifact="gbt")
        assert (final / "gbt.json").read_bytes() == b"fixture-content"
