"""Publication contract for chunked validation/test evidence."""

import importlib
from unittest.mock import MagicMock

import pytest
from py4j.protocol import Py4JJavaError
from pyspark.sql import SparkSession


@pytest.fixture(scope="module")
def spark():
    session = (
        SparkSession.builder.master("local[1]")
        .appName("als-chunked-evidence-test")
        .config("spark.ui.enabled", "false")
        .config("spark.sql.shuffle.partitions", "1")
        .getOrCreate()
    )
    session.sparkContext.setLogLevel("ERROR")
    yield session
    session.stop()


def writer_class():
    return importlib.import_module("batch.train.run_report").ChunkedEvidenceWriter


def frames(spark, user_id):
    candidates = spark.createDataFrame(
        [(user_id, user_id + 10, float(user_id), 1, "movielens", None)],
        "movielens_user_id int, movielens_movie_id int, score float, rank int, "
        "catalog string, service_movie_id long",
    )
    per_user = spark.createDataFrame(
        [(user_id, 1.0, 1.0, "movielens")],
        "movielens_user_id int, recall_at_10 double, ndcg_at_10 double, catalog string",
    )
    return candidates, per_user


def empty_frames(spark):
    candidates, per_user = frames(spark, 1)
    return candidates.limit(0), per_user.limit(0)


def new_writer(spark, tmp_path, *, run_id="run-a1"):
    return writer_class()(
        spark,
        tmp_path.as_uri(),
        run_id,
        stage="validation",
        model_version="selection-a",
    )


def test_chunks_publish_to_one_canonical_evidence_reference_without_run_marker(
    spark, tmp_path
):
    writer = new_writer(spark, tmp_path)
    first_candidates, first_per_user = frames(spark, 1)
    second_candidates, second_per_user = frames(spark, 2)
    writer(chunk_id=0, candidates=first_candidates, per_user=first_per_user)
    writer(chunk_id=1, candidates=second_candidates, per_user=second_per_user)

    evidence = writer.finalize()

    base = tmp_path / "als-runs" / "run-a1"
    assert evidence == {
        "stage": "validation",
        "model_version": "selection-a",
        "candidates_uri": (base / "validation" / "candidates").as_uri(),
        "per_user_uri": (base / "validation" / "per-user").as_uri(),
        "candidate_rows": 2,
        "per_user_rows": 2,
    }
    candidate_users = sorted(
        row.movielens_user_id
        for row in spark.read.parquet(evidence["candidates_uri"]).collect()
    )
    per_user_users = sorted(
        row.movielens_user_id
        for row in spark.read.parquet(evidence["per_user_uri"]).collect()
    )
    assert candidate_users == [1, 2]
    assert per_user_users == [1, 2]
    assert not (base / "_SUCCESS").exists()


def test_same_run_stage_and_duplicate_chunk_never_overwrite_partial_or_published_data(
    spark, tmp_path
):
    writer = new_writer(spark, tmp_path)
    candidates, per_user = frames(spark, 1)
    writer(chunk_id=0, candidates=candidates, per_user=per_user)

    with pytest.raises(FileExistsError):
        writer(chunk_id=0, candidates=candidates, per_user=per_user)

    base = tmp_path / "als-runs" / "run-a1"
    assert not (base / "_SUCCESS").exists()
    assert not (base / "validation" / "candidates").exists()
    assert not (base / "validation" / "per-user").exists()

    other = new_writer(spark, tmp_path, run_id="run-b1")
    other(chunk_id=0, candidates=candidates, per_user=per_user)
    evidence = other.finalize()
    before = spark.read.parquet(evidence["candidates_uri"]).collect()
    with pytest.raises(FileExistsError):
        new_writer(spark, tmp_path, run_id="run-b1")
    assert spark.read.parquet(evidence["candidates_uri"]).collect() == before


def test_empty_chunk_publishes_schema_correct_zero_row_evidence(spark, tmp_path):
    writer = new_writer(spark, tmp_path)
    candidates, per_user = empty_frames(spark)
    writer(chunk_id=0, candidates=candidates, per_user=per_user)

    evidence = writer.finalize()

    assert evidence["candidate_rows"] == 0
    assert evidence["per_user_rows"] == 0
    assert spark.read.parquet(evidence["candidates_uri"]).count() == 0
    assert spark.read.parquet(evidence["per_user_uri"]).count() == 0
    assert not (tmp_path / "als-runs" / "run-a1" / "_SUCCESS").exists()


