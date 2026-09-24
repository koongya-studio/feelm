"""Version-bound local GBT attribution without changing Candidate v6."""

from __future__ import annotations

import hashlib
import hmac
import math
import secrets
from dataclasses import dataclass

import numpy as np
import xgboost as xgb

from feelm_consumer.gbt.errors import GbtError
from feelm_consumer.gbt.inputs import merge_inputs
from feelm_consumer.gbt.schema import FEATURES, TRANSFORM
from feelm_consumer.gbt_snapshot import InputSnapshot, canonical

EVIDENCE_SCHEMA_VERSION = "gbt-evidence-v1"
EXPLANATION_VERSION = "gbt-feature-attribution-v1"
RANKING_VERSION = "gbt-personal-raw-score-v1"
CANDIDATE_VERSION = 6
MAX_EVIDENCE_PER_CANDIDATE = 3
MAX_EVIDENCE_BYTES = 2 * 1024**2
GROUPS = frozenset(
    {
        "GENRE_PATTERN",
        "RATING_PATTERN",
        "CONTENT_RELATION",
        "POPULARITY_SIGNAL",
        "AUDIENCE_RATING_SIGNAL",
    }
)
_INTERNAL_SUFFIXES = ("_known", "_known_share", "_present", "_anchor")
_SEAL_KEY = secrets.token_bytes(32)


def _feature_group(name: str) -> str | None:
    if name.endswith(_INTERNAL_SUFFIXES) or name == "verified_kobis_member":
        return None
    if name == "history_log1p" or name.startswith("history_rating_"):
        return "RATING_PATTERN"
    if name in {"history_positive_share", "history_negative_share"}:
        return "RATING_PATTERN"
    if name.startswith("country_genre_"):
        return "GENRE_PATTERN"
    if name == "tmdb_rating_raw_0_1":
        return "AUDIENCE_RATING_SIGNAL"
    if name == "reach_log1p" or name.startswith("history_positive_reach_"):
        return "POPULARITY_SIGNAL"
    if name.startswith("history_negative_reach_") or name.startswith("candidate_"):
        return "POPULARITY_SIGNAL"
    if name.startswith(("collection_", "director_", "cast_", "keyword_", "strict_")):
        return "CONTENT_RELATION"
    return None


FEATURE_GROUPS = tuple(_feature_group(name) for name in FEATURES)


def _evidence_id(snapshot: InputSnapshot, movie_id: int, group: str) -> str:
    values = (
        EXPLANATION_VERSION,
        snapshot.model_version,
        snapshot.serving_revision,
        TRANSFORM,
        RANKING_VERSION,
        snapshot.input_hash,
        str(movie_id),
        group,
    )
    digest = hashlib.sha256()
    for value in values:
        encoded = value.encode("utf-8")
        digest.update(len(encoded).to_bytes(4, "big"))
        digest.update(encoded)
    return "sha256:" + digest.hexdigest()


@dataclass(frozen=True)
class EvidenceEntry:
    evidence_id: str
    group: str
    contribution: float
    coverage: float

    def to_payload(self) -> dict[str, object]:
        return {
            "evidenceId": self.evidence_id,
            "group": self.group,
            "direction": "POSITIVE",
            "contribution": self.contribution,
            "coverage": self.coverage,
        }


@dataclass(frozen=True)
class CandidateEvidence:
    movie_id: int
    score: float
    coverage: float
    evidence: tuple[EvidenceEntry, ...]

    def to_payload(self) -> dict[str, object]:
        return {
            "movieId": self.movie_id,
            "score": self.score,
            "coverage": self.coverage,
            "evidence": [entry.to_payload() for entry in self.evidence],
        }


@dataclass(frozen=True)
class EvidenceSnapshot:
    user_id: int
    generation_id: str
    model_version: str
    serving_revision: str
    input_version: str
    items: tuple[CandidateEvidence, ...]
    _seal: bytes

    def to_payload(self) -> dict[str, object]:
        return {
            "evidenceSchemaVersion": EVIDENCE_SCHEMA_VERSION,
            "explanationVersion": EXPLANATION_VERSION,
            "userId": self.user_id,
            "generationId": self.generation_id,
            "modelVersion": self.model_version,
            "servingRevision": self.serving_revision,
            "featureVersion": TRANSFORM,
            "rankingVersion": RANKING_VERSION,
            "inputVersion": self.input_version,
            "candidateVersion": CANDIDATE_VERSION,
            "items": [item.to_payload() for item in self.items],
        }

    def sealed_for_publication(self) -> bool:
        expected = hmac.digest(_SEAL_KEY, canonical(self.to_payload()), "sha256")
        return hmac.compare_digest(self._seal, expected)


