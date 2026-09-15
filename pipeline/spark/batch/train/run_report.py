"""Immutable run reports for ALS baseline orchestration."""

from __future__ import annotations

import json
import math
import os
from pathlib import Path
from uuid import uuid4

from py4j.protocol import Py4JJavaError
from pyspark.sql import functions as F

from batch.train.artifacts import artifact_version_uri, load_als_artifacts

REPORT_FILENAME = "report.json"
COMPLETE_MARKER = "_SUCCESS"
RESERVATION_MARKER = "_RESERVED"


class EvidenceCleanupError(OSError):
    """Report failure to remove writer-owned partial evidence."""

    def __str__(self) -> str:
        message = super().__str__()
        cleanup_errors = getattr(self, "cleanup_errors", ())
        if not cleanup_errors:
            return message
        details = "; ".join(
            f"{type(error).__name__}: {_cleanup_error_text(error)}"
            for error in cleanup_errors
        )
        return f"{message} (additional cleanup errors: {details})"


def _cleanup_error_text(error: BaseException) -> str:
    if isinstance(error, OSError):
        return OSError.__str__(error)
    return str(error)


def _record_cleanup_error(primary: BaseException, additional: BaseException) -> None:
    """Keep cleanup failures explicitly available on the primary exception.

    Python 3.10 has no ``BaseException.add_note()`` and exception context can
    already be cyclic while cleanup runs from an ``except`` block.  Keep a
    direct collection so observability does not depend on either mechanism.
    """
    cleanup_errors = getattr(primary, "cleanup_errors", None)
    if cleanup_errors is None:
        cleanup_errors = []
        primary.cleanup_errors = cleanup_errors
    known = {id(error) for error in cleanup_errors}
    for error in (additional, *getattr(additional, "cleanup_errors", ())):
        if error is not primary and id(error) not in known:
            cleanup_errors.append(error)
            known.add(id(error))


def _preserve_additional_error(primary: BaseException, additional: BaseException) -> None:
    """Keep another failure observable without replacing the primary exception."""
    if primary is additional:
        return
    _record_cleanup_error(primary, additional)
    current = primary
    seen = {id(primary)}
    while current.__context__ is not None and id(current.__context__) not in seen:
        current = current.__context__
        seen.add(id(current))
    if current.__context__ is None and id(additional) not in seen:
        current.__context__ = additional


def run_report_uri(output_uri: str, run_id: str) -> str:
    return f"{output_uri.rstrip('/')}/als-runs/{run_id}"


def serialize_run_report(report: dict[str, object]) -> str:
    _reject_non_finite(report)
    return json.dumps(
        report,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    )


def deserialize_run_report(serialized: str) -> dict[str, object]:
    report = json.loads(serialized, parse_constant=lambda value: (_raise_non_finite(value)))
    if not isinstance(report, dict):
        raise ValueError("run report must be a JSON object")
    _reject_non_finite(report)
    return report


def validate_run_report(report: dict[str, object], *, require_complete: bool) -> None:
    required = ("report_version", "run_id", "status", "input_snapshot", "implementation_revision")
    if any(not report.get(field) for field in required):
        raise ValueError("run report is missing required fields")
    if report["status"] not in {"SUCCEEDED", "FAILED"}:
        raise ValueError("run report status is invalid")
    _reject_non_finite(report)
    if not require_complete:
        if report["status"] == "FAILED" and not report.get("failure"):
            raise ValueError("failed run report requires failure details")
        return

    settings = report.get("settings")
    user_chunk_size = settings.get("user_chunk_size") if isinstance(settings, dict) else None
    if (
        not isinstance(user_chunk_size, int)
        or isinstance(user_chunk_size, bool)
        or user_chunk_size <= 0
    ):
        raise ValueError("complete run report requires a positive user chunk size")

    candidates = report.get("candidates")
    final = report.get("final_evaluation")
    serving = report.get("serving")
    if (
        report.get("status") != "SUCCEEDED"
        or report.get("failure") is not None
        or not isinstance(candidates, list)
        or not candidates
        or not isinstance(final, dict)
        or not isinstance(serving, dict)
        or not report.get("selected_model_version")
    ):
        raise ValueError("complete run report requires every successful stage")
    versions = [candidate.get("model_version") for candidate in candidates]
    if any(candidate.get("artifact_stage") != "MODEL_SELECTION" for candidate in candidates):
        raise ValueError("candidate artifact stage is invalid")
    if len(set(versions)) != len(versions):
        raise ValueError("candidate model versions must be unique")
    if report["selected_model_version"] not in versions:
        raise ValueError("selected model version does not reference a candidate")
    if final.get("artifact_stage") != "FINAL_EVALUATION":
        raise ValueError("final evaluation artifact stage is invalid")
    if serving.get("artifact_stage") != "SERVING":
        raise ValueError("serving artifact stage is invalid")
    if not final.get("model_version") or not serving.get("model_version"):
        raise ValueError("complete report requires final and serving model versions")
    if final["model_version"] == serving["model_version"]:
        raise ValueError("final evaluation and serving model versions must differ")
    selected = next(
        candidate
        for candidate in candidates
        if candidate.get("model_version") == report["selected_model_version"]
    )
    _validate_evidence_reference(
        selected.get("evidence"), stage="validation", model_version=selected["model_version"]
    )
    _validate_evidence_reference(
        final.get("evidence"), stage="test", model_version=final["model_version"]
    )