def test_active_writer_claim_is_not_deleted_by_a_colliding_writer(spark, tmp_path):
    owner = new_writer(spark, tmp_path)
    candidates, per_user = frames(spark, 1)
    owner(chunk_id=0, candidates=candidates, per_user=per_user)

    with pytest.raises(FileExistsError):
        new_writer(spark, tmp_path)

    evidence = owner.finalize()
    assert spark.read.parquet(evidence["candidates_uri"]).count() == 1


@pytest.mark.parametrize("failure_point", ["write", "close"])
def test_owned_claim_write_failure_is_cleaned_without_becoming_a_collision(
    monkeypatch, failure_point
):
    module = importlib.import_module("batch.train.run_report")
    injected = Py4JJavaError(f"injected claim {failure_point} failure", MagicMock())

    class FailingStream:
        def write(self, _value):
            if failure_point == "write":
                raise injected

        def close(self):
            if failure_point == "close":
                raise injected

    class FileSystem:
        def __init__(self):
            self.paths = set()

        def exists(self, path):
            return path in self.paths

        def mkdirs(self, path):
            if path in self.paths:
                return False
            self.paths.add(path)
            return True

        def create(self, path, overwrite):
            assert overwrite is False
            if path in self.paths:
                raise AssertionError("fixture unexpectedly overwrote an existing path")
            self.paths.add(path)
            return FailingStream()

        def delete(self, path, recursive):
            owned = path in self.paths or any(
                candidate.startswith(f"{path}/") for candidate in self.paths
            )
            self.paths = {
                candidate
                for candidate in self.paths
                if candidate != path
                and (not recursive or not candidate.startswith(f"{path}/"))
            }
            return owned

    file_system = FileSystem()
    monkeypatch.setattr(
        module,
        "_hadoop_path",
        lambda _spark, uri: (uri, file_system),
    )
    claim_uri = (
        "file:///output/als-runs/run-a1/"
        ".validation-selection-a-evidence-active"
    )

    with pytest.raises(Py4JJavaError) as caught:
        module.ChunkedEvidenceWriter(
            object(),
            "file:///output",
            "run-a1",
            stage="validation",
            model_version="selection-a",
        )

    assert caught.value is injected
    assert claim_uri not in file_system.paths


def _observable_error_text(error):
    pending = [error]
    seen = set()
    observations = []
    while pending:
        current = pending.pop()
        if id(current) in seen:
            continue
        seen.add(id(current))
        observations.append(f"{type(current).__name__}: {current}")
        observations.extend(getattr(current, "__notes__", ()))
        for related in (current.__cause__, current.__context__):
            if related is not None:
                pending.append(related)
    return "\n".join(observations)


@pytest.mark.parametrize("first_delete", ["false", "error"])
def test_claim_write_and_repeated_cleanup_failures_remain_observable(
    monkeypatch, first_delete
):
    module = importlib.import_module("batch.train.run_report")
    write_error = OSError("injected claim write failure")

    class FailingStream:
        def write(self, _value):
            raise write_error

        def close(self):
            pass

    class FileSystem:
        def __init__(self):
            self.paths = set()
            self.delete_calls = 0

        def exists(self, path):
            return path in self.paths

        def mkdirs(self, path):
            self.paths.add(path)
            return True

        def create(self, path, overwrite):
            assert overwrite is False
            self.paths.add(path)
            return FailingStream()

        def delete(self, _path, _recursive):
            self.delete_calls += 1
            if self.delete_calls == 1:
                if first_delete == "false":
                    return False
                raise OSError("injected first claim delete failure")
            raise OSError("injected retry claim delete failure")

    file_system = FileSystem()
    monkeypatch.setattr(module, "_hadoop_path", lambda _spark, uri: (uri, file_system))

    with pytest.raises(OSError) as caught:
        module.ChunkedEvidenceWriter(
            object(),
            "file:///output",
            "run-a1",
            stage="validation",
            model_version="selection-a",
        )

    assert caught.value is write_error
    observations = _observable_error_text(caught.value)
    assert "injected retry claim delete failure" in observations
    if first_delete == "error":
        assert "injected first claim delete failure" in observations
    else:
        assert observations.count("failed to clean writer-owned evaluation evidence path") >= 2
    assert file_system.delete_calls == 2


