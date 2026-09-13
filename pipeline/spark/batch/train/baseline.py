"""End-to-end ALS baseline selection, evaluation, and serving retraining."""

from __future__ import annotations

import logging
import platform
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from uuid import uuid4

from batch.evaluation.chunked import evaluate_offline_chunked
from batch.evaluation.offline import evaluate_offline as evaluate_offline
from batch.train.artifact_metadata import (
    FINAL_EVALUATION,
    MODEL_SELECTION,
    SERVING,
    SPLIT_METHOD,
    AlsArtifactMetadata,
)
from batch.train.artifacts import load_als_artifacts, save_als_artifacts
from batch.train.run_report import (
    ChunkedEvidenceWriter,
    reserve_run_report,
    write_run_report,
)
from batch.train.selection import ValidationCandidate, select_best_candidate
from batch.train.settings import AlsRunContext
from batch.train.splits import split_ratings, summarize_splits
from batch.train.training import train_als_model

LOGGER = logging.getLogger(__name__)


@dataclass(frozen=True)
class BaselineResult:
    run_id: str
    selected_model_version: str
    final_evaluation_model_version: str
    serving_model_version: str


def new_model_version(stage: str) -> str:
    label = {
        MODEL_SELECTION: "selection",
        FINAL_EVALUATION: "final",
        SERVING: "serving",
    }[stage]
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    return f"als-{label}-{timestamp}-{uuid4().hex[:12]}"


