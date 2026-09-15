"""Stage boundaries for selection, final evaluation, and serving retraining."""

import importlib
import logging
import traceback
from types import SimpleNamespace

import pytest

from batch.train.settings import AlsConfig


class Frame:
    def __init__(self, name):
        self.name = name
        self.persisted = False
        self.unpersisted = False

    def persist(self):
        self.persisted = True
        return self

    def unpersist(self):
        self.unpersisted = True

    def unionByName(self, other):
        return Frame(f"{self.name}+{other.name}")


def baseline_module():
    return importlib.import_module("batch.train.baseline")


def settings():
    return SimpleNamespace(
        input_uri="/input/v1",
        output_uri="/output",
        input_snapshot="sha256:fixture-v1",
        implementation_revision="abc123",
        movie_metadata_revision="sha256:" + "b" * 64,
        run_id="run-a1",
        seed=42,
        train_ratio=0.8,
        validation_ratio=0.1,
        positive_rating_threshold=4.0,
        candidate_size=500,
        user_chunk_size=2,
        als_configs=(AlsConfig(16, 0.1, 2), AlsConfig(32, 0.1, 2)),
    )


@pytest.fixture(autouse=True)
def reserve_run(monkeypatch):
    monkeypatch.setattr(baseline_module(), "reserve_run_report", lambda *_args, **_kwargs: None)


