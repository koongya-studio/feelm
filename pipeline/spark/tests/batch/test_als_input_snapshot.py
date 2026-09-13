"""Immutable 118 input manifest validation for the ALS run entry point."""

import hashlib
import importlib
import json
from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from pyspark.sql import SparkSession


@pytest.fixture(scope="module")
def spark():
    session = (
        SparkSession.builder.master("local[1]")
        .appName("als-input-snapshot-test")
        .config("spark.ui.enabled", "false")
        .getOrCreate()
    )
    session.sparkContext.setLogLevel("ERROR")
    yield session
    session.stop()


def snapshot_module():
    return importlib.import_module("batch.train.input_snapshot")


def write_manifest(
    root,
    *,
    files=None,
    preprocessing_revision="fixture-revision",
    ratings_count=64,
    service_movie_count=14,
    matched_service_movie_count=13,
):
    input_root = root / "prepared"
    sources = {
        "ratings": ("sources/ratings.csv", b"source-ratings"),
        "movies": ("sources/movies.csv", b"source-movies"),
        "links": ("sources/links.csv", b"source-links"),
        "service_movie_export": ("sources/service-movies.csv", b"service-export"),
    }
    for relative, value in sources.values():
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(value)
    required = {
        "ratings": b"ratings",
        "movies": b"movies",
        "movie-id-map": b"movie-map",
        "service-movie-id-map": b"service-map",
        "validation-report": json.dumps(
            {
                "outputs": {
                    "ratings": ratings_count,
                    "service_movie_id_map": service_movie_count,
                },
                "service_mapping": {"matched": matched_service_movie_count},
                "all_checks_passed": True,
            }
        ).encode(),
    }
    for directory, value in required.items():
        path = input_root / directory / "part-00000"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(value)
    manifest_files = {
        path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted([*input_root.rglob("*"), *(root / "sources").rglob("*")])
        if path.is_file()
    }
    if files is not None:
        manifest_files = files
    manifest = {
        "files": manifest_files,
        "preprocessing_revision": preprocessing_revision,
        "preprocessing_settings": {
            "rating_min": 0.5,
            "rating_max": 5.0,
            "rating_step": 0.5,
            "latest_rating_per_user_movie": True,
        },
        "sources": {name: relative for name, (relative, _) in sources.items()},
    }
    serialized = json.dumps(manifest, sort_keys=True).encode()
    manifest_path = root / "manifest.json"
    manifest_path.write_bytes(serialized)
    return input_root, manifest_path, "sha256:" + hashlib.sha256(serialized).hexdigest()


def test_valid_snapshot_binds_all_five_118_outputs(spark, tmp_path):
    input_root, manifest_path, snapshot = write_manifest(tmp_path)

    manifest = snapshot_module().validate_input_snapshot(
        spark,
        input_uri=input_root.as_uri(),
        manifest_uri=manifest_path.as_uri(),
        expected_snapshot=snapshot,
        inputs=SimpleNamespace(
            ratings_count=64,
            service_movie_count=14,
            matched_service_movie_count=13,
        ),
    )

    assert manifest["preprocessing_revision"] == "fixture-revision"
    assert {
        path.split("/")[1]
        for path in manifest["files"]
        if path.startswith("prepared/")
    } == {
        "ratings",
        "movies",
        "movie-id-map",
        "service-movie-id-map",
        "validation-report",
    }
    assert set(manifest["sources"]) == {
        "ratings",
        "movies",
        "links",
        "service_movie_export",
    }
    assert manifest["preprocessing_settings"]["latest_rating_per_user_movie"] is True


def test_manifest_generator_creates_output_accepted_by_production_validator(spark, tmp_path):
    input_root, manifest_path, _ = write_manifest(tmp_path)
    manifest_path.unlink()

    snapshot = snapshot_module().create_input_manifest(
        spark,
        input_uri=input_root.as_uri(),
        manifest_uri=manifest_path.as_uri(),
        source_uris={
            "ratings": (tmp_path / "sources/ratings.csv").as_uri(),
            "movies": (tmp_path / "sources/movies.csv").as_uri(),
            "links": (tmp_path / "sources/links.csv").as_uri(),
            "service_movie_export": (tmp_path / "sources/service-movies.csv").as_uri(),
        },
        preprocessing_revision="fixture-revision",
        preprocessing_settings={"latest_rating_per_user_movie": True},
    )

    manifest = snapshot_module().validate_input_snapshot(
        spark,
        input_uri=input_root.as_uri(),
        manifest_uri=manifest_path.as_uri(),
        expected_snapshot=snapshot,
        inputs=SimpleNamespace(
            ratings_count=64,
            service_movie_count=14,
            matched_service_movie_count=13,
        ),
    )

    assert set(manifest["sources"]) == {
        "ratings",
        "movies",
        "links",
        "service_movie_export",
    }
    assert manifest["preprocessing_settings"] == {"latest_rating_per_user_movie": True}


