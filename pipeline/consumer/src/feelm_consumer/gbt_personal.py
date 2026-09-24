"""Full-catalog personal GBT and the 742 state/Candidate v6 payloads."""

from __future__ import annotations

from dataclasses import dataclass

from feelm_consumer.discovery import select_discovery_candidates
from feelm_consumer.discovery_release import validate_discovery_artifacts
from feelm_consumer.gbt.errors import GbtError
from feelm_consumer.gbt_evidence import AttributionBuilder, EvidenceSnapshot, empty_evidence
from feelm_consumer.gbt_snapshot import INPUT_SCHEMA, InputSnapshot
from feelm_consumer.group_artifacts import compare_live_mapping
from feelm_consumer.profile import ProfileInput
from feelm_consumer.ranking import RankedMovie
from feelm_consumer.results import CANDIDATE_VERSION, utc_now


@dataclass(frozen=True)
class PersonalResult:
    snapshot: InputSnapshot
    state: dict
    candidates: dict
    evidence: EvidenceSnapshot


class PersonalScorer:
    """Reuse 626 raw scores; Discovery keeps its existing group policy."""

    def __init__(self, scorer, *, groups=None, popularity=None):
        if (groups is None) != (popularity is None):
            raise GbtError("INCOMPLETE_DISCOVERY_ARTIFACTS")
        self.scorer, self.groups, self.popularity = scorer, groups, popularity
        self.attribution = AttributionBuilder(scorer)
        if groups is not None:
            validate_discovery_artifacts(groups, popularity)
            table = scorer.bundle.table
            pairs = zip(table["service_movie_id"].to_pylist(), table["tmdb_id"].to_pylist(), strict=True)
            if not compare_live_mapping(groups, pairs).activation_allowed:
                raise GbtError("DISCOVERY_MAPPING_REBOUND")

    def calculate(self, snapshot: InputSnapshot, *, generated_at: str | None = None) -> PersonalResult:
        bundle = self.scorer.bundle
        if (snapshot.model_version != bundle.model_version
                or snapshot.serving_revision != bundle.serving_revision):
            raise GbtError("SCORER_REVISION_MISMATCH")
        raw = self.scorer.score(
            snapshot.ratings,
            dismissed_movie_ids=snapshot.dismissed_movie_ids,
            excluded_movie_ids=snapshot.watched_movie_ids,
        )
        if raw.input_count != snapshot.input_count:
            raise GbtError("SCORER_INPUT_COUNT_MISMATCH")
        scores = {int(mid): float(score) for mid, score in zip(raw.movie_ids, raw.scores, strict=True)}
        population = [RankedMovie(int(mid), scores[int(mid)]) for mid in raw.ranked_ids()]
        cold_start = raw.status == "NEEDS_COLD_START" and raw.input_count == 0
        if cold_start:
            # A real candidate batch lets Backend apply its current exclusions and
            # finish the notice, even when no eligible items remain after filtering.
            # Reuse the bundle's validated popularity feature, not a GBT prediction.
            population = sorted(
                (RankedMovie(int(mid), float(self.scorer.features.movie[index, 0]))
                 for index, mid in enumerate(self.scorer.features.ids) if self.scorer.eligible[index]),
                key=lambda item: (-item.score, item.movie_id),
            )
        taste = [{"movieId": item.movie_id, "score": item.score, "reasons": []} for item in population[:500]]
        evidence = (
            self.attribution.build(snapshot, raw, [item["movieId"] for item in taste])
            if raw.status == "OK"
            else empty_evidence(snapshot)
        )
        discovery = []
        if self.groups is not None and raw.status == "OK":
            selected = select_discovery_candidates(
                self.groups, population,
                [ProfileInput(mid, score, "RATING") for mid, score in snapshot.ratings],
                popularity_ranking=self.popularity.candidates,
                watched_movie_ids=snapshot.watched_movie_ids,
                dismissed_movie_ids=snapshot.dismissed_movie_ids,
            )
            discovery = [
                {"movieId": item.movie_id, "score": item.score,
                 "reasons": [selected.reasons[item.movie_id].payload()]}
                for item in selected.candidates
            ]
        status = ("READY" if population else "EXHAUSTED") if cold_start or raw.status == "OK" else raw.status
        timestamp = generated_at or utc_now()
        common = {
            "userId": snapshot.user_id, "modelVersion": snapshot.model_version,
            "servingRevision": snapshot.serving_revision, "generationId": snapshot.generation_id,
            "inputHash": snapshot.input_hash, "candidateVersion": CANDIDATE_VERSION,
            "inputSchemaVersion": INPUT_SCHEMA,
            "updatedAt": timestamp, "status": status,
        }
        actual_ids = {mid for mid, _ in snapshot.ratings}
        state = {
            **common, "stateVersion": 1, "modelKind": "GBT",
            "ratingCount": len(snapshot.ratings), "inputCount": snapshot.input_count,
            "dismissCount": len(snapshot.dismissed_movie_ids),
            "dismissInputCount": len(snapshot.dismissed_movie_ids - actual_ids),
            "supportedInputCount": raw.input_count,
            "excludedCounts": {
                "rating": len(actual_ids), "dismissOnly": len(snapshot.dismissed_movie_ids - actual_ids),
                "watchOnly": 0 if cold_start else len(
                    snapshot.watched_movie_ids - actual_ids - snapshot.dismissed_movie_ids),
            },
            "eligibleCandidateCount": len(population),
        }
        return PersonalResult(snapshot, state,
                              {**common, "taste": taste, "discovery": discovery}, evidence)