def test_stage_order_data_boundaries_reload_and_metric_attribution(monkeypatch):
    module = baseline_module()
    source, train, validation, test = map(Frame, ("source", "train", "validation", "test"))
    assignments = Frame("assignments")
    splits = SimpleNamespace(assignments=assignments, train=train, validation=validation, test=test)
    inputs = SimpleNamespace(
        ratings=source,
        service_movie_map=Frame("map"),
        ratings_count=64,
        mapping_revision="sha256:" + "a" * 64,
    )
    events = []
    artifact_revisions = []
    versions = iter(("selection-a", "selection-b", "final-b", "serving-b"))

    monkeypatch.setattr(module, "split_ratings", lambda *args, **kwargs: splits)
    monkeypatch.setattr(module, "summarize_splits", lambda value: SimpleNamespace(total_ratings=64))
    monkeypatch.setattr(module, "new_model_version", lambda *_: next(versions))

    def train_model(frame, config, seed):
        events.append(("train", frame.name, config.rank, seed))
        return SimpleNamespace(uid=f"fit-{len(events)}", rank=config.rank)

    monkeypatch.setattr(module, "train_als_model", train_model)

    def save(_spark, model, output_uri, metadata):
        artifact_revisions.append(
            (metadata.mapping_revision, metadata.movie_metadata_revision)
        )
        events.append(
            (
                "save",
                metadata.artifact_stage,
                metadata.model_version,
                metadata.training_ratings_count,
            )
        )
        return f"{output_uri}/als/{metadata.model_version}"

    monkeypatch.setattr(module, "save_als_artifacts", save)

    def load(_spark, _output_uri, version):
        events.append(("load", version))
        return SimpleNamespace(
            model=SimpleNamespace(uid=f"loaded-{version}"),
            metadata=SimpleNamespace(model_version=version),
        )

    monkeypatch.setattr(module, "load_als_artifacts", load)

    def evaluate(
        model,
        known,
        holdout,
        _movie_map,
        *,
        stage,
        model_version,
        user_chunk_size,
        seed,
        evidence_writer,
        **kwargs,
    ):
        events.append(
            (
                "evaluate",
                model.uid,
                known.name,
                holdout.name,
                stage,
                model_version,
                user_chunk_size,
                seed,
            )
        )
        score = 0.4 if model_version == "selection-b" else 0.2
        evidence_writer(
            chunk_id=0,
            candidates=Frame(f"{stage}-{model_version}-candidates"),
            per_user=Frame(f"{stage}-{model_version}-per-user"),
        )
        return SimpleNamespace(
            summary={
                "movielens": {
                    "evaluated_users": 5,
                    "ndcg_at_10": score,
                    "recall_at_10": score,
                },
                "service": {
                    "evaluated_users": 4,
                    "ndcg_at_10": score,
                    "recall_at_10": score,
                },
            }
        )

    def reject_legacy_evaluation(*_args, **_kwargs):
        raise AssertionError("run mode must not call evaluate_offline")

    monkeypatch.setattr(module, "evaluate_offline", reject_legacy_evaluation)
    monkeypatch.setattr(module, "evaluate_offline_chunked", evaluate, raising=False)

    evidence_calls = []

    class EvidenceWriter:
        def __init__(_self, _spark, output, run_id, *, stage, model_version):
            _self.output = output
            _self.run_id = run_id
            _self.stage = stage
            _self.model_version = model_version
            _self.chunks = []

        def __call__(_self, *, chunk_id, candidates, per_user):
            _self.chunks.append((chunk_id, candidates.name, per_user.name))

        def finalize(_self):
            evidence_calls.append(
                (
                    "finalize",
                    _self.stage,
                    _self.model_version,
                    tuple(_self.chunks),
                )
            )
            base = f"{_self.output}/als-runs/{_self.run_id}/{_self.stage}"
            return {
                "stage": _self.stage,
                "model_version": _self.model_version,
                "candidates_uri": f"{base}/candidates",
                "per_user_uri": f"{base}/per-user",
                "candidate_rows": 10,
                "per_user_rows": 4,
            }

        def abort(_self):
            evidence_calls.append(("abort", _self.stage, _self.model_version))

    monkeypatch.setattr(module, "ChunkedEvidenceWriter", EvidenceWriter, raising=False)

    def choose(candidates):
        events.append(("select", [item.model_version for item in candidates]))
        return SimpleNamespace(selected=candidates[1], policy={"catalog": "movielens"})

    monkeypatch.setattr(module, "select_best_candidate", choose)
    reports = []
    monkeypatch.setattr(
        module,
        "write_run_report",
        lambda spark, output, report, *, complete: reports.append((report, complete)),
    )

    result = module.run_baseline(object(), inputs, settings())

    assert assignments.persisted and assignments.unpersisted
    assert [event for event in events if event[0] == "train"] == [
        ("train", "train", 16, 42),
        ("train", "train", 32, 42),
        ("train", "train+validation", 32, 42),
        ("train", "source", 32, 42),
    ]
    assert [event for event in events if event[0] == "evaluate"] == [
        (
            "evaluate", "loaded-selection-a", "train", "validation", "validation",
            "selection-a", 2, 42,
        ),
        (
            "evaluate", "loaded-selection-b", "train", "validation", "validation",
            "selection-b", 2, 42,
        ),
        (
            "evaluate", "loaded-final-b", "train+validation", "test", "test",
            "final-b", 2, 42,
        ),
    ]
    assert events.index(("select", ["selection-a", "selection-b"])) < events.index(
        (
            "evaluate",
            "loaded-final-b",
            "train+validation",
            "test",
            "test",
            "final-b",
            2,
            42,
        )
    )
    assert reports[0][1] is True
    report = reports[0][0]
    assert report["selected_model_version"] == "selection-b"
    assert report["final_evaluation"]["model_version"] == "final-b"
    assert report["serving"]["model_version"] == "serving-b"
    assert evidence_calls == [
        (
            "abort",
            "validation",
            "selection-a",
        ),
        (
            "finalize",
            "validation",
            "selection-b",
            ((0, "validation-selection-b-candidates", "validation-selection-b-per-user"),),
        ),
        (
            "finalize",
            "test",
            "final-b",
            ((0, "test-final-b-candidates", "test-final-b-per-user"),),
        ),
    ]
    selected_report = next(
        item for item in report["candidates"] if item["model_version"] == "selection-b"
    )
    assert selected_report["evidence"]["stage"] == "validation"
    assert report["final_evaluation"]["evidence"]["stage"] == "test"
    assert result.serving_model_version == "serving-b"
    assert artifact_revisions == [
        ("sha256:" + "a" * 64, "sha256:" + "b" * 64)
    ] * 4