def reserve_run_report(spark, output_uri: str, run_id: str) -> str:
    """Atomically reserve a run path before any split, training, or model write."""
    final_uri = run_report_uri(output_uri, run_id)
    final_path, file_system = _hadoop_path(spark, final_uri)
    if file_system.exists(final_path):
        raise FileExistsError(f"ALS run already exists: {final_uri}")
    temporary_uri = f"{final_uri}.reserve-{uuid4().hex}"
    temporary_path, temporary_file_system = _hadoop_path(spark, temporary_uri)
    try:
        if not temporary_file_system.mkdirs(temporary_path):
            raise OSError(f"failed to create ALS run reservation: {temporary_uri}")
        _write_text(spark, f"{temporary_uri}/{RESERVATION_MARKER}", "")
        _publish_directory(spark, temporary_uri, final_uri)
    except Exception:
        if temporary_file_system.exists(temporary_path):
            temporary_file_system.delete(temporary_path, True)
        raise
    return final_uri


def write_evaluation_evidence(
    spark,
    output_uri: str,
    run_id: str,
    evaluation,
    *,
    stage: str,
    model_version: str,
) -> dict[str, object]:
    """Persist selected validation or final test evidence without collecting rows."""
    if stage not in {"validation", "test"}:
        raise ValueError("evaluation evidence stage must be validation or test")
    base_uri = f"{run_report_uri(output_uri, run_id)}/{stage}"
    candidates_uri = f"{base_uri}/candidates"
    per_user_uri = f"{base_uri}/per-user"
    candidates = evaluation.candidates.withColumn("stage", F.lit(stage)).withColumn(
        "model_version", F.lit(model_version)
    )
    per_user = evaluation.per_user.withColumn("stage", F.lit(stage)).withColumn(
        "model_version", F.lit(model_version)
    )
    candidates.write.mode("errorifexists").parquet(candidates_uri)
    per_user.write.mode("errorifexists").parquet(per_user_uri)
    candidate_rows = spark.read.parquet(candidates_uri).count()
    per_user_rows = spark.read.parquet(per_user_uri).count()
    return {
        "stage": stage,
        "model_version": model_version,
        "candidates_uri": candidates_uri,
        "per_user_uri": per_user_uri,
        "candidate_rows": candidate_rows,
        "per_user_rows": per_user_rows,
    }


