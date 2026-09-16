"""Deterministically re-score a party candidate union from ALS vectors.

The Consumer's published candidate scores are only meaningful within one user.
This policy combines ALS fit percentiles with within-user candidate-rank
percentiles when ALS cannot score a content-only movie or user. Redis and Spark
Streaming adapters deliberately stay outside this module.
"""

from __future__ import annotations

import math
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from statistics import fmean, pstdev

MAX_PARTY_MEMBERS = 4
MAX_MEMBER_CANDIDATES = 600
MAX_PARTY_CANDIDATES = 500


@dataclass(frozen=True)
class PartyMember:
    """One immutable member input from the Consumer's atomic Redis snapshot."""

    user_id: int
    vector: tuple[float, ...]
    candidate_movie_ids: tuple[int, ...]


@dataclass(frozen=True)
class PartyCandidate:
    """Raw candidate for Spring's later exclusion and feature re-ranking."""

    movie_id: int
    party_score: float
    mean_fit: float
    min_fit: float
    max_fit: float
    stddev_fit: float
    member_fit: tuple[tuple[int, float], ...]


@dataclass(frozen=True)
class PartyScoringResult:
    """Computed candidates plus observable ALS fallback information."""

    candidates: tuple[PartyCandidate, ...]
    missing_factor_movie_ids: tuple[int, ...]


def score_party_candidates(
    members: Sequence[PartyMember],
    item_factors: Mapping[int, Sequence[float]],
    *,
    limit: int = MAX_PARTY_CANDIDATES,
) -> PartyScoringResult:
    """Return the highest-mean normalized fits from the bounded candidate union.

    ``party_score`` is intentionally the mean normalized fit for *candidate
    retrieval*, not the product's final fairness formula.  Spring receives all
    member fits and metrics and owns final re-ranking. ALS ties use a mid-rank
    percentile. Content-only movies and users use their ordered candidate-rank
    percentile; a movie absent from a member's list gets zero rank fit.
    """
    _validate_members(members)
    if not 1 <= limit <= MAX_PARTY_CANDIDATES:
        raise ValueError(f"limit must be between 1 and {MAX_PARTY_CANDIDATES}")

    dimensions = {len(member.vector) for member in members if member.vector}
    dimension = next(iter(dimensions)) if dimensions else None
    movie_ids = _candidate_union(members)
    usable_factors: dict[int, tuple[float, ...]] = {}
    missing: list[int] = []
    for movie_id in movie_ids:
        factor = item_factors.get(movie_id)
        if factor is None:
            missing.append(movie_id)
            continue
        normalized = _finite_vector(factor, f"item factor for movie {movie_id}")
        if dimension is not None and len(normalized) != dimension:
            raise ValueError(f"item factor dimension mismatch for movie {movie_id}")
        usable_factors[movie_id] = normalized

    raw_by_user: dict[int, dict[int, float]] = {}
    for member in members:
        raw_by_user[member.user_id] = {
            movie_id: _dot(member.vector, factor)
            for movie_id, factor in usable_factors.items()
        } if member.vector else {}

    fit_by_user = {}
    for member in members:
        als_fit = _midrank_percentiles(raw_by_user[member.user_id])
        rank_fit = _candidate_rank_percentiles(member.candidate_movie_ids)
        fit_by_user[member.user_id] = {
            movie_id: als_fit.get(movie_id, rank_fit.get(movie_id, 0.0))
            for movie_id in movie_ids
        }
    candidates = tuple(
        sorted(
            (
                _candidate(movie_id, members, fit_by_user)
                for movie_id in movie_ids
            ),
            key=lambda candidate: (
                -candidate.party_score,
                -candidate.min_fit,
                candidate.stddev_fit,
                candidate.movie_id,
            ),
        )[:limit]
    )
    return PartyScoringResult(candidates=candidates, missing_factor_movie_ids=tuple(missing))


def _validate_members(members: Sequence[PartyMember]) -> None:
    if not 1 <= len(members) <= MAX_PARTY_MEMBERS:
        raise ValueError(f"party must contain between 1 and {MAX_PARTY_MEMBERS} members")
    user_ids = [member.user_id for member in members]
    if user_ids != sorted(user_ids) or len(set(user_ids)) != len(user_ids):
        raise ValueError("members must have unique user IDs in ascending order")
    dimensions = {len(member.vector) for member in members if member.vector}
    if len(dimensions) > 1:
        raise ValueError("non-empty member vectors must share a dimension")
    for member in members:
        _finite_vector(member.vector, f"vector for member {member.user_id}")
        if not member.candidate_movie_ids:
            raise ValueError(f"member {member.user_id} has no candidate movies")
        if len(member.candidate_movie_ids) > MAX_MEMBER_CANDIDATES:
            raise ValueError(f"member {member.user_id} exceeds {MAX_MEMBER_CANDIDATES} candidates")
        if any(movie_id <= 0 for movie_id in member.candidate_movie_ids):
            raise ValueError(f"member {member.user_id} has an invalid movie ID")
        if len(set(member.candidate_movie_ids)) != len(member.candidate_movie_ids):
            raise ValueError(f"member {member.user_id} has duplicate candidate movies")


def _candidate_union(members: Iterable[PartyMember]) -> tuple[int, ...]:
    movie_ids = {movie_id for member in members for movie_id in member.candidate_movie_ids}
    return tuple(sorted(movie_ids))


def _candidate_rank_percentiles(movie_ids: Sequence[int]) -> dict[int, float]:
    size = len(movie_ids)
    return {movie_id: (size - index - 0.5) / size for index, movie_id in enumerate(movie_ids)}


def _finite_vector(values: Sequence[float], description: str) -> tuple[float, ...]:
    result = tuple(float(value) for value in values)
    if any(not math.isfinite(value) for value in result):
        raise ValueError(f"{description} must contain only finite values")
    return result


def _dot(left: Sequence[float], right: Sequence[float]) -> float:
    value = sum(a * b for a, b in zip(left, right, strict=True))
    if not math.isfinite(value):
        raise ValueError("dot product must be finite")
    return value


def _midrank_percentiles(scores: Mapping[int, float]) -> dict[int, float]:
    """Map finite scores to tie-preserving percentiles in the open interval (0, 1)."""
    if not scores:
        return {}
    ordered = sorted(scores.items(), key=lambda item: (item[1], item[0]))
    result: dict[int, float] = {}
    start = 0
    while start < len(ordered):
        end = start + 1
        while end < len(ordered) and ordered[end][1] == ordered[start][1]:
            end += 1
        percentile = (start + (end - start) / 2) / len(ordered)
        for movie_id, _ in ordered[start:end]:
            result[movie_id] = percentile
        start = end
    return result


def _candidate(
    movie_id: int,
    members: Sequence[PartyMember],
    fit_by_user: Mapping[int, Mapping[int, float]],
) -> PartyCandidate:
    member_fit = tuple(
        (member.user_id, fit_by_user[member.user_id][movie_id]) for member in members
    )
    fits = tuple(fit for _, fit in member_fit)
    mean_fit = fmean(fits)
    return PartyCandidate(
        movie_id=movie_id,
        party_score=mean_fit,
        mean_fit=mean_fit,
        min_fit=min(fits),
        max_fit=max(fits),
        stddev_fit=pstdev(fits),
        member_fit=member_fit,
    )
