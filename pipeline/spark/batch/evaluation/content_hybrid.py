"""Pure score normalization and mixing policy for Content/ALS comparison."""

from __future__ import annotations

import hashlib
import json
import math
import os
import shutil
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from uuid import uuid4

from batch.content.artifacts import _identifier


@dataclass(frozen=True)
class HybridPolicy:
    """Mix ALS and Content percentiles with a fixed ALS weight."""

    alpha: float

    def __post_init__(self) -> None:
        if not math.isfinite(self.alpha) or not 0 <= self.alpha <= 1:
            raise ValueError("hybrid alpha must be finite and between zero and one")


@dataclass(frozen=True)
class SinglePolicy:
    component: str


@dataclass(frozen=True)
class CandidateScore:
    score: float | None
    branch: str
    effective_als_weight: float | None


ALS_ONLY = SinglePolicy("als")
CONTENT_ONLY = SinglePolicy("content")
HYBRID_POLICIES = (HybridPolicy(0.25), HybridPolicy(0.5), HybridPolicy(0.75))


@dataclass(frozen=True)
class RankingSummary:
    """Small, serialisable offline ranking result for one fixed policy."""

    target_users: int
    evaluated_users: int
    users_without_positives: int
    positive_items: int
    recall_at_10: float | None
    ndcg_at_10: float | None
    # These fields are populated only by the evaluator.  Selection refuses a
    # hand-built summary, even if its public labels claim to be validation.
    stage: str | None = None
    split: str | None = None
    content_version: str | None = None
    als_model_version: str | None = None
    mapping_snapshot: str | None = None
    _issued_by_evaluator: object | None = field(default=None, repr=False, compare=False)


_SUMMARY_ISSUER = object()
_SELECTION_RECORD_ISSUER = object()


@dataclass(frozen=True)
class EvaluationBundle:
    """Immutable provenance supplied by the stage executor, not by selection."""

    stage: str
    split: str
    content_version: str
    als_model_version: str
    mapping_snapshot: str

    def __post_init__(self) -> None:
        if self.stage not in {"validation", "test"} or self.split != self.stage:
            raise ValueError("evaluation bundle stage and split must match validation or test")
        _validate_version_components(
            self.content_version, self.als_model_version, self.mapping_snapshot
        )


@dataclass(frozen=True)
class PolicySelection:
    """Validation-only policy decisions, kept separate from final test output."""

    overall: HybridPolicy | SinglePolicy
    hybrid: HybridPolicy


@dataclass(frozen=True)
class ValidationSelectionRecord:
    """Serializable, immutable validation decision bound to its input bundle."""

    stage: str
    split: str
    content_version: str
    als_model_version: str
    mapping_snapshot: str
    overall: HybridPolicy | SinglePolicy
    hybrid: HybridPolicy
    _issued_by_selection_factory: object | None = field(default=None, repr=False, compare=False)

    def __post_init__(self) -> None:
        if self.stage != "validation" or self.split != "validation":
            raise ValueError("selection record must be derived from validation only")
        _validate_version_components(
            self.content_version, self.als_model_version, self.mapping_snapshot
        )
        if (
            self.overall not in (ALS_ONLY, CONTENT_ONLY, *HYBRID_POLICIES)
            or self.hybrid not in HYBRID_POLICIES
        ):
            raise ValueError("selection record must contain only fixed policies")


def percentile_scores(scores: list[float]) -> list[float]:
    """Return tie-aware percentiles over the full supported candidate population."""
    if not scores:
        return []
    if any(not math.isfinite(score) for score in scores):
        raise ValueError("component scores must be finite")
    ordered = sorted(enumerate(scores), key=lambda pair: pair[1])
    result = [0.0] * len(scores)
    start = 0
    while start < len(ordered):
        end = start + 1
        while end < len(ordered) and ordered[end][1] == ordered[start][1]:
            end += 1
        percentile = (start + 0.5 * (end - start)) / len(ordered)
        for index, _ in ordered[start:end]:
            result[index] = percentile
        start = end
    return result