class ChunkedEvidenceWriter:
    """Write isolated evidence chunks and atomically publish one stage directory."""

    def __init__(self, spark, output_uri: str, run_id: str, *, stage: str, model_version: str):
        if stage not in {"validation", "test"}:
            raise ValueError("evaluation evidence stage must be validation or test")
        self.spark = spark
        self.stage = stage
        self.model_version = model_version
        self.run_uri = run_report_uri(output_uri, run_id)
        self.final_uri = f"{self.run_uri}/{stage}"
        self.claim_uri = f"{self.run_uri}/.{stage}-{model_version}-evidence-active"
        self.temporary_uri = (
            f"{self.run_uri}/.{stage}-{model_version}-evidence-{uuid4().hex}"
        )
        self._chunk_ids: list[int] = []
        self._finished = False
        self._claim_owned = False
        self._temporary_owned = False
        self._canonical_owned = False

        run_path, file_system = _hadoop_path(spark, self.run_uri)
        final_path, _ = _hadoop_path(spark, self.final_uri)
        if file_system.exists(final_path):
            raise FileExistsError(f"ALS evaluation evidence already exists: {self.final_uri}")
        if not file_system.exists(run_path) and not file_system.mkdirs(run_path):
            raise OSError(f"failed to create ALS run directory: {self.run_uri}")
        try:
            self._create_claim()
            temporary_path, _ = _hadoop_path(spark, self.temporary_uri)
            if not file_system.mkdirs(temporary_path):
                raise OSError(f"failed to create evidence staging path: {self.temporary_uri}")
            self._temporary_owned = True
        except Exception as error:
            try:
                self.abort()
            except BaseException as cleanup_error:
                _preserve_additional_error(error, cleanup_error)
            raise

    def __call__(self, *, chunk_id: int, candidates, per_user) -> None:
        if self._finished:
            raise RuntimeError("ALS evaluation evidence writer is closed")
        if not isinstance(chunk_id, int) or isinstance(chunk_id, bool) or chunk_id < 0:
            raise ValueError("evidence chunk ID must be a non-negative integer")
        if chunk_id in self._chunk_ids:
            raise FileExistsError(f"ALS evaluation evidence chunk already exists: {chunk_id}")
        chunk_uri = f"{self.temporary_uri}/chunks/{chunk_id:020d}"
        attributed_candidates = candidates.withColumn("stage", F.lit(self.stage)).withColumn(
            "model_version", F.lit(self.model_version)
        )
        attributed_per_user = per_user.withColumn("stage", F.lit(self.stage)).withColumn(
            "model_version", F.lit(self.model_version)
        )
        attributed_candidates.write.mode("errorifexists").parquet(
            f"{chunk_uri}/candidates"
        )
        attributed_per_user.write.mode("errorifexists").parquet(f"{chunk_uri}/per-user")
        self._chunk_ids.append(chunk_id)

    def finalize(self) -> dict[str, object]:
        if self._finished:
            raise RuntimeError("ALS evaluation evidence writer is closed")
        if not self._chunk_ids:
            raise ValueError("at least one evidence chunk is required")

        chunk_uris = [
            f"{self.temporary_uri}/chunks/{chunk_id:020d}"
            for chunk_id in self._chunk_ids
        ]
        publish_uri = f"{self.temporary_uri}/publish"
        candidates_uri = f"{publish_uri}/candidates"
        per_user_uri = f"{publish_uri}/per-user"
        self.spark.read.parquet(
            *(f"{uri}/candidates" for uri in chunk_uris)
        ).write.mode("errorifexists").parquet(candidates_uri)
        self.spark.read.parquet(
            *(f"{uri}/per-user" for uri in chunk_uris)
        ).write.mode("errorifexists").parquet(per_user_uri)
        candidate_rows = self.spark.read.parquet(candidates_uri).count()
        per_user_rows = self.spark.read.parquet(per_user_uri).count()
        _publish_directory(self.spark, publish_uri, self.final_uri)
        self._canonical_owned = True
        self._cleanup_staging()
        self._finished = True
        return {
            "stage": self.stage,
            "model_version": self.model_version,
            "candidates_uri": f"{self.final_uri}/candidates",
            "per_user_uri": f"{self.final_uri}/per-user",
            "candidate_rows": candidate_rows,
            "per_user_rows": per_user_rows,
        }

    def abort(self) -> None:
        """Roll back owned evidence, including a successfully finalized publication."""
        cleanup_error = None
        try:
            self._cleanup_staging()
        except BaseException as error:
            cleanup_error = error
        try:
            self._cleanup_canonical()
        except BaseException as error:
            if cleanup_error is None:
                cleanup_error = error
            else:
                _preserve_additional_error(cleanup_error, error)
        if cleanup_error is not None:
            raise cleanup_error
        self._finished = True

    def _create_claim(self) -> None:
        path, file_system = _hadoop_path(self.spark, self.claim_uri)
        try:
            stream = file_system.create(path, False)
        except Py4JJavaError as error:
            if file_system.exists(path):
                raise FileExistsError(
                    f"ALS evaluation evidence writer already exists: {self.final_uri}"
                ) from error
            raise

        self._claim_owned = True
        write_error = None
        try:
            stream.write(bytearray(self.temporary_uri.encode("utf-8")))
        except BaseException as error:
            write_error = error
        finally:
            try:
                stream.close()
            except BaseException as error:
                if write_error is None:
                    write_error = error
                else:
                    _preserve_additional_error(write_error, error)

        if write_error is None:
            return
        try:
            self._delete_owned_path(self.claim_uri, "_claim_owned")
        except BaseException as cleanup_error:
            _preserve_additional_error(write_error, cleanup_error)
        raise write_error

    def _cleanup_staging(self) -> None:
        cleanup_error = None
        for uri, ownership_attribute in (
            (self.temporary_uri, "_temporary_owned"),
            (self.claim_uri, "_claim_owned"),
        ):
            try:
                self._delete_owned_path(uri, ownership_attribute)
            except BaseException as error:
                if cleanup_error is None:
                    cleanup_error = error
                else:
                    _preserve_additional_error(cleanup_error, error)
        if cleanup_error is not None:
            raise cleanup_error

    def _cleanup_canonical(self) -> None:
        self._delete_owned_path(self.final_uri, "_canonical_owned")

    def _delete_owned_path(self, uri: str, ownership_attribute: str) -> None:
        if not getattr(self, ownership_attribute):
            return
        path, file_system = _hadoop_path(self.spark, uri)
        try:
            exists = file_system.exists(path)
            if exists and not file_system.delete(path, True):
                raise EvidenceCleanupError(
                    f"failed to clean writer-owned evaluation evidence path: {uri}"
                )
        except BaseException as error:
            if isinstance(error, EvidenceCleanupError):
                raise
            raise EvidenceCleanupError(
                f"failed to clean writer-owned evaluation evidence path {uri}: {error}"
            ) from error
        setattr(self, ownership_attribute, False)