def test_existing_run_is_rejected_before_split_training_or_writes(monkeypatch):
    module = baseline_module()
    existing = FileExistsError("ALS run already exists: /output/als-runs/run-a1")
    reserve = pytest.fail

    def reject_existing(*_args, **_kwargs):
        raise existing

    reserve = reject_existing
    monkeypatch.setattr(module, "reserve_run_report", reserve, raising=False)
    monkeypatch.setattr(module, "split_ratings", pytest.fail)
    monkeypatch.setattr(module, "train_als_model", pytest.fail)
    monkeypatch.setattr(module, "save_als_artifacts", pytest.fail)
    monkeypatch.setattr(module, "ChunkedEvidenceWriter", pytest.fail, raising=False)
    monkeypatch.setattr(module, "write_run_report", pytest.fail)
    inputs = SimpleNamespace(
        ratings=Frame("source"), service_movie_map=Frame("map"), ratings_count=64,
        mapping_revision="sha256:" + "a" * 64,
    )

    with pytest.raises(FileExistsError) as caught:
        module.run_baseline(object(), inputs, settings())

    assert caught.value is existing


def test_candidate_failure_stops_later_stages_and_never_completes_report(monkeypatch):
    module = baseline_module()
    assignments = Frame("assignments")
    splits = SimpleNamespace(
        assignments=assignments,
        train=Frame("train"),
        validation=Frame("validation"),
        test=Frame("test"),
    )
    monkeypatch.setattr(module, "split_ratings", lambda *args, **kwargs: splits)
    monkeypatch.setattr(module, "summarize_splits", lambda value: SimpleNamespace(total_ratings=64))
    trained = []

    def fail_second(frame, config, seed):
        trained.append(config.rank)
        if config.rank == 32:
            raise RuntimeError("injected candidate failure")
        return SimpleNamespace(uid="first", rank=config.rank)

    monkeypatch.setattr(module, "train_als_model", fail_second)
    monkeypatch.setattr(module, "new_model_version", lambda *_: "selection-a")
    monkeypatch.setattr(module, "save_als_artifacts", lambda *args, **kwargs: "/saved")
    monkeypatch.setattr(
        module,
        "load_als_artifacts",
        lambda *args: SimpleNamespace(model=SimpleNamespace(uid="loaded")),
    )
    def evaluate_chunked(*args, **kwargs):
        return SimpleNamespace(
            summary={
                name: {"evaluated_users": 1, "ndcg_at_10": 0.1, "recall_at_10": 0.1}
                for name in ("movielens", "service")
            }
        )

    class PendingEvidence:
        def __init__(self, *_args, **_kwargs):
            pass

        def __call__(self, **_kwargs):
            pass

        def finalize(self):
            raise AssertionError("selection must not run after candidate training failure")

        def abort(self):
            pass

    def reject_legacy_evaluation(*_args, **_kwargs):
        raise AssertionError("run mode must not call evaluate_offline")

    monkeypatch.setattr(module, "evaluate_offline", reject_legacy_evaluation)
    monkeypatch.setattr(module, "evaluate_offline_chunked", evaluate_chunked, raising=False)
    monkeypatch.setattr(module, "ChunkedEvidenceWriter", PendingEvidence, raising=False)
    select = pytest.fail
    monkeypatch.setattr(module, "select_best_candidate", select)
    reports = []
    monkeypatch.setattr(
        module,
        "write_run_report",
        lambda spark, output, report, *, complete: reports.append((report, complete)),
    )
    inputs = SimpleNamespace(
        ratings=Frame("source"), service_movie_map=Frame("map"), ratings_count=64,
        mapping_revision="sha256:" + "a" * 64,
    )

    with pytest.raises(RuntimeError, match="injected candidate failure"):
        module.run_baseline(object(), inputs, settings())
    assert trained == [16, 32]
    assert assignments.unpersisted
    assert len(reports) == 1
    failed_report, complete = reports[0]
    assert complete is False
    assert failed_report["status"] == "FAILED"
    assert failed_report["failure"]["stage"] == "candidate_training"