@pytest.mark.parametrize("failure_point", ["write", "close"])
def test_manifest_write_failure_does_not_leave_the_final_path(
    monkeypatch, failure_point
):
    module = snapshot_module()
    final_path = Mock()
    parent_path = Mock()
    final_path.getParent.return_value = parent_path
    state = {"final_exists": False}

    file_system = Mock()
    file_system.exists.side_effect = lambda path: (
        True if path is parent_path else state["final_exists"]
    )

    stream = Mock()
    if failure_point == "write":
        stream.write.side_effect = OSError("injected manifest write failure")
    else:
        stream.close.side_effect = OSError("injected manifest close failure")

    def create(path, overwrite):
        assert path is final_path
        assert overwrite is False
        state["final_exists"] = True
        return stream

    def delete(path, recursive):
        assert path is final_path
        assert recursive is False
        state["final_exists"] = False
        return True

    file_system.create.side_effect = create
    file_system.delete.side_effect = delete
    monkeypatch.setattr(module, "_hadoop_path", lambda spark, uri: (final_path, file_system))

    with pytest.raises(OSError, match=failure_point):
        module._write_bytes_no_overwrite(Mock(), "file:///snapshot/manifest.json", b"manifest")

    assert state["final_exists"] is False


@pytest.mark.parametrize("cleanup_failure", [False, OSError("injected delete failure")])
def test_manifest_write_failure_preserves_write_and_cleanup_errors(
    monkeypatch, cleanup_failure
):
    module = snapshot_module()
    final_path = Mock()
    parent_path = Mock()
    final_path.getParent.return_value = parent_path

    file_system = Mock()
    file_system.exists.side_effect = lambda path: path is parent_path
    stream = Mock()
    stream.write.side_effect = OSError("injected manifest write failure")
    file_system.create.return_value = stream
    if cleanup_failure is False:
        file_system.delete.return_value = False
    else:
        file_system.delete.side_effect = cleanup_failure
    monkeypatch.setattr(module, "_hadoop_path", lambda spark, uri: (final_path, file_system))

    with pytest.raises(OSError, match="manifest write failure") as raised:
        module._write_bytes_no_overwrite(
            Mock(), "file:///snapshot/manifest.json", b"manifest"
        )

    assert isinstance(raised.value.__cause__, module.ManifestCleanupError)
    assert "delete" in str(raised.value.__cause__)


def test_snapshot_identifier_must_equal_manifest_content_hash(spark, tmp_path):
    input_root, manifest_path, _ = write_manifest(tmp_path)

    with pytest.raises(ValueError, match="(?i)manifest.*hash|snapshot"):
        snapshot_module().validate_input_snapshot(
            spark,
            input_uri=input_root.as_uri(),
            manifest_uri=manifest_path.as_uri(),
            expected_snapshot="sha256:" + "0" * 64,
            inputs=SimpleNamespace(
                ratings_count=64,
                service_movie_count=14,
                matched_service_movie_count=13,
            ),
        )


def test_changed_or_missing_input_file_is_rejected(spark, tmp_path):
    input_root, manifest_path, snapshot = write_manifest(tmp_path)
    (input_root / "ratings" / "part-00000").write_bytes(b"changed-ratings")

    with pytest.raises(ValueError, match="(?i)hash|changed"):
        snapshot_module().validate_input_snapshot(
            spark,
            input_uri=input_root.as_uri(),
            manifest_uri=manifest_path.as_uri(),
            expected_snapshot=snapshot,
            inputs=SimpleNamespace(
                ratings_count=64,
                service_movie_count=14,
                matched_service_movie_count=13,
            ),
        )