def score_candidate(
    als_percentile: float | None,
    content_percentile: float | None,
    policy: HybridPolicy | SinglePolicy,
) -> CandidateScore:
    """Score one candidate while distinguishing normal absence from invalid output."""
    _validate_component(als_percentile)
    _validate_component(content_percentile)
    if isinstance(policy, SinglePolicy):
        selected = als_percentile if policy.component == "als" else content_percentile
        branch = "ALS_ONLY" if policy.component == "als" else "CONTENT_ONLY"
        return CandidateScore(selected, branch if selected is not None else "UNSUPPORTED", None)

    if als_percentile is not None and content_percentile is not None:
        return CandidateScore(
            policy.alpha * als_percentile + (1 - policy.alpha) * content_percentile,
            "MIXED",
            policy.alpha,
        )
    if als_percentile is not None:
        return CandidateScore(als_percentile, "ALS_ONLY", 1.0)
    if content_percentile is not None:
        return CandidateScore(content_percentile, "CONTENT_ONLY", 0.0)
    return CandidateScore(None, "UNSUPPORTED", None)


def matched_quality_catalog(
    movies: Iterable[Mapping[str, object]], *, known_movielens_ids: set[int]
) -> list[Mapping[str, object]]:
    """Keep unobserved MATCHED movies for holdout quality evaluation."""
    return [
        movie
        for movie in movies
        if movie.get("mapping_status") == "MATCHED"
        and movie.get("movielens_movie_id") not in known_movielens_ids
    ]


def service_coverage_catalog(
    movies: Iterable[Mapping[str, object]], *, known_movielens_ids: set[int]
) -> tuple[list[Mapping[str, object]], dict[str, int]]:
    """Keep MATCHED and Content-only movies while exposing excluded categories."""
    catalog: list[Mapping[str, object]] = []
    excluded = {"known_matched": 0, "ambiguous": 0}
    for movie in movies:
        status = movie.get("mapping_status")
        movie_id = movie.get("movielens_movie_id")
        if status == "TMDB_ID_AMBIGUOUS":
            excluded["ambiguous"] += 1
        elif status == "MATCHED" and movie_id in known_movielens_ids:
            excluded["known_matched"] += 1
        elif status in {"MATCHED", "SERVICE_ONLY_CONTENT_BASED"}:
            catalog.append(movie)
    return catalog, excluded


def rank_candidates(
    candidates: Iterable[Mapping[str, object]], *, limit: int
) -> list[Mapping[str, object]]:
    """Deduplicate and order candidates by score descending then service ID ascending."""
    if limit <= 0:
        raise ValueError("limit must be positive")
    best: dict[int, Mapping[str, object]] = {}
    for candidate in candidates:
        service_id = candidate.get("service_movie_id")
        score = candidate.get("score")
        if isinstance(service_id, bool) or not isinstance(service_id, int) or service_id <= 0:
            raise ValueError("service_movie_id must be a positive integer")
        if not isinstance(score, int | float) or not math.isfinite(score):
            raise ValueError("candidate score must be finite")
        previous = best.get(service_id)
        if previous is None or float(score) > float(previous["score"]):
            best[service_id] = candidate
    ordered = sorted(
        best.values(),
        key=lambda item: (-float(item["score"]), int(item["service_movie_id"])),
    )
    return ordered[:limit]