def test_chunk_failure_context_is_kept_in_incomplete_report(monkeypatch):
    module = baseline_module()
    assignments = Frame("assignments")
    splits = SimpleNamespace(
        assignments=assignments,
        train=Frame("train"),
        validation=Frame("validation"),
        test=Frame("test"),
    )
    monkeypatch.setattr(module, "split_ratings", lambda *args, **kwargs: splits)
    monkeypatch.setattr(module, "summarize_splits", lambda value: SimpleNamespace(total_ratings=64))
    monkeypatch.setattr(module, "train_als_model", lambda *args: SimpleNamespace(uid="trained"))
    monkeypatch.setattr(module, "save_als_artifacts", lambda *args: "/saved")
    monkeypatch.setattr(
        module,
        "load_als_artifacts",
        lambda *args: SimpleNamespace(model=SimpleNamespace(uid="loaded")),
    )
    failure = RuntimeError("injected chunk failure")
    failure.stage = "metrics"
    failure.chunk_id = 1

    chunk_calls = []

    def fail_evaluation(*args, **kwargs):
        chunk_calls.append(kwargs)
        raise failure

    def reject_legacy_evaluation(*_args, **_kwargs):
        raise AssertionError("run mode must not call evaluate_offline")

    monkeypatch.setattr(module, "evaluate_offline", reject_legacy_evaluation)
    monkeypatch.setattr(module, "evaluate_offline_chunked", fail_evaluation, raising=False)
    monkeypatch.setattr(
        module,
        "ChunkedEvidenceWriter",
        lambda *_args, **_kwargs: (lambda **_chunk: None),
        raising=False,
    )
    reports = []
    monkeypatch.setattr(
        module,
        "write_run_report",
        lambda spark, output, report, *, complete: reports.append((report, complete)),
    )
    inputs = SimpleNamespace(
        ratings=Frame("source"), service_movie_map=Frame("map"), ratings_count=64,
        mapping_revision="sha256:" + "a" * 64,
    )

    with pytest.raises(RuntimeError, match="injected chunk failure"):
        module.run_baseline(object(), inputs, settings())

    report, complete = reports[0]
    assert complete is False and assignments.unpersisted
    assert len(chunk_calls) == 1
    assert chunk_calls[0]["user_chunk_size"] == 2
    assert chunk_calls[0]["seed"] == 42
    assert callable(chunk_calls[0]["evidence_writer"])
    assert report["failure"] == {
        "stage": "validation",
        "chunk_stage": "metrics",
        "chunk_id": 1,
        "error_type": "RuntimeError",
    }


def test_report_records_explicit_user_chunk_size(monkeypatch):
    module = baseline_module()
    reports = []
    monkeypatch.setattr(
        module,
        "write_run_report",
        lambda spark, output, report, *, complete: reports.append((report, complete)),
    )

    def stop_after_report_initialization(*_args, **_kwargs):
        raise RuntimeError("stop after report initialization")

    monkeypatch.setattr(module, "split_ratings", stop_after_report_initialization)

    inputs = SimpleNamespace(
        ratings=Frame("source"), service_movie_map=Frame("map"), ratings_count=64,
        mapping_revision="sha256:" + "a" * 64,
    )
    with pytest.raises(RuntimeError, match="stop after report initialization"):
        module.run_baseline(object(), inputs, settings())

    report, complete = reports[0]
    assert complete is False
    assert report["settings"]["user_chunk_size"] == 2


def test_job_failure_abort_logs_all_owned_evidence_cleanup_failures(monkeypatch, caplog):
    """The baseline exception boundary must not hide a writer cleanup failure."""
    module = baseline_module()
    report_module = importlib.import_module("batch.train.run_report")
    writer = report_module.ChunkedEvidenceWriter.__new__(report_module.ChunkedEvidenceWriter)
    writer.spark = object()
    writer.temporary_uri = "file:///run/.temporary"
    writer.claim_uri = "file:///run/.claim"
    writer.final_uri = "file:///run/validation"
    writer._temporary_owned = True
    writer._claim_owned = True
    writer._canonical_owned = True
    writer._finished = False

    delete_attempts = []

    class FailingFileSystem:
        def exists(self, _path):
            return True

        def delete(self, path, _recursive):
            delete_attempts.append(path)
            raise OSError(f"injected delete failure: {path}")

    file_system = FailingFileSystem()
    monkeypatch.setattr(
        report_module,
        "_hadoop_path",
        lambda _spark, uri: (uri, file_system),
    )
    assignments = Frame("assignments")
    splits = SimpleNamespace(
        assignments=assignments,
        train=Frame("train"),
        validation=Frame("validation"),
        test=Frame("test"),
    )
    monkeypatch.setattr(module, "split_ratings", lambda *_args, **_kwargs: splits)
    monkeypatch.setattr(
        module,
        "summarize_splits",
        lambda _value: SimpleNamespace(total_ratings=64),
    )
    monkeypatch.setattr(module, "train_als_model", lambda *_args: SimpleNamespace(uid="trained"))
    monkeypatch.setattr(module, "save_als_artifacts", lambda *_args: "/saved")
    monkeypatch.setattr(
        module,
        "load_als_artifacts",
        lambda *_args: SimpleNamespace(model=SimpleNamespace(uid="loaded")),
    )
    job_error = RuntimeError("injected original job failure")
    monkeypatch.setattr(
        module,
        "evaluate_offline_chunked",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(job_error),
        raising=False,
    )
    monkeypatch.setattr(module, "ChunkedEvidenceWriter", lambda *_args, **_kwargs: writer)
    monkeypatch.setattr(module, "write_run_report", lambda *_args, **_kwargs: None)

    caplog.set_level(logging.ERROR, logger=module.__name__)
    inputs = SimpleNamespace(
        ratings=Frame("source"), service_movie_map=Frame("map"), ratings_count=64,
        mapping_revision="sha256:" + "a" * 64,
    )

    with pytest.raises(RuntimeError) as caught:
        module.run_baseline(object(), inputs, settings())

    assert caught.value is job_error
    assert assignments.unpersisted
    assert delete_attempts == [writer.temporary_uri, writer.claim_uri, writer.final_uri]
    cleanup_traceback = "".join("".join(traceback.format_exception(*record.exc_info))
        for record in caplog.records
        if record.exc_info is not None
    )
    for path in delete_attempts:
        assert f"injected delete failure: {path}" in cleanup_traceback