def _sealed_snapshot(**values: object) -> EvidenceSnapshot:
    provisional = EvidenceSnapshot(**values, _seal=b"")
    seal = hmac.digest(_SEAL_KEY, canonical(provisional.to_payload()), "sha256")
    return EvidenceSnapshot(**values, _seal=seal)


def empty_evidence(snapshot: InputSnapshot) -> EvidenceSnapshot:
    return _sealed_snapshot(
        user_id=snapshot.user_id,
        generation_id=snapshot.generation_id,
        model_version=snapshot.model_version,
        serving_revision=snapshot.serving_revision,
        input_version=snapshot.input_hash,
        items=(),
    )


def _candidate_evidence(
    snapshot: InputSnapshot,
    movie_id: int,
    score: float,
    contributions: np.ndarray,
) -> CandidateEvidence:
    positive = np.maximum(contributions, 0.0)
    total_positive = float(positive.sum(dtype=np.float64))
    grouped: dict[str, float] = {}
    for group, contribution in zip(FEATURE_GROUPS, positive, strict=True):
        if group is not None and contribution > 0.0:
            grouped[group] = grouped.get(group, 0.0) + float(contribution)
    ordered = sorted(grouped.items(), key=lambda item: (-item[1], item[0]))[:MAX_EVIDENCE_PER_CANDIDATE]
    evidence = []
    for group, contribution in ordered:
        coverage = contribution / total_positive if total_positive > 0.0 else 0.0
        evidence.append(
            EvidenceEntry(
                evidence_id=_evidence_id(snapshot, movie_id, group),
                group=group,
                contribution=contribution,
                coverage=coverage,
            )
        )
    coverage = min(1.0, sum(item.coverage for item in evidence))
    return CandidateEvidence(movie_id, score, coverage, tuple(evidence))


class AttributionBuilder:
    """Compute exact TreeSHAP only for the already-ranked TASTE candidates."""

    def __init__(self, scorer):
        self.scorer = scorer

    def build(self, snapshot: InputSnapshot, raw, candidate_ids: list[int]) -> EvidenceSnapshot:
        bundle = self.scorer.bundle
        if (
            snapshot.model_version != bundle.model_version
            or snapshot.serving_revision != bundle.serving_revision
            or bundle.manifest.get("transform") != TRANSFORM
        ):
            raise GbtError("EVIDENCE_REVISION_MISMATCH")
        if raw.status != "OK" or raw.input_count == 0 or not candidate_ids:
            return empty_evidence(snapshot)
        positions = np.asarray([self.scorer.features.positions[mid] for mid in candidate_ids], np.int64)
        history = merge_inputs(
            snapshot.ratings,
            snapshot.dismissed_movie_ids,
            snapshot.watched_movie_ids,
        )
        matrix = self.scorer.features.prepare(history.ratings).matrix(positions)
        if not np.isfinite(matrix).all():
            raise GbtError("NONFINITE_FEATURES")
        contributions = bundle.booster.predict(
            xgb.DMatrix(matrix),
            pred_contribs=True,
            approx_contribs=False,
            validate_features=False,
        )
        if contributions.shape != (len(candidate_ids), len(FEATURES) + 1):
            raise GbtError("INVALID_ATTRIBUTION_SHAPE")
        if not np.isfinite(contributions).all():
            raise GbtError("NONFINITE_ATTRIBUTION")
        scores = {int(mid): float(score) for mid, score in zip(raw.movie_ids, raw.scores, strict=True)}
        expected = np.asarray([scores[mid] for mid in candidate_ids], np.float64)
        reconstructed = contributions.sum(axis=1, dtype=np.float64)
        if not np.allclose(reconstructed, expected, rtol=1e-6, atol=1e-6):
            raise GbtError("ATTRIBUTION_SCORE_MISMATCH")
        items = tuple(
            _candidate_evidence(snapshot, mid, score, row[:-1])
            for mid, score, row in zip(candidate_ids, expected.tolist(), contributions, strict=True)
        )
        value = _sealed_snapshot(
            user_id=snapshot.user_id,
            generation_id=snapshot.generation_id,
            model_version=snapshot.model_version,
            serving_revision=snapshot.serving_revision,
            input_version=snapshot.input_hash,
            items=items,
        )
        validate_evidence(value.to_payload(), snapshot=snapshot, candidates=candidate_ids, scores=expected.tolist())
        return value