def run_baseline(spark, inputs, settings) -> BaselineResult:
    run_started = time.perf_counter()
    if hasattr(settings, "require_run_context"):
        context = settings.require_run_context()
    else:
        context = AlsRunContext(
            output_uri=settings.output_uri,
            input_manifest_uri=getattr(settings, "input_manifest_uri", ""),
            input_snapshot=settings.input_snapshot,
            run_id=settings.run_id,
            implementation_revision=settings.implementation_revision,
        )
    reserve_run_report(spark, context.output_uri, context.run_id)
    assignments = None
    failure_stage = "split"
    report = {
        "report_version": "als-run-v1",
        "run_id": context.run_id,
        "status": "FAILED",
        "input_snapshot": context.input_snapshot,
        "mapping_snapshot": context.input_snapshot,
        "implementation_revision": context.implementation_revision,
        "runtime": _runtime(spark),
        "settings": {
            "seed": settings.seed,
            "train_ratio": settings.train_ratio,
            "validation_ratio": settings.validation_ratio,
            "positive_rating_threshold": settings.positive_rating_threshold,
            "candidate_size": settings.candidate_size,
            "user_chunk_size": settings.user_chunk_size,
            "als_configs": [asdict(config) for config in settings.als_configs],
        },
        "selection_policy": None,
        "split": None,
        "candidates": [],
        "selected_model_version": None,
        "final_evaluation": None,
        "serving": None,
        "failure": None,
    }
    try:
        splits = split_ratings(
            inputs.ratings,
            train_ratio=settings.train_ratio,
            validation_ratio=settings.validation_ratio,
            seed=settings.seed,
        )
        assignments = splits.assignments.persist()
        splits = (
            type(splits)(assignments) if hasattr(type(splits), "__dataclass_fields__") else splits
        )
        split_report = summarize_splits(splits)
        if split_report.total_ratings != inputs.ratings_count:
            raise ValueError("split rating count does not match validated input count")
        report["split"] = _as_mapping(split_report)
        source_count = split_report.total_ratings
        train_count = _split_count(split_report, "train", source_count)
        validation_count = _split_count(split_report, "validation", 0)
        train_and_validation = splits.train.unionByName(splits.validation)

        candidates = []
        evidence_writers = {}
        owned_writers = []
        failure_stage = "candidate_training"
        for config in settings.als_configs:
            candidate_started = time.perf_counter()
            version = new_model_version(MODEL_SELECTION)
            model = train_als_model(splits.train, config, settings.seed)
            metadata = _metadata(
                settings,
                context,
                version,
                MODEL_SELECTION,
                config,
                source_count,
                train_count,
            )
            artifact_uri = save_als_artifacts(spark, model, context.output_uri, metadata)
            loaded = load_als_artifacts(spark, context.output_uri, version)
            failure_stage = "validation"
            evidence_writer = ChunkedEvidenceWriter(
                spark,
                context.output_uri,
                context.run_id,
                stage="validation",
                model_version=version,
            )
            evidence_writers[version] = evidence_writer
            owned_writers.append(evidence_writer)
            evaluation = evaluate_offline_chunked(
                loaded.model,
                splits.train,
                splits.validation,
                inputs.service_movie_map,
                stage="validation",
                candidate_size=settings.candidate_size,
                positive_rating_threshold=settings.positive_rating_threshold,
                model_version=version,
                user_chunk_size=settings.user_chunk_size,
                seed=settings.seed,
                evidence_writer=evidence_writer,
            )
            candidate = ValidationCandidate(config, version, evaluation.summary)
            candidates.append(candidate)
            report["candidates"].append(
                {
                    "artifact_stage": MODEL_SELECTION,
                    "model_version": version,
                    "artifact_uri": artifact_uri,
                    "config": asdict(config),
                    "training_ratings_count": train_count,
                    "duration_seconds": time.perf_counter() - candidate_started,
                    "validation": evaluation.summary,
                }
            )
            failure_stage = "candidate_training"

        failure_stage = "selection"
        selection = select_best_candidate(candidates)
        selected = selection.selected
        report["selection_policy"] = selection.policy
        report["selected_model_version"] = selected.model_version
        failure_stage = "validation_evidence"
        for version, writer in evidence_writers.items():
            if version != selected.model_version:
                writer.abort()
                owned_writers.remove(writer)
        selected_writer = evidence_writers[selected.model_version]
        selected_evidence = selected_writer.finalize()
        # Finalized evidence remains rollbackable until the complete report succeeds.
        selected_report = next(
            item
            for item in report["candidates"]
            if item["model_version"] == selected.model_version
        )
        selected_report["evidence"] = selected_evidence

        failure_stage = "final_training"
        final_started = time.perf_counter()
        final_version = new_model_version(FINAL_EVALUATION)
        final_model = train_als_model(train_and_validation, selected.config, settings.seed)
        final_artifact_uri = save_als_artifacts(
            spark,
            final_model,
            context.output_uri,
            _metadata(
                settings,
                context,
                final_version,
                FINAL_EVALUATION,
                selected.config,
                source_count,
                train_count + validation_count,
            ),
        )
        final_loaded = load_als_artifacts(spark, context.output_uri, final_version)
        failure_stage = "test"
        test_writer = ChunkedEvidenceWriter(
            spark,
            context.output_uri,
            context.run_id,
            stage="test",
            model_version=final_version,
        )
        owned_writers.append(test_writer)
        test_evaluation = evaluate_offline_chunked(
            final_loaded.model,
            train_and_validation,
            splits.test,
            inputs.service_movie_map,
            stage="test",
            candidate_size=settings.candidate_size,
            positive_rating_threshold=settings.positive_rating_threshold,
            model_version=final_version,
            user_chunk_size=settings.user_chunk_size,
            seed=settings.seed,
            evidence_writer=test_writer,
        )
        failure_stage = "test_evidence"
        test_evidence = test_writer.finalize()
        report["final_evaluation"] = {
            "artifact_stage": FINAL_EVALUATION,
            "model_version": final_version,
            "artifact_uri": final_artifact_uri,
            "training_ratings_count": train_count + validation_count,
            "duration_seconds": time.perf_counter() - final_started,
            "test": test_evaluation.summary,
            "evidence": test_evidence,
        }

        failure_stage = "serving_training"
        serving_started = time.perf_counter()
        serving_version = new_model_version(SERVING)
        serving_model = train_als_model(inputs.ratings, selected.config, settings.seed)
        serving_artifact_uri = save_als_artifacts(
            spark,
            serving_model,
            context.output_uri,
            _metadata(
                settings,
                context,
                serving_version,
                SERVING,
                selected.config,
                source_count,
                source_count,
            ),
        )
        load_als_artifacts(spark, context.output_uri, serving_version)
        report["serving"] = {
            "artifact_stage": SERVING,
            "model_version": serving_version,
            "artifact_uri": serving_artifact_uri,
            "training_ratings_count": source_count,
            "duration_seconds": time.perf_counter() - serving_started,
        }
        report["status"] = "SUCCEEDED"
        report["duration_seconds"] = time.perf_counter() - run_started
        failure_stage = "report"
        write_run_report(spark, context.output_uri, report, complete=True)
        return BaselineResult(
            context.run_id, selected.model_version, final_version, serving_version
        )
    except Exception as error:
        for writer in locals().get("owned_writers", []):
            try:
                abort = getattr(writer, "abort", None)
                if abort is not None:
                    abort()
            except Exception:
                LOGGER.exception("Failed to clean ALS evaluation evidence owned by this run")
        report["status"] = "FAILED"
        report["duration_seconds"] = time.perf_counter() - run_started
        report["failure"] = {"stage": failure_stage, "error_type": type(error).__name__}
        if hasattr(error, "stage"):
            report["failure"]["chunk_stage"] = error.stage
        if hasattr(error, "chunk_id"):
            report["failure"]["chunk_id"] = error.chunk_id
        try:
            write_run_report(spark, context.output_uri, report, complete=False)
        except Exception:
            LOGGER.exception("Failed to persist the ALS failure report")
        raise
    finally:
        if assignments is not None:
            assignments.unpersist()


def _metadata(settings, context, version, stage, config, source_count, training_count):
    split_values = {}
    if stage != SERVING:
        split_values = {
            "split_method": SPLIT_METHOD,
            "split_seed": settings.seed,
            "train_ratio": settings.train_ratio,
            "validation_ratio": settings.validation_ratio,
            "min_eval_ratings": 3,
        }
    return AlsArtifactMetadata(
        model_version=version,
        artifact_stage=stage,
        input_snapshot=context.input_snapshot,
        input_uri=settings.input_uri,
        trained_at_utc=datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        config=config,
        als_seed=settings.seed,
        source_ratings_count=source_count,
        training_ratings_count=training_count,
        implementation_revision=context.implementation_revision,
        **split_values,
    )


def _split_count(report, name: str, default: int) -> int:
    by_split = getattr(report, "by_split", None)
    if isinstance(by_split, dict) and name in by_split:
        return by_split[name].ratings
    return default


def _as_mapping(value):
    try:
        return asdict(value)
    except TypeError:
        return dict(vars(value))


def _runtime(spark) -> dict[str, str | None]:
    java_version = None
    try:
        java_version = spark._jvm.java.lang.System.getProperty("java.version")  # noqa: SLF001
    except AttributeError:
        pass
    return {
        "python_version": platform.python_version(),
        "spark_version": getattr(spark, "version", None),
        "java_version": java_version,
    }