def evaluate_top_k(
    recommendations: Mapping[int, Sequence[int]],
    positives: Mapping[int, Sequence[int]],
    target_users: Iterable[int],
    *,
    k: int = 10,
    allowed_positive_items: set[int] | None = None,
    rejected_positive_items: set[int] | None = None,
    bundle: EvaluationBundle | None = None,
) -> RankingSummary:
    """Evaluate fixed rankings without silently dropping users with no results."""
    if k <= 0:
        raise ValueError("k must be positive")
    users = sorted(set(target_users))
    if any(isinstance(user, bool) or not isinstance(user, int) or user <= 0 for user in users):
        raise ValueError("target users must be positive integers")
    recall_values: list[float] = []
    ndcg_values: list[float] = []
    positive_items = 0
    for user in users:
        actual = set(positives.get(user, ()))
        if rejected_positive_items is not None and actual & rejected_positive_items:
            raise ValueError("quality positives include content-only or ambiguous items")
        if allowed_positive_items is not None and not actual <= allowed_positive_items:
            raise ValueError("quality positives are outside the MATCHED catalog")
        if not actual:
            continue
        positive_items += len(actual)
        ranked = list(recommendations.get(user, ()))[:k]
        if len(ranked) != len(set(ranked)):
            raise ValueError("recommendations must not repeat an item for one user")
        hits = [item for item in ranked if item in actual]
        recall_values.append(len(hits) / len(actual))
        dcg = sum(1 / math.log2(rank + 2) for rank, item in enumerate(ranked) if item in actual)
        ideal = sum(1 / math.log2(rank + 2) for rank in range(min(k, len(actual))))
        ndcg_values.append(dcg / ideal)
    evaluated = len(recall_values)
    return RankingSummary(
        target_users=len(users),
        evaluated_users=evaluated,
        users_without_positives=len(users) - evaluated,
        positive_items=positive_items,
        recall_at_10=sum(recall_values) / evaluated if evaluated else None,
        ndcg_at_10=sum(ndcg_values) / evaluated if evaluated else None,
        stage=bundle.stage if bundle else None,
        split=bundle.split if bundle else None,
        content_version=bundle.content_version if bundle else None,
        als_model_version=bundle.als_model_version if bundle else None,
        mapping_snapshot=bundle.mapping_snapshot if bundle else None,
        _issued_by_evaluator=_SUMMARY_ISSUER if bundle else None,
    )


def select_validation_policies(
    summaries: Mapping[HybridPolicy | SinglePolicy, RankingSummary],
) -> PolicySelection:
    """Choose overall and hybrid-only winners only from validation summaries."""
    expected = (ALS_ONLY, CONTENT_ONLY, *HYBRID_POLICIES)
    if set(summaries) != set(expected):
        raise ValueError("validation summaries must contain exactly the fixed policies")
    for policy, summary in summaries.items():
        if (
            summary.evaluated_users <= 0
            or summary.ndcg_at_10 is None
            or summary.recall_at_10 is None
        ):
            raise ValueError(f"policy has no valid validation population: {policy!r}")
        if not math.isfinite(summary.ndcg_at_10) or not math.isfinite(summary.recall_at_10):
            raise ValueError("validation metrics must be finite")
    return PolicySelection(
        overall=_best_policy(expected, summaries),
        hybrid=_best_policy(HYBRID_POLICIES, summaries),
    )


def create_validation_selection_record(
    summaries: Mapping[HybridPolicy | SinglePolicy, RankingSummary],
    *,
    content_version: str,
    als_model_version: str,
    mapping_snapshot: str,
    stage: str,
    split: str,
) -> ValidationSelectionRecord:
    """Seal a validation-only decision to exact input versions.

    The caller must explicitly supply the stage and split that produced
    ``summaries``; test-stage summaries are rejected before policy selection.
    """
    expected_bundle = EvaluationBundle(
        stage=stage,
        split=split,
        content_version=content_version,
        als_model_version=als_model_version,
        mapping_snapshot=mapping_snapshot,
    )
    if expected_bundle.stage != "validation":
        raise ValueError("selection record must be derived from validation only")
    for summary in summaries.values():
        if summary._issued_by_evaluator is not _SUMMARY_ISSUER or (
            summary.stage,
            summary.split,
            summary.content_version,
            summary.als_model_version,
            summary.mapping_snapshot,
        ) != ("validation", "validation", content_version, als_model_version, mapping_snapshot):
            raise ValueError(
                "validation summaries must be evaluator-issued and bound to the input bundle"
            )
    selection = select_validation_policies(summaries)
    return ValidationSelectionRecord(
        stage=stage,
        split=split,
        content_version=content_version,
        als_model_version=als_model_version,
        mapping_snapshot=mapping_snapshot,
        overall=selection.overall,
        hybrid=selection.hybrid,
        _issued_by_selection_factory=_SELECTION_RECORD_ISSUER,
    )


