"""Job ownership and preflight requirements for S15P21E106-119."""

from dataclasses import replace
from types import SimpleNamespace
from unittest.mock import Mock

import pytest

from batch.train.settings import AlsJobSettings
from jobs.batch import als_baseline as job


@pytest.fixture
def job_context(monkeypatch):
    settings = AlsJobSettings.from_sources(environ={}, argv=["--input-uri", "/fixture/v1"])
    monkeypatch.setattr(job.AlsJobSettings, "from_sources", lambda: settings)
    session = Mock()
    create = Mock(return_value=session)
    load = Mock(
        return_value=SimpleNamespace(
            ratings_count=60,
            service_movie_count=14,
            matched_service_movie_count=12,
        )
    )
    monkeypatch.setattr(job, "create_spark_session", create)
    monkeypatch.setattr(job, "load_training_inputs", load)
    return session, create, load


def test_existing_validation_reads_inputs_and_stops_session(job_context, capsys):
    session, create, load = job_context
    job.main()
    create.assert_called_once()
    load.assert_called_once_with(
        session,
        "/fixture/v1/ratings",
        "/fixture/v1/service-movie-id-map",
    )
    assert "ratings=60" in capsys.readouterr().out
    session.stop.assert_called_once()


def test_input_failure_is_propagated_and_session_stopped(job_context):
    session, _, load = job_context
    error = ValueError("invalid input fixture")
    load.side_effect = error
    with pytest.raises(ValueError) as caught:
        job.main()
    assert caught.value is error
    session.stop.assert_called_once()


def test_session_configuration_failure_also_stops_session(job_context):
    session, _, load = job_context
    error = RuntimeError("session configuration failed")
    session.conf.set.side_effect = error
    with pytest.raises(RuntimeError) as caught:
        job.main()
    assert caught.value is error
    load.assert_not_called()
    session.stop.assert_called_once()


def test_cleanup_failure_does_not_replace_original_error(job_context, caplog):
    session, _, load = job_context
    original = ValueError("invalid input fixture")
    load.side_effect = original
    session.stop.side_effect = RuntimeError("cleanup failed")
    with pytest.raises(ValueError) as caught:
        job.main()
    assert caught.value is original
    assert "cleanup failed" in caplog.text


def test_missing_input_is_rejected_before_session_creation(monkeypatch, job_context):
    _, create, load = job_context
    settings = replace(AlsJobSettings.from_sources(), input_uri=None)
    monkeypatch.setattr(job.AlsJobSettings, "from_sources", lambda: settings)
    with pytest.raises(ValueError, match="input URI is required"):
        job.main()
    create.assert_not_called()
    load.assert_not_called()


def test_run_mode_dispatches_validated_inputs_to_orchestrator(monkeypatch):
    session = Mock()
    inputs = object()
    context = SimpleNamespace(
        input_manifest_uri="/snapshots/input-v1/manifest.json",
        input_snapshot="sha256:" + "1" * 64,
    )
    settings = SimpleNamespace(
        mode="run",
        spark_master="local[1]",
        spark_app_name="als-run",
        input_uri="/input",
        require_input_uris=lambda: ("/input/ratings", "/input/service-movie-id-map"),
        require_run_context=Mock(return_value=context),
    )
    monkeypatch.setattr(job.AlsJobSettings, "from_sources", lambda: settings)
    monkeypatch.setattr(job, "create_spark_session", Mock(return_value=session))
    monkeypatch.setattr(job, "load_training_inputs", Mock(return_value=inputs))
    verify = Mock()
    monkeypatch.setattr(job, "validate_input_snapshot", verify, raising=False)
    run = Mock()
    monkeypatch.setattr(job, "run_baseline", run, raising=False)

    job.main()

    settings.require_run_context.assert_called_once()
    verify.assert_called_once_with(
        session,
        input_uri="/input",
        manifest_uri=context.input_manifest_uri,
        expected_snapshot=context.input_snapshot,
        inputs=inputs,
    )
    run.assert_called_once_with(session, inputs, settings)
    session.stop.assert_called_once()


def test_run_preflight_failure_occurs_before_session_creation(monkeypatch):
    error = ValueError("output URI is required")
    settings = SimpleNamespace(
        mode="run",
        require_input_uris=lambda: ("/input/ratings", "/input/service-movie-id-map"),
        require_run_context=Mock(side_effect=error),
    )
    create = Mock()
    monkeypatch.setattr(job.AlsJobSettings, "from_sources", lambda: settings)
    monkeypatch.setattr(job, "create_spark_session", create)

    with pytest.raises(ValueError) as caught:
        job.main()
    assert caught.value is error
    create.assert_not_called()


def test_manifest_mismatch_stops_before_orchestration(monkeypatch):
    session = Mock()
    inputs = object()
    context = SimpleNamespace(
        input_manifest_uri="/snapshots/input-v1/manifest.json",
        input_snapshot="sha256:" + "1" * 64,
    )
    settings = SimpleNamespace(
        mode="run",
        spark_master="local[1]",
        spark_app_name="als-run",
        input_uri="/input",
        require_input_uris=lambda: ("/input/ratings", "/input/service-movie-id-map"),
        require_run_context=Mock(return_value=context),
    )
    mismatch = ValueError("input manifest hash does not match input snapshot")
    verify = Mock(side_effect=mismatch)
    run = Mock()
    monkeypatch.setattr(job.AlsJobSettings, "from_sources", lambda: settings)
    monkeypatch.setattr(job, "create_spark_session", Mock(return_value=session))
    monkeypatch.setattr(job, "load_training_inputs", Mock(return_value=inputs))
    monkeypatch.setattr(job, "validate_input_snapshot", verify, raising=False)
    monkeypatch.setattr(job, "run_baseline", run, raising=False)

    with pytest.raises(ValueError) as caught:
        job.main()

    assert caught.value is mismatch
    verify.assert_called_once_with(
        session,
        input_uri="/input",
        manifest_uri=context.input_manifest_uri,
        expected_snapshot=context.input_snapshot,
        inputs=inputs,
    )
    run.assert_not_called()
    session.stop.assert_called_once()