def write_run_report(spark, output_uri: str, report: dict[str, object], *, complete: bool) -> str:
    validate_run_report(report, require_complete=complete)
    final_uri = run_report_uri(output_uri, str(report["run_id"]))
    final_path, file_system = _hadoop_path(spark, final_uri)
    if file_system.exists(final_path):
        reservation_path, _ = _hadoop_path(spark, f"{final_uri}/{RESERVATION_MARKER}")
        report_path, _ = _hadoop_path(spark, f"{final_uri}/{REPORT_FILENAME}")
        if not file_system.exists(reservation_path) or file_system.exists(report_path):
            raise FileExistsError(f"ALS run report already exists: {final_uri}")
        _write_text(spark, f"{final_uri}/{REPORT_FILENAME}", serialize_run_report(report))
        if not file_system.delete(reservation_path, False):
            raise OSError("failed to delete ALS run reservation marker")
        if complete:
            _write_text(spark, f"{final_uri}/{COMPLETE_MARKER}", "")
        return final_uri
    temporary_uri = f"{final_uri}.tmp-{uuid4().hex}"
    temporary_path, temporary_file_system = _hadoop_path(spark, temporary_uri)
    try:
        if not temporary_file_system.mkdirs(temporary_path):
            raise OSError(f"failed to create run report directory: {temporary_uri}")
        _write_text(spark, f"{temporary_uri}/{REPORT_FILENAME}", serialize_run_report(report))
        if complete:
            _write_text(spark, f"{temporary_uri}/{COMPLETE_MARKER}", "")
        _publish_directory(spark, temporary_uri, final_uri)
    except Exception:
        if temporary_file_system.exists(temporary_path):
            temporary_file_system.delete(temporary_path, True)
        raise
    return final_uri


def load_run_report(spark, report_uri: str, *, require_complete: bool) -> dict[str, object]:
    report_path, file_system = _hadoop_path(spark, f"{report_uri.rstrip('/')}/{REPORT_FILENAME}")
    if not file_system.exists(report_path):
        raise FileNotFoundError(f"ALS run report does not exist: {report_uri}")
    marker_path, _ = _hadoop_path(spark, f"{report_uri.rstrip('/')}/{COMPLETE_MARKER}")
    if require_complete and not file_system.exists(marker_path):
        raise ValueError("ALS run report is not complete")
    report = deserialize_run_report(_read_text(spark, str(report_path)))
    validate_run_report(report, require_complete=require_complete)
    if require_complete:
        _validate_completed_references(spark, report_uri, report)
    return report