def test_claim_write_and_close_failures_keep_write_primary_and_close_observable(
    monkeypatch,
):
    module = importlib.import_module("batch.train.run_report")
    write_error = OSError("injected claim write failure")

    class FailingStream:
        def write(self, _value):
            raise write_error

        def close(self):
            raise OSError("injected claim close failure")

    class FileSystem:
        def __init__(self):
            self.paths = set()

        def exists(self, path):
            return path in self.paths

        def mkdirs(self, path):
            self.paths.add(path)
            return True

        def create(self, path, overwrite):
            assert overwrite is False
            self.paths.add(path)
            return FailingStream()

        def delete(self, path, _recursive):
            self.paths.remove(path)
            return True

    file_system = FileSystem()
    monkeypatch.setattr(module, "_hadoop_path", lambda _spark, uri: (uri, file_system))

    with pytest.raises(OSError) as caught:
        module.ChunkedEvidenceWriter(
            object(),
            "file:///output",
            "run-a1",
            stage="validation",
            model_version="selection-a",
        )

    assert caught.value is write_error
    assert "injected claim close failure" in _observable_error_text(caught.value)


def test_abort_attempts_all_owned_paths_and_keeps_every_cleanup_failure(monkeypatch):
    module = importlib.import_module("batch.train.run_report")
    writer = module.ChunkedEvidenceWriter.__new__(module.ChunkedEvidenceWriter)
    writer.spark = object()
    writer.temporary_uri = "file:///run/.temporary"
    writer.claim_uri = "file:///run/.claim"
    writer.final_uri = "file:///run/validation"
    writer._temporary_owned = True
    writer._claim_owned = True
    writer._canonical_owned = True
    writer._finished = False
    attempts = []

    class FileSystem:
        def exists(self, _path):
            return True

        def delete(self, path, _recursive):
            attempts.append(path)
            raise OSError(f"injected delete failure: {path}")

    file_system = FileSystem()
    monkeypatch.setattr(module, "_hadoop_path", lambda _spark, uri: (uri, file_system))

    with pytest.raises(module.EvidenceCleanupError) as caught:
        writer.abort()

    assert attempts == [writer.temporary_uri, writer.claim_uri, writer.final_uri]
    observations = _observable_error_text(caught.value)
    for uri in attempts:
        assert f"injected delete failure: {uri}" in observations


def test_finalize_publish_failure_aborts_staging_without_canonical_evidence(
    spark, tmp_path, monkeypatch
):
    module = importlib.import_module("batch.train.run_report")
    writer = new_writer(spark, tmp_path)
    candidates, per_user = frames(spark, 1)
    writer(chunk_id=0, candidates=candidates, per_user=per_user)

    def fail_publish(*_args, **_kwargs):
        raise OSError("injected evidence publish failure")

    monkeypatch.setattr(module, "_publish_directory", fail_publish)
    with pytest.raises(OSError, match="injected evidence publish failure"):
        writer.finalize()
    writer.abort()

    base = tmp_path / "als-runs" / "run-a1"
    assert not (base / "validation").exists()
    assert not list(base.glob(".validation-selection-a-evidence-*"))
    assert not (base / "_SUCCESS").exists()


def test_finalize_collision_preserves_existing_canonical_and_aborts_staging(
    spark, tmp_path
):
    writer = new_writer(spark, tmp_path)
    candidates, per_user = frames(spark, 1)
    writer(chunk_id=0, candidates=candidates, per_user=per_user)
    base = tmp_path / "als-runs" / "run-a1"
    canonical = base / "validation"
    canonical.mkdir()
    sentinel = canonical / "existing"
    sentinel.write_text("preserve", encoding="utf-8")

    with pytest.raises(FileExistsError):
        writer.finalize()
    writer.abort()

    assert sentinel.read_text(encoding="utf-8") == "preserve"
    assert not list(base.glob(".validation-selection-a-evidence-*"))
    assert not (base / "_SUCCESS").exists()


def test_finalize_cleanup_failure_rolls_back_owned_canonical_and_retries_staging_cleanup(
    spark, tmp_path, monkeypatch
):
    writer = new_writer(spark, tmp_path)
    candidates, per_user = frames(spark, 1)
    writer(chunk_id=0, candidates=candidates, per_user=per_user)
    original_cleanup = writer._cleanup_staging
    cleanup_calls = 0

    def fail_cleanup_once():
        nonlocal cleanup_calls
        cleanup_calls += 1
        if cleanup_calls == 1:
            raise OSError("injected staging cleanup failure")
        original_cleanup()

    monkeypatch.setattr(writer, "_cleanup_staging", fail_cleanup_once)
    with pytest.raises(OSError, match="injected staging cleanup failure"):
        writer.finalize()
    writer.abort()

    base = tmp_path / "als-runs" / "run-a1"
    assert cleanup_calls == 2
    assert not (base / "validation").exists()
    assert not list(base.glob(".validation-selection-a-evidence-*"))
    assert not (base / "_SUCCESS").exists()