def save_validation_selection(record: ValidationSelectionRecord, path: str) -> None:
    """Persist a canonical immutable record; existing records are never replaced."""
    if (
        not isinstance(record, ValidationSelectionRecord)
        or record._issued_by_selection_factory is not _SELECTION_RECORD_ISSUER
    ):
        raise ValueError("selection record must be issued by the validation selector")
    destination = _canonical_selection_destination(
        path,
        record.content_version,
        record.als_model_version,
        record.mapping_snapshot,
    )
    published = destination.parent
    digest_name = "selection.json.sha256"
    if published.exists():
        raise FileExistsError(f"selection record already exists: {destination}")
    stage = published.parent / f".{published.name}.stage-{uuid4().hex}"
    payload = {
        "stage": record.stage,
        "split": record.split,
        "content_version": record.content_version,
        "als_model_version": record.als_model_version,
        "mapping_snapshot": record.mapping_snapshot,
        "overall": _policy_id(record.overall),
        "hybrid": _policy_id(record.hybrid),
    }
    serialized = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    try:
        stage.mkdir(parents=True)
        staged_selection = stage / destination.name
        staged_digest = stage / digest_name
        with staged_selection.open("xb") as handle:
            handle.write(serialized)
        with staged_digest.open("x", encoding="ascii") as handle:
            handle.write(hashlib.sha256(serialized).hexdigest())
        if (
            hashlib.sha256(staged_selection.read_bytes()).hexdigest()
            != staged_digest.read_text(encoding="ascii").strip()
        ):
            raise ValueError("selection staging digest mismatch")
        if published.exists():
            raise FileExistsError(f"selection record already exists: {destination}")
        os.rename(stage, published)
    except Exception:
        # A competing writer may own the public directory; clean only this writer's stage.
        if stage.exists():
            shutil.rmtree(stage, ignore_errors=True)
        raise


def load_validation_selection(path: str) -> ValidationSelectionRecord:
    """Reload a selection record without accepting hand-authored policy values."""
    from pathlib import Path

    try:
        destination = Path(path)
        serialized = destination.read_bytes()
        expected_digest = (
            destination.with_name("selection.json.sha256").read_text(encoding="ascii").strip()
        )
        actual_digest = hashlib.sha256(serialized).hexdigest()
        if expected_digest != actual_digest:
            raise ValueError("selection record digest mismatch")
        value = json.loads(serialized)
        record = ValidationSelectionRecord(
            value["stage"],
            value["split"],
            value["content_version"],
            value["als_model_version"],
            value["mapping_snapshot"],
            _policy_from_id(value["overall"]),
            _policy_from_id(value["hybrid"]),
        )
        _canonical_selection_destination(
            path,
            record.content_version,
            record.als_model_version,
            record.mapping_snapshot,
        )
        return record
    except (OSError, KeyError, TypeError, json.JSONDecodeError, ValueError) as error:
        raise ValueError("invalid validation selection record") from error


def final_test_policies_from_selection(
    selection_path: str,
    *,
    content_version: str,
    als_model_version: str,
    mapping_snapshot: str,
    stage: str = "test",
    split: str = "test",
) -> tuple[HybridPolicy | SinglePolicy, ...]:
    """Reload a digest-bound validation decision for the only final comparison.

    This deliberately takes a path, never an in-memory record or policy tuple:
    final execution must cross the persisted digest/reload boundary.
    """
    if not isinstance(selection_path, str):
        raise ValueError("final evaluation requires a sealed selection path")
    _validate_version_components(content_version, als_model_version, mapping_snapshot)
    record = load_validation_selection(selection_path)
    if stage != "test" or split != "test":
        raise ValueError("final evaluation must use the test stage and split")
    if (record.content_version, record.als_model_version, record.mapping_snapshot) != (
        content_version,
        als_model_version,
        mapping_snapshot,
    ):
        raise ValueError("selection record input bundle mismatch")
    return ALS_ONLY, CONTENT_ONLY, record.hybrid


def _validate_version_components(
    content_version: str, als_model_version: str, mapping_snapshot: str
) -> None:
    """Use the artifact component rule at every selection provenance boundary."""
    _identifier(content_version, "content_version")
    _identifier(als_model_version, "als_model_version")
    _identifier(mapping_snapshot, "mapping_snapshot")