@pytest.fixture(scope="module")
def evidence_spark():
    from pyspark.sql import SparkSession

    session = (
        SparkSession.builder.master("local[1]")
        .appName("als-baseline-rollback-test")
        .config("spark.ui.enabled", "false")
        .config("spark.sql.shuffle.partitions", "1")
        .getOrCreate()
    )
    session.sparkContext.setLogLevel("ERROR")
    yield session
    session.stop()


@pytest.mark.parametrize(
    ("failure_stage", "cleanup_fails", "failure_report_write_fails"),
    [
        ("final_training", False, False),
        ("final_training", True, False),
        ("serving_training", False, False),
        ("serving_training", True, False),
        ("report", False, False),
        ("report", True, False),
        ("report", True, True),
    ],
    ids=[
        "final-training-cleanup-ok",
        "final-training-cleanup-error",
        "serving-training-cleanup-ok",
        "serving-training-cleanup-error",
        "report-cleanup-ok",
        "report-cleanup-error",
        "complete-and-failure-report-write-fail",
    ],
)
def test_finalized_evidence_rolls_back_after_later_job_failure(
    evidence_spark,
    tmp_path,
    monkeypatch,
    caplog,
    failure_stage,
    cleanup_fails,
    failure_report_write_fails,
):
    """Exercise real writer finalize/abort across the baseline Job exception boundary."""
    module = baseline_module()
    report_module = importlib.import_module("batch.train.run_report")
    config = settings()
    config.output_uri = tmp_path.as_uri()
    config.als_configs = config.als_configs[:1]
    base = tmp_path / "als-runs" / config.run_id
    protected = [
        tmp_path / "als-runs" / "existing-run" / "validation" / "candidates" / "part",
        tmp_path / "als-runs" / "existing-run" / "test" / "per-user" / "part",
        tmp_path / "als-runs" / "existing-run" / "_SUCCESS",
        tmp_path / "als" / "existing-model" / "model" / "part",
        tmp_path / "als" / "existing-model" / "model" / "_SUCCESS",
    ]
    for path in protected:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"existing-owned-by-another-run")

    assignments = Frame("assignments")
    splits = SimpleNamespace(
        assignments=assignments, train=Frame("train"),
        validation=Frame("validation"), test=Frame("test"),
    )
    monkeypatch.setattr(module, "split_ratings", lambda *_args, **_kwargs: splits)
    monkeypatch.setattr(module, "summarize_splits", lambda _: SimpleNamespace(total_ratings=64))
    versions = iter(("selection-a", "final-a", "serving-a"))
    monkeypatch.setattr(module, "new_model_version", lambda _: next(versions))
    job_error = RuntimeError(f"injected original {failure_stage} failure")
    expected_stages = ["validation"]
    if failure_stage != "final_training":
        expected_stages.append("test")

    def fail_job():
        for stage in expected_stages:
            assert (base / stage / "candidates").is_dir()
            assert (base / stage / "per-user").is_dir()
        assert not (base / "_SUCCESS").exists()
        raise job_error

    def train(frame, *_args):
        if (failure_stage == "final_training" and frame.name == "train+validation") or (
            failure_stage == "serving_training" and frame.name == "source"
        ):
            fail_job()
        return SimpleNamespace(uid="trained")

    monkeypatch.setattr(module, "train_als_model", train)
    saved_models = []

    def save(_spark, _model, _output, metadata):
        path = tmp_path / "als" / metadata.model_version / "model"
        path.mkdir(parents=True)
        sentinel = path / "part"
        sentinel.write_bytes(b"published-model")
        saved_models.append(sentinel)
        return path.parent.as_uri()

    monkeypatch.setattr(module, "save_als_artifacts", save)
    monkeypatch.setattr(module, "load_als_artifacts", lambda *_: SimpleNamespace(model=object()))
    frame = evidence_spark.createDataFrame([(1,)], "movielens_user_id int")

    def evaluate(*_args, evidence_writer, **_kwargs):
        evidence_writer(chunk_id=0, candidates=frame, per_user=frame)
        return SimpleNamespace(summary={
            catalog: {"evaluated_users": 1, "ndcg_at_10": 0.5, "recall_at_10": 0.5}
            for catalog in ("movielens", "service")
        })

    monkeypatch.setattr(module, "evaluate_offline_chunked", evaluate)
    monkeypatch.setattr(module, "reserve_run_report", report_module.reserve_run_report)
    # This test isolates lifecycle handling; metric/schema validation has its own suites.
    monkeypatch.setattr(report_module, "validate_run_report", lambda *_args, **_kwargs: None)
    original_write = report_module._write_text

    def write_text(spark, uri, value):
        if failure_stage == "report" and '"SUCCEEDED"' in value:
            fail_job()
        if failure_report_write_fails and '"FAILED"' in value:
            raise OSError("injected failure report write failure")
        return original_write(spark, uri, value)

    monkeypatch.setattr(report_module, "_write_text", write_text)
    original_hadoop_path = report_module._hadoop_path
    canonical_uris = [(base / stage).as_uri() for stage in expected_stages]
    delete_attempts = []

    class FileSystem:
        def __init__(self, delegate):
            self.delegate = delegate

        def __getattr__(self, name):
            return getattr(self.delegate, name)

        def delete(self, path, recursive):
            delete_attempts.append(str(path))
            if cleanup_fails:
                raise OSError(f"injected canonical cleanup failure: {path}")
            return self.delegate.delete(path, recursive)

    def hadoop_path(spark, uri):
        path, fs = original_hadoop_path(spark, uri)
        if uri in canonical_uris:
            fs = FileSystem(fs)
        return path, fs

    monkeypatch.setattr(report_module, "_hadoop_path", hadoop_path)
    caplog.set_level(logging.ERROR, logger=module.__name__)
    inputs = SimpleNamespace(
        ratings=Frame("source"), service_movie_map=Frame("map"), ratings_count=64,
        mapping_revision="sha256:" + "a" * 64,
    )
    with pytest.raises(RuntimeError) as caught:
        module.run_baseline(evidence_spark, inputs, config)

    assert caught.value is job_error
    assert assignments.unpersisted
    assert not (base / "_SUCCESS").exists()
    for path in protected:
        assert path.read_bytes() == b"existing-owned-by-another-run"
    for path in saved_models:
        assert path.read_bytes() == b"published-model"
    expected_paths = [str(original_hadoop_path(evidence_spark, uri)[0]) for uri in canonical_uris]
    assert delete_attempts == expected_paths
    cleanup_traceback = "".join(
        "".join(traceback.format_exception(*record.exc_info))
        for record in caplog.records if record.exc_info is not None
    )
    for stage, path in zip(expected_stages, expected_paths, strict=True):
        assert (base / stage).exists() is cleanup_fails
        if cleanup_fails:
            assert f"injected canonical cleanup failure: {path}" in cleanup_traceback
    if failure_report_write_fails:
        assert "injected failure report write failure" in cleanup_traceback
        assert any(
            record.getMessage() == "Failed to persist the ALS failure report"
            for record in caplog.records
        )
    if not cleanup_fails and not failure_report_write_fails:
        assert not caplog.records