def test_manifest_must_cover_every_required_118_output(spark, tmp_path):
    input_root, manifest_path, snapshot = write_manifest(
        tmp_path,
        files={"prepared/ratings/part-00000": hashlib.sha256(b"ratings").hexdigest()},
    )

    with pytest.raises(ValueError, match="(?i)required|output|manifest"):
        snapshot_module().validate_input_snapshot(
            spark,
            input_uri=input_root.as_uri(),
            manifest_uri=manifest_path.as_uri(),
            expected_snapshot=snapshot,
            inputs=SimpleNamespace(
                ratings_count=64,
                service_movie_count=14,
                matched_service_movie_count=13,
            ),
        )


def test_validation_report_counts_must_match_loaded_inputs(spark, tmp_path):
    input_root, manifest_path, snapshot = write_manifest(tmp_path, ratings_count=63)

    with pytest.raises(ValueError, match="(?i)validation.*count|ratings.*count"):
        snapshot_module().validate_input_snapshot(
            spark,
            input_uri=input_root.as_uri(),
            manifest_uri=manifest_path.as_uri(),
            expected_snapshot=snapshot,
            inputs=SimpleNamespace(
                ratings_count=64,
                service_movie_count=14,
                matched_service_movie_count=13,
            ),
        )


def test_manifest_rejects_an_unlisted_visible_input_file(spark, tmp_path):
    input_root, manifest_path, snapshot = write_manifest(tmp_path)
    (input_root / "movies" / "part-00001.parquet").write_bytes(b"unlisted-movie-part")

    with pytest.raises(ValueError, match="(?i)unlisted|unexpected|file set|manifest"):
        snapshot_module().validate_input_snapshot(
            spark,
            input_uri=input_root.as_uri(),
            manifest_uri=manifest_path.as_uri(),
            expected_snapshot=snapshot,
            inputs=SimpleNamespace(
                ratings_count=64,
                service_movie_count=14,
                matched_service_movie_count=13,
            ),
        )


def test_manifest_rejects_a_listed_file_missing_from_the_input_tree(spark, tmp_path):
    input_root, manifest_path, snapshot = write_manifest(tmp_path)
    (input_root / "movies" / "part-00000").unlink()

    with pytest.raises(FileNotFoundError, match="(?i)manifest.*file|does not exist"):
        snapshot_module().validate_input_snapshot(
            spark,
            input_uri=input_root.as_uri(),
            manifest_uri=manifest_path.as_uri(),
            expected_snapshot=snapshot,
            inputs=SimpleNamespace(
                ratings_count=64,
                service_movie_count=14,
                matched_service_movie_count=13,
            ),
        )


@pytest.mark.parametrize(
    "source_name",
    ["ratings", "movies", "links", "service_movie_export"],
)
def test_manifest_requires_all_four_hashed_source_roles(spark, tmp_path, source_name):
    input_root, manifest_path, _ = write_manifest(tmp_path)
    manifest = json.loads(manifest_path.read_text())
    manifest["sources"].pop(source_name)
    serialized = json.dumps(manifest, sort_keys=True).encode()
    manifest_path.write_bytes(serialized)

    with pytest.raises(ValueError, match="(?i)source|ratings|movies|links|service.*export"):
        snapshot_module().validate_input_snapshot(
            spark,
            input_uri=input_root.as_uri(),
            manifest_uri=manifest_path.as_uri(),
            expected_snapshot="sha256:" + hashlib.sha256(serialized).hexdigest(),
            inputs=SimpleNamespace(
                ratings_count=64,
                service_movie_count=14,
                matched_service_movie_count=13,
            ),
        )


def test_each_source_role_must_reference_a_hashed_manifest_file(spark, tmp_path):
    input_root, manifest_path, _ = write_manifest(tmp_path)
    manifest = json.loads(manifest_path.read_text())
    manifest["sources"]["service_movie_export"] = "sources/unlisted-export.csv"
    (tmp_path / "sources" / "unlisted-export.csv").write_bytes(b"other-export")
    serialized = json.dumps(manifest, sort_keys=True).encode()
    manifest_path.write_bytes(serialized)

    with pytest.raises(ValueError, match="(?i)source|service.*export|manifest.*file"):
        snapshot_module().validate_input_snapshot(
            spark,
            input_uri=input_root.as_uri(),
            manifest_uri=manifest_path.as_uri(),
            expected_snapshot="sha256:" + hashlib.sha256(serialized).hexdigest(),
            inputs=SimpleNamespace(
                ratings_count=64,
                service_movie_count=14,
                matched_service_movie_count=13,
            ),
        )