def _canonical_selection_destination(
    path: str, content_version: str, als_model_version: str, mapping_snapshot: str
) -> Path:
    """Require the selection path for exactly one validated version tuple."""
    _validate_version_components(content_version, als_model_version, mapping_snapshot)
    if not isinstance(path, str):
        raise ValueError("selection path must be a string")
    destination = Path(path)
    try:
        output_uri = destination.parents[5]
    except IndexError as error:
        raise ValueError("selection path must use the canonical tuple layout") from error
    expected = (
        output_uri
        / "content-evaluations"
        / content_version
        / als_model_version
        / mapping_snapshot
        / "selection"
        / "selection.json"
    )
    if destination != expected:
        raise ValueError("selection path must use the canonical tuple layout")
    return destination


def assert_no_known_holdout_overlap(
    known_pairs: Iterable[tuple[int, int]], holdout_pairs: Iterable[tuple[int, int]]
) -> None:
    """Reject a split before profile, vocabulary, or candidate work starts."""
    overlap = set(known_pairs) & set(holdout_pairs)
    if overlap:
        raise ValueError(f"known and holdout ratings overlap: {sorted(overlap)[:3]}")


def coverage_summary(
    eligible_service_ids: Iterable[int],
    recommendations: Mapping[int, Sequence[int]],
    service_only_ids: Iterable[int],
) -> dict[str, int]:
    """Report service reach separately from MovieLens quality denominators."""
    eligible = set(eligible_service_ids)
    service_only = set(service_only_ids)
    if not service_only <= eligible:
        raise ValueError("service-only IDs must be eligible")
    recommended = {item for values in recommendations.values() for item in values}
    return {
        "eligible_items": len(eligible),
        "recommended_items": len(recommended & eligible),
        "service_only_recommended_items": len(recommended & service_only),
    }


def stage_lineage(
    stage: str,
    train_ratings: Sequence[object],
    validation_ratings: Sequence[object],
    test_ratings: Sequence[object],
) -> tuple[tuple[object, ...], tuple[object, ...]]:
    """Return the only legal known/holdout inputs for an evaluation stage.

    Callers receive immutable tuples so a final-evaluation path cannot append
    test observations while building profiles or a vocabulary.
    """
    if stage == "validation":
        return tuple(train_ratings), tuple(validation_ratings)
    if stage == "test":
        return tuple(train_ratings) + tuple(validation_ratings), tuple(test_ratings)
    raise ValueError("stage must be validation or test; serving is not evaluable")


def _best_policy(
    policies: Sequence[HybridPolicy | SinglePolicy],
    summaries: Mapping[HybridPolicy | SinglePolicy, RankingSummary],
) -> HybridPolicy | SinglePolicy:
    def key(policy: HybridPolicy | SinglePolicy):
        summary = summaries[policy]
        policy_order = (
            0 if policy == ALS_ONLY else 1 if policy == CONTENT_ONLY else 2,
            policy.alpha if isinstance(policy, HybridPolicy) else 0.0,
        )
        return (-float(summary.ndcg_at_10), -float(summary.recall_at_10), policy_order)

    return min(policies, key=key)


def _validate_component(value: float | None) -> None:
    if value is not None and not math.isfinite(value):
        raise ValueError("component percentile must be finite")


def _policy_id(policy: HybridPolicy | SinglePolicy) -> str:
    if policy == ALS_ONLY:
        return "ALS_ONLY"
    if policy == CONTENT_ONLY:
        return "CONTENT_ONLY"
    if isinstance(policy, HybridPolicy) and policy in HYBRID_POLICIES:
        return f"HYBRID_A{int(policy.alpha * 100):03d}"
    raise ValueError("policy is not fixed")


def _policy_from_id(value: object) -> HybridPolicy | SinglePolicy:
    by_id = {
        "ALS_ONLY": ALS_ONLY,
        "CONTENT_ONLY": CONTENT_ONLY,
        "HYBRID_A025": HybridPolicy(0.25),
        "HYBRID_A050": HybridPolicy(0.5),
        "HYBRID_A075": HybridPolicy(0.75),
    }
    if value not in by_id:
        raise ValueError("selection policy is not fixed")
    return by_id[value]