def _validate_evidence_reference(evidence, *, stage: str, model_version: str) -> None:
    if not isinstance(evidence, dict):
        raise ValueError(f"{stage} evaluation evidence is required")
    if evidence.get("stage") != stage or evidence.get("model_version") != model_version:
        raise ValueError(f"{stage} evaluation evidence attribution is invalid")
    for field in ("candidates_uri", "per_user_uri"):
        if not evidence.get(field):
            raise ValueError(f"{stage} evaluation evidence URI is required")
    for field in ("candidate_rows", "per_user_rows"):
        value = evidence.get(field)
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            raise ValueError(f"{stage} evaluation evidence row count is invalid")


def _validate_completed_references(spark, report_uri: str, report: dict[str, object]) -> None:
    output_uri = _output_uri_from_report_uri(report_uri, str(report["run_id"]))
    selected_version = report["selected_model_version"]
    candidates = report["candidates"]
    selected = next(item for item in candidates if item["model_version"] == selected_version)
    references = [
        *((item, "MODEL_SELECTION") for item in candidates),
        (report["final_evaluation"], "FINAL_EVALUATION"),
        (report["serving"], "SERVING"),
    ]
    for reference, expected_stage in references:
        version = reference["model_version"]
        expected_uri = artifact_version_uri(output_uri, version)
        loaded = load_als_artifacts(spark, output_uri, version)
        if reference.get("artifact_uri") != expected_uri:
            raise ValueError("run report artifact URI does not match its model version")
        metadata = loaded.metadata
        comparisons = {
            "artifact stage": (_metadata_value(metadata, "artifact_stage"), expected_stage),
            "model version": (_metadata_value(metadata, "model_version"), version),
            "input snapshot": (
                _metadata_value(metadata, "input_snapshot"),
                report["input_snapshot"],
            ),
            "implementation revision": (
                _metadata_value(metadata, "implementation_revision"),
                report["implementation_revision"],
            ),
        }
        for label, (actual, expected) in comparisons.items():
            if actual != expected:
                raise ValueError(f"referenced ALS metadata {label} does not match the run report")
    _validate_evidence_data(spark, report_uri, selected["evidence"])
    _validate_evidence_data(spark, report_uri, report["final_evaluation"]["evidence"])


def _validate_evidence_data(spark, report_uri: str, evidence: dict[str, object]) -> None:
    stage = evidence["stage"]
    model_version = evidence["model_version"]
    expected_base = f"{report_uri.rstrip('/')}/{stage}"
    expected_uris = {
        "candidates_uri": f"{expected_base}/candidates",
        "per_user_uri": f"{expected_base}/per-user",
    }
    required_columns = {
        "candidates_uri": {
            "movielens_user_id",
            "movielens_movie_id",
            "score",
            "rank",
            "catalog",
            "service_movie_id",
            "stage",
            "model_version",
        },
        "per_user_uri": {
            "movielens_user_id",
            "recall_at_10",
            "ndcg_at_10",
            "catalog",
            "stage",
            "model_version",
        },
    }
    expected_types = {
        "candidates_uri": {
            "movielens_user_id": "int",
            "movielens_movie_id": "int",
            "score": "float",
            "rank": "int",
            "catalog": "string",
            "service_movie_id": "bigint",
            "stage": "string",
            "model_version": "string",
        },
        "per_user_uri": {
            "movielens_user_id": "int",
            "recall_at_10": "double",
            "ndcg_at_10": "double",
            "catalog": "string",
            "stage": "string",
            "model_version": "string",
        },
    }
    count_fields = {"candidates_uri": "candidate_rows", "per_user_uri": "per_user_rows"}
    for uri_field, expected_uri in expected_uris.items():
        if evidence[uri_field] != expected_uri:
            raise ValueError(f"{stage} evaluation evidence URI is outside its run path")
        frame = spark.read.parquet(expected_uri)
        missing = required_columns[uri_field] - set(frame.columns)
        if missing:
            raise ValueError(
                f"{stage} evaluation evidence schema is missing columns: {sorted(missing)}"
            )
        for column, expected_type in expected_types[uri_field].items():
            if frame.schema[column].dataType.simpleString() != expected_type:
                raise ValueError(f"{stage} evaluation evidence column {column} has an invalid type")
        mismatch = (
            frame.filter(
                F.col("stage").isNull()
                | (F.col("stage") != stage)
                | F.col("model_version").isNull()
                | (F.col("model_version") != model_version)
            )
            .limit(1)
            .count()
        )
        if mismatch:
            raise ValueError(f"{stage} evaluation evidence attribution does not match")
        if frame.count() != evidence[count_fields[uri_field]]:
            raise ValueError(f"{stage} evaluation evidence row count does not match")


