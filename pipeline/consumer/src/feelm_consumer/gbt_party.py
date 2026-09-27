"""Full-history GBT party scoring; no ALS or rank-list fallback."""

from __future__ import annotations

import math
from dataclasses import dataclass, replace
from statistics import fmean, pstdev
from typing import Protocol

from feelm_consumer.gbt.errors import GbtError
from feelm_consumer.gbt.inputs import merge_inputs
from feelm_consumer.gbt_party_events import PartyRequest, validate_request
from feelm_consumer.gbt_snapshot import InputSnapshot

ALGORITHM_VERSION = "party-gbt-mean-fit-v1"


class SnapshotReader(Protocol):
    def read_snapshot(self, user_id: int, generation_id: str, *, serving_revision: str | None,
                      model_version: str | None, input_hash: str) -> InputSnapshot: ...


@dataclass(frozen=True)
class PartyCandidate:
    movie_id: int
    mean_fit: float
    min_fit: float
    max_fit: float
    stddev_fit: float
    member_fit: tuple[tuple[int, float], ...]
    popularity_score: float | None = None

    def to_payload(self) -> dict:
        if self.popularity_score is not None:
            return {"movieId": self.movie_id, "partyScore": self.popularity_score,
                    "meanFit": None, "minFit": None, "maxFit": None, "stddevFit": None,
                    "memberFit": []}
        return {"movieId": self.movie_id, "partyScore": self.mean_fit, "meanFit": self.mean_fit,
                "minFit": self.min_fit, "maxFit": self.max_fit, "stddevFit": self.stddev_fit,
                "memberFit": [{"userId": uid, "score": fit} for uid, fit in self.member_fit]}


@dataclass(frozen=True)
class PartyResult:
    status: str
    candidates: tuple[PartyCandidate, ...]
    active_member_ids: tuple[int, ...]
    population_count: int
    recommendation_mode: str = "PERSONALIZED"


def midrank_percentiles(scores: dict[int, float]) -> dict[int, float]:
    """Tie-aware (rank - 0.5) / N; singletons and complete ties are 0.5."""
    ordered = sorted(scores, key=lambda mid: (scores[mid], mid))
    result = {}
    start = 0
    while start < len(ordered):
        end = start + 1
        while end < len(ordered) and scores[ordered[end]] == scores[ordered[start]]:
            end += 1
        fit = (start + (end - start) / 2) / len(ordered)
        result.update((mid, fit) for mid in ordered[start:end])
        start = end
    return result


def score_party(scorer, request: PartyRequest, snapshots: tuple[InputSnapshot, ...], *,
                limit: int = 500) -> PartyResult:
    validate_request(request)
    if type(limit) is not int or not 1 <= limit <= 500:
        raise GbtError("INVALID_PARTY_LIMIT")
    if (scorer.bundle.serving_revision != request.serving_revision
            or scorer.bundle.model_version != request.model_version):
        raise GbtError("PARTY_MODEL_MISMATCH")
    if len(snapshots) != len(request.members):
        raise GbtError("PARTY_SNAPSHOT_COUNT_MISMATCH")
    excluded = set()
    active = []
    for member, snapshot in zip(request.members, snapshots, strict=True):
        if (snapshot.user_id != member.user_id or snapshot.generation_id != member.generation_id
                or snapshot.input_hash != member.input_hash
                or snapshot.serving_revision != request.serving_revision
                or snapshot.model_version != request.model_version):
            raise GbtError("PARTY_SNAPSHOT_MISMATCH")
        # Reuse 626 actual-first merge; watched-only is an exclusion, never a rating.
        history = merge_inputs(snapshot.ratings, snapshot.dismissed_movie_ids, snapshot.watched_movie_ids)
        excluded.update(history.excluded)
        if history.ratings:
            active.append(snapshot)
    union = {mid for member in request.members for mid in member.candidate_movie_ids}
    population = tuple(sorted(union - excluded))
    if not active:
        candidates = []
        for mid in population:
            pos = scorer.features.positions.get(mid)
            if pos is None:
                raise GbtError("UNSUPPORTED_PARTY_CANDIDATE")
            if not scorer.eligible[pos]:
                raise GbtError("INELIGIBLE_PARTY_CANDIDATE")
            score = float(scorer.features.movie[pos, 0])
            if not math.isfinite(score):
                raise GbtError("NONFINITE_PARTY_SCORE")
            candidates.append(PartyCandidate(mid, 0, 0, 0, 0, (), score))
        candidates.sort(key=lambda value: (-value.popularity_score, value.movie_id))
        return PartyResult("OK" if candidates else "EXHAUSTED", tuple(candidates[:limit]),
                           (), len(population), "POPULARITY")
    fit_by_user = {}
    for snapshot in active:
        # Even an empty population validates all history support before reporting EXHAUSTED.
        scored = scorer.score(snapshot.ratings, dismissed_movie_ids=snapshot.dismissed_movie_ids,
                              excluded_movie_ids=excluded, candidate_ids=population)
        if (scored.status != "OK" or scored.model_version != request.model_version
                or scored.serving_revision != request.serving_revision):
            raise GbtError("PARTY_SCORER_RESULT_MISMATCH")
        ids = tuple(int(mid) for mid in scored.movie_ids)
        if len(ids) != len(population) or set(ids) != set(population) or len(scored.scores) != len(ids):
            raise GbtError("PARTY_POPULATION_MISMATCH")
        raw = {mid: float(value) for mid, value in zip(ids, scored.scores, strict=True)}
        if any(not math.isfinite(value) for value in raw.values()):
            raise GbtError("NONFINITE_PARTY_SCORE")
        fit_by_user[snapshot.user_id] = midrank_percentiles(raw)
    active_ids = tuple(snapshot.user_id for snapshot in active)
    if not population:
        return PartyResult("EXHAUSTED", (), active_ids, 0)
    candidates = []
    for mid in population:
        member_fit = tuple((uid, fit_by_user[uid][mid]) for uid in active_ids)
        fits = tuple(fit for _, fit in member_fit)
        candidates.append(PartyCandidate(mid, fmean(fits), min(fits), max(fits), pstdev(fits), member_fit))
    candidates.sort(key=lambda value: (-value.mean_fit, -value.min_fit, value.stddev_fit, value.movie_id))
    return PartyResult("OK", tuple(candidates[:limit]), active_ids, len(population))


def calculate_party(scorer, request: PartyRequest, reader: SnapshotReader) -> PartyResult:
    validate_request(request)
    snapshots = tuple(reader.read_snapshot(member.user_id, member.generation_id,
                                          serving_revision=request.serving_revision,
                                          model_version=request.model_version, input_hash=member.input_hash)
                      for member in request.members)
    return score_party(scorer, request, snapshots)


def resolve_party_input(request: PartyRequest, reader: SnapshotReader):
    """Resolve only the requested generations and reuse the validated bytes for scoring."""
    snapshots = tuple(reader.read_snapshot(member.user_id, member.generation_id,
                                          serving_revision=request.serving_revision,
                                          model_version=request.model_version, input_hash=member.input_hash)
                      for member in request.members)
    metadata = {(value.model_version, value.serving_revision) for value in snapshots}
    if len(metadata) != 1:
        raise GbtError("PARTY_SNAPSHOT_MODEL_MISMATCH")
    model_version, serving_revision = next(iter(metadata))
    resolved = replace(request, model_version=model_version, serving_revision=serving_revision)
    validate_request(resolved)
    return resolved, snapshots