@pytest.mark.parametrize("revision", [1, {"commit": "fixture-revision"}])
def test_manifest_requires_a_string_preprocessing_revision(spark, tmp_path, revision):
    input_root, manifest_path, _ = write_manifest(tmp_path)
    manifest = json.loads(manifest_path.read_text())
    manifest["preprocessing_revision"] = revision
    serialized = json.dumps(manifest, sort_keys=True).encode()
    manifest_path.write_bytes(serialized)

    with pytest.raises(ValueError, match="(?i)preprocessing.*revision"):
        snapshot_module().validate_input_snapshot(
            spark,
            input_uri=input_root.as_uri(),
            manifest_uri=manifest_path.as_uri(),
            expected_snapshot="sha256:" + hashlib.sha256(serialized).hexdigest(),
            inputs=SimpleNamespace(
                ratings_count=64,
                service_movie_count=14,
                matched_service_movie_count=13,
            ),
        )


@pytest.mark.parametrize("settings", [None, {}, [], "defaults"])
def test_manifest_requires_structured_preprocessing_settings(spark, tmp_path, settings):
    input_root, manifest_path, _ = write_manifest(tmp_path)
    manifest = json.loads(manifest_path.read_text())
    manifest["preprocessing_settings"] = settings
    serialized = json.dumps(manifest, sort_keys=True).encode()
    manifest_path.write_bytes(serialized)

    with pytest.raises(ValueError, match="(?i)preprocessing.*settings|configuration"):
        snapshot_module().validate_input_snapshot(
            spark,
            input_uri=input_root.as_uri(),
            manifest_uri=manifest_path.as_uri(),
            expected_snapshot="sha256:" + hashlib.sha256(serialized).hexdigest(),
            inputs=SimpleNamespace(
                ratings_count=64,
                service_movie_count=14,
                matched_service_movie_count=13,
            ),
        )


@pytest.mark.parametrize(
    "relative",
    [
        "prepared/ratings/_SUCCESS",
        "prepared/movies/.part-00000.crc",
        "prepared/movie-id-map/_temporary/attempt/part-00000",
    ],
)
def test_hidden_and_completion_markers_must_not_be_manifest_entries(
    spark, tmp_path, relative
):
    input_root, manifest_path, _ = write_manifest(tmp_path)
    marker = tmp_path / relative
    marker.parent.mkdir(parents=True, exist_ok=True)
    marker.write_bytes(b"marker")
    manifest = json.loads(manifest_path.read_text())
    manifest["files"][relative] = hashlib.sha256(marker.read_bytes()).hexdigest()
    serialized = json.dumps(manifest, sort_keys=True).encode()
    manifest_path.write_bytes(serialized)

    with pytest.raises(ValueError, match="(?i)hidden|marker|manifest"):
        snapshot_module().validate_input_snapshot(
            spark,
            input_uri=input_root.as_uri(),
            manifest_uri=manifest_path.as_uri(),
            expected_snapshot="sha256:" + hashlib.sha256(serialized).hexdigest(),
            inputs=SimpleNamespace(
                ratings_count=64,
                service_movie_count=14,
                matched_service_movie_count=13,
            ),
        )


def test_unlisted_hidden_and_completion_markers_are_ignored(spark, tmp_path):
    input_root, manifest_path, snapshot = write_manifest(tmp_path)
    for relative in (
        "ratings/_SUCCESS",
        "movies/.part-00000.crc",
        "movie-id-map/_temporary/attempt/part-00000",
    ):
        marker = input_root / relative
        marker.parent.mkdir(parents=True, exist_ok=True)
        marker.write_bytes(b"marker")

    manifest = snapshot_module().validate_input_snapshot(
        spark,
        input_uri=input_root.as_uri(),
        manifest_uri=manifest_path.as_uri(),
        expected_snapshot=snapshot,
        inputs=SimpleNamespace(
            ratings_count=64,
            service_movie_count=14,
            matched_service_movie_count=13,
        ),
    )

    assert not any(
        part.startswith((".", "_"))
        for relative in manifest["files"]
        for part in relative.split("/")
    )