def _metadata_value(metadata, field: str):
    try:
        return metadata[field]
    except (KeyError, TypeError):
        return getattr(metadata, field)


def _output_uri_from_report_uri(report_uri: str, run_id: str) -> str:
    suffix = f"/als-runs/{run_id}"
    normalized = report_uri.rstrip("/")
    if not normalized.endswith(suffix):
        raise ValueError("ALS run report URI does not match its run ID")
    return normalized[: -len(suffix)]


def _reject_non_finite(value: object) -> None:
    if isinstance(value, float) and not math.isfinite(value):
        raise ValueError("run report numbers must be finite")
    if isinstance(value, dict):
        for nested in value.values():
            _reject_non_finite(nested)
    elif isinstance(value, list | tuple):
        for nested in value:
            _reject_non_finite(nested)


def _raise_non_finite(value: str):
    raise ValueError(f"run report numbers must be finite: {value}")


def _hadoop_path(spark, uri: str):
    configuration = spark.sparkContext._jsc.hadoopConfiguration()  # noqa: SLF001
    path = spark._jvm.org.apache.hadoop.fs.Path(uri)  # noqa: SLF001
    return path, path.getFileSystem(configuration)


def _publish_directory(spark, source_uri: str, destination_uri: str) -> None:
    source_path, source_file_system = _hadoop_path(spark, source_uri)
    destination_path, destination_file_system = _hadoop_path(spark, destination_uri)
    if source_file_system.getUri().toString() != destination_file_system.getUri().toString():
        raise ValueError("temporary and final report paths must use the same filesystem")
    scheme = source_file_system.getUri().getScheme()
    if scheme == "file":
        _publish_local_directory(
            Path(source_file_system.makeQualified(source_path).toUri().getPath()),
            Path(destination_file_system.makeQualified(destination_path).toUri().getPath()),
        )
        return
    if scheme != "hdfs":
        raise ValueError("ALS run reports support only local and hdfs filesystems")

    configuration = spark.sparkContext._jsc.hadoopConfiguration()  # noqa: SLF001
    file_context = spark._jvm.org.apache.hadoop.fs.FileContext.getFileContext(  # noqa: SLF001
        source_file_system.getUri(), configuration
    )
    rename_options = spark.sparkContext._gateway.new_array(  # noqa: SLF001
        spark._jvm.org.apache.hadoop.fs.Options.Rename,
        1,  # noqa: SLF001
    )
    rename_options[0] = spark._jvm.org.apache.hadoop.fs.Options.Rename.NONE  # noqa: SLF001
    try:
        file_context.rename(source_path, destination_path, rename_options)
    except Py4JJavaError as error:
        if destination_file_system.exists(destination_path):
            raise FileExistsError(f"ALS run report already exists: {destination_uri}") from error
        raise OSError(f"failed to publish ALS run report: {destination_uri}") from error


def _publish_local_directory(source: Path, destination: Path) -> None:
    import fcntl

    parent_fd = os.open(destination.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        fcntl.flock(parent_fd, fcntl.LOCK_EX)
        if os.path.lexists(destination):
            raise FileExistsError(f"ALS run report already exists: {destination}")
        os.rename(source, destination)
    finally:
        os.close(parent_fd)


def _write_text(spark, uri: str, value: str) -> None:
    path, file_system = _hadoop_path(spark, uri)
    stream = file_system.create(path, False)
    try:
        stream.write(bytearray(value.encode("utf-8")))
    finally:
        stream.close()


def _read_text(spark, uri: str) -> str:
    path, file_system = _hadoop_path(spark, uri)
    stream = file_system.open(path)
    try:
        return spark._jvm.org.apache.commons.io.IOUtils.toString(stream, "UTF-8")  # noqa: SLF001
    finally:
        stream.close()