def _finite_number(value: object) -> bool:
    return type(value) in {int, float} and math.isfinite(value)


def validate_evidence(
    payload: object,
    *,
    snapshot: InputSnapshot,
    candidates: list[int],
    scores: list[float],
) -> None:
    root_keys = {
        "evidenceSchemaVersion",
        "explanationVersion",
        "userId",
        "generationId",
        "modelVersion",
        "servingRevision",
        "featureVersion",
        "rankingVersion",
        "inputVersion",
        "candidateVersion",
        "items",
    }
    expected_root = {
        "evidenceSchemaVersion": EVIDENCE_SCHEMA_VERSION,
        "explanationVersion": EXPLANATION_VERSION,
        "userId": snapshot.user_id,
        "generationId": snapshot.generation_id,
        "modelVersion": snapshot.model_version,
        "servingRevision": snapshot.serving_revision,
        "featureVersion": TRANSFORM,
        "rankingVersion": RANKING_VERSION,
        "inputVersion": snapshot.input_hash,
        "candidateVersion": CANDIDATE_VERSION,
    }
    if not isinstance(payload, dict) or set(payload) != root_keys:
        raise GbtError("INVALID_EVIDENCE_PAYLOAD")
    if any(payload.get(key) != value for key, value in expected_root.items()):
        raise GbtError("EVIDENCE_VERSION_MISMATCH")
    items = payload.get("items")
    if not isinstance(items, list) or len(items) != len(candidates) or len(items) > 500:
        raise GbtError("INVALID_EVIDENCE_ITEMS")
    if len(candidates) != len(scores) or len(set(candidates)) != len(candidates):
        raise GbtError("INVALID_EVIDENCE_CANDIDATES")
    for item, expected_movie_id, expected_score in zip(items, candidates, scores, strict=True):
        if not isinstance(item, dict) or set(item) != {"movieId", "score", "coverage", "evidence"}:
            raise GbtError("INVALID_CANDIDATE_EVIDENCE")
        if item["movieId"] != expected_movie_id or item["score"] != expected_score:
            raise GbtError("EVIDENCE_CANDIDATE_MISMATCH")
        coverage = item["coverage"]
        entries = item["evidence"]
        if not _finite_number(coverage) or not 0.0 <= coverage <= 1.0:
            raise GbtError("INVALID_EVIDENCE_COVERAGE")
        if not isinstance(entries, list) or len(entries) > MAX_EVIDENCE_PER_CANDIDATE:
            raise GbtError("INVALID_EVIDENCE_ENTRIES")
        seen_ids: set[str] = set()
        seen_groups: set[str] = set()
        prior: tuple[float, str] | None = None
        total_coverage = 0.0
        for entry in entries:
            if not isinstance(entry, dict) or set(entry) != {
                "evidenceId",
                "group",
                "direction",
                "contribution",
                "coverage",
            }:
                raise GbtError("INVALID_EVIDENCE_ENTRY")
            group = entry["group"]
            evidence_id = entry["evidenceId"]
            contribution = entry["contribution"]
            item_coverage = entry["coverage"]
            if (
                group not in GROUPS
                or entry["direction"] != "POSITIVE"
                or evidence_id != _evidence_id(snapshot, expected_movie_id, group)
                or evidence_id in seen_ids
                or group in seen_groups
                or not _finite_number(contribution)
                or contribution <= 0.0
                or not _finite_number(item_coverage)
                or not 0.0 < item_coverage <= 1.0
            ):
                raise GbtError("INVALID_EVIDENCE_ENTRY")
            order = (-float(contribution), group)
            if prior is not None and order < prior:
                raise GbtError("UNSORTED_EVIDENCE")
            prior = order
            seen_ids.add(evidence_id)
            seen_groups.add(group)
            total_coverage += float(item_coverage)
        if not math.isclose(float(coverage), total_coverage, rel_tol=1e-9, abs_tol=1e-9):
            raise GbtError("EVIDENCE_COVERAGE_MISMATCH")
