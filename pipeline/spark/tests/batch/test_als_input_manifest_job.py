"""Preflight and dispatch tests for the ALS input manifest generator."""

from types import SimpleNamespace
from unittest.mock import Mock

import pytest

from jobs.batch import als_input_manifest as job


def arguments(*overrides: str) -> list[str]:
    options = {
        "--input-uri": "file:///snapshot/prepared",
        "--manifest-uri": "file:///snapshot/manifest.json",
        "--ratings-source": "file:///snapshot/source/ratings.csv",
        "--movies-source": "file:///snapshot/source/movies.csv",
        "--links-source": "file:///snapshot/source/links.csv",
        "--service-movie-export": "file:///snapshot/source/service-movies.csv",
        "--preprocessing-revision": "abc123",
        "--preprocessing-settings-json": '{"write_mode":"errorifexists"}',
    }
    options.update(
        {overrides[index]: overrides[index + 1] for index in range(0, len(overrides), 2)}
    )
    return [value for pair in options.items() for value in pair]


def test_rejects_default_filesystem_uri_before_spark_creation(monkeypatch, capsys):
    create_spark = Mock()
    monkeypatch.setattr(job, "create_spark_session", create_spark)

    result = job.main(arguments("--input-uri", "/snapshot/prepared"))

    assert result == 1
    assert "explicit" in capsys.readouterr().err
    create_spark.assert_not_called()


def test_generates_then_validates_manifest_and_stops_spark(monkeypatch, capsys):
    spark = Mock()
    inputs = SimpleNamespace(ratings_count=64)
    create_manifest = Mock(return_value="sha256:" + "1" * 64)
    validate_manifest = Mock()
    monkeypatch.setattr(job, "create_spark_session", Mock(return_value=spark))
    monkeypatch.setattr(job, "load_training_inputs", Mock(return_value=inputs))
    monkeypatch.setattr(job, "create_input_manifest", create_manifest)
    monkeypatch.setattr(job, "validate_input_snapshot", validate_manifest)

    result = job.main(arguments())

    assert result == 0
    create_manifest.assert_called_once()
    validate_manifest.assert_called_once_with(
        spark,
        input_uri="file:///snapshot/prepared",
        manifest_uri="file:///snapshot/manifest.json",
        expected_snapshot="sha256:" + "1" * 64,
        inputs=inputs,
    )
    assert "input_snapshot" in capsys.readouterr().out
    spark.stop.assert_called_once()


def manifest_job_context(monkeypatch, *, validation_error):
    path = Mock()
    file_system = Mock()
    path.getFileSystem.return_value = file_system
    spark = Mock()
    spark._jvm.org.apache.hadoop.fs.Path.return_value = path
    monkeypatch.setattr(job, "create_spark_session", Mock(return_value=spark))
    monkeypatch.setattr(job, "load_training_inputs", Mock(return_value=object()))
    monkeypatch.setattr(
        job,
        "create_input_manifest",
        Mock(return_value="sha256:" + "1" * 64),
    )
    monkeypatch.setattr(
        job,
        "validate_input_snapshot",
        Mock(side_effect=validation_error),
    )
    return spark, file_system, path


def test_unexpected_spark_validation_failure_removes_owned_manifest(
    monkeypatch, capsys
):
    spark, file_system, path = manifest_job_context(
        monkeypatch,
        validation_error=RuntimeError("injected Spark validation failure"),
    )
    file_system.delete.return_value = True

    result = job.main(arguments())

    assert result == 1
    assert "Spark validation failure" in capsys.readouterr().err
    file_system.delete.assert_called_once_with(path, False)
    spark.stop.assert_called_once()


@pytest.mark.parametrize("cleanup_failure", [False, OSError("injected delete failure")])
def test_manifest_cleanup_failure_is_reported_without_hiding_validation_error(
    monkeypatch, capsys, cleanup_failure
):
    spark, file_system, path = manifest_job_context(
        monkeypatch,
        validation_error=ValueError("injected validation failure"),
    )
    if cleanup_failure is False:
        file_system.delete.return_value = False
    else:
        file_system.delete.side_effect = cleanup_failure

    result = job.main(arguments())

    assert result == 1
    error_output = capsys.readouterr().err
    assert "validation failure" in error_output
    assert "cleanup" in error_output.lower() or "delete" in error_output.lower()
    file_system.delete.assert_called_once_with(path, False)
    spark.stop.assert_called_once()


def test_stop_failure_does_not_replace_manifest_validation_error(
    monkeypatch, capsys, caplog
):
    spark, file_system, _ = manifest_job_context(
        monkeypatch,
        validation_error=ValueError("injected validation failure"),
    )
    file_system.delete.return_value = True
    spark.stop.side_effect = RuntimeError("injected stop failure")

    result = job.main(arguments())

    assert result == 1
    assert "validation failure" in capsys.readouterr().err
    assert "stop failure" in caplog.text


@pytest.mark.parametrize("cleanup_failure", [None, False, OSError("injected delete failure")])
def test_stop_failure_after_validation_cleans_up_owned_manifest(
    monkeypatch, capsys, cleanup_failure
):
    spark, file_system, path = manifest_job_context(
        monkeypatch,
        validation_error=None,
    )
    spark.stop.side_effect = RuntimeError("injected stop failure")
    if cleanup_failure is None:
        file_system.delete.return_value = True
    elif cleanup_failure is False:
        file_system.delete.return_value = False
    else:
        file_system.delete.side_effect = cleanup_failure

    result = job.main(arguments())

    assert result == 1
    error_output = capsys.readouterr().err
    assert "stop failure" in error_output
    if cleanup_failure is not None:
        assert "cleanup" in error_output.lower() or "delete" in error_output.lower()
    file_system.delete.assert_called_once_with(path, False)


def test_stop_failure_cleans_up_after_spark_context_is_invalidated(monkeypatch, capsys):
    spark, file_system, path = manifest_job_context(
        monkeypatch,
        validation_error=None,
    )
    file_system.delete.return_value = True

    def invalidate_context_then_fail():
        spark.sparkContext._jsc = None
        raise RuntimeError("injected stop failure after context invalidation")

    spark.stop.side_effect = invalidate_context_then_fail

    result = job.main(arguments())

    assert result == 1
    error_output = capsys.readouterr().err
    assert "stop failure after context invalidation" in error_output
    assert "cleanup failed" not in error_output
    file_system.delete.assert_called_once_with(path, False)


@pytest.mark.parametrize("cleanup_failure", [False, OSError("injected delete failure")])
def test_manifest_write_cleanup_failure_is_reported_with_write_error(
    monkeypatch, capsys, cleanup_failure
):
    spark = Mock()
    monkeypatch.setattr(job, "create_spark_session", Mock(return_value=spark))
    monkeypatch.setattr(job, "load_training_inputs", Mock(return_value=object()))

    cleanup_message = (
        "manifest cleanup delete returned false"
        if cleanup_failure is False
        else str(cleanup_failure)
    )

    def fail_manifest_creation(*args, **kwargs):
        cleanup_error = job.ManifestCleanupError(cleanup_message)
        raise OSError("injected manifest write failure") from cleanup_error

    monkeypatch.setattr(job, "create_input_manifest", fail_manifest_creation)

    result = job.main(arguments())

    assert result == 1
    error_output = capsys.readouterr().err
    assert "manifest write failure" in error_output
    assert cleanup_message in error_output
