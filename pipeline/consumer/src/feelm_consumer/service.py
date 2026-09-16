from __future__ import annotations

import logging
from collections.abc import Sequence

import numpy as np

from feelm_consumer.artifacts import ArtifactBundle
from feelm_consumer.content import ContentBundle
from feelm_consumer.discovery import select_discovery_candidates
from feelm_consumer.group_artifacts import GroupBundle
from feelm_consumer.hybrid import build_profile, percentile_scores, score_candidate
from feelm_consumer.profile import ProfileInput, fold_in, merge_inputs
from feelm_consumer.ranking import RankedMovie, rank_candidates
from feelm_consumer.results import build_results
from feelm_consumer.xai import build_candidate_reasons

LOG = logging.getLogger(__name__)
CANDIDATE_LIMIT = 500


def calculate_user_results(
    bundle: ArtifactBundle,
    ratings: list[ProfileInput],
    onboarding: list[ProfileInput],
    candidate_version: int | None = None,
    *,
    content: ContentBundle | None = None,
    groups: GroupBundle | None = None,
    discovery_popularity: Sequence[RankedMovie] | None = None,
    watched_movie_ids: set[int] | frozenset[int] = frozenset(),
    dismissed_movie_ids: set[int] | frozenset[int] = frozenset(),
    hybrid_alpha: float = 0.5,
    min_input_count: int = 0,
) -> tuple[dict[str, object], dict[str, object]] | None:
    """Score one user's candidates from ALS factors, content vectors, or both.

    ``candidate_version`` is accepted during the Redis sequencing migration but is
    not serialized: payload versioning is fixed by ``results.CANDIDATE_VERSION``.

    Without ``content`` the candidate population and their order are the ALS-only
    ones.  With it, a movie is a candidate when either component supports it, so
    content-only service movies and users without any ALS-backed rating still get
    results.  The published score is the active model's relative ranking value;
    its formula and range are intentionally not part of the serving contract.

    Returns ``None`` when the user is below ``min_input_count`` or when no
    component can score them at all.  Publishing an arbitrary list instead would
    be indistinguishable from a real recommendation to the consumers of the Redis
    keys, so the caller skips the user instead.
    """
    inverse_mapping = {movielens_id: service_id for service_id, movielens_id in bundle.service_to_movielens.items()}
    merged_inputs = merge_inputs(ratings, onboarding)
    if len(merged_inputs) < min_input_count:
        LOG.info("below_minimum_input_count inputs=%s minimum=%s", len(merged_inputs), min_input_count)
        return None
    factor_inputs = [
        ProfileInput(bundle.service_to_movielens[item.movie_id], item.score, item.source)
        for item in merged_inputs
        if item.movie_id in bundle.service_to_movielens
    ]
    unmapped_input_count = len(merged_inputs) - len(factor_inputs)
    if unmapped_input_count:
        LOG.warning("foldin_input_not_in_publishable_mapping count=%s", unmapped_input_count)
    folded = fold_in(bundle.factors, factor_inputs, bundle.reg_param)
    if folded is None and factor_inputs:
        # Inputs existed but the solve failed (singular matrix or a non-finite
        # solution).  That is a computation failure, not "this user has no
        # signal", so it must stay loud instead of being skipped silently.
        raise ValueError("fold-in failed despite factor-backed input")
    if folded is None:
        LOG.warning("foldin_no_factor_backed_input merged_input_count=%s", len(merged_inputs))
    # The candidate limit applies to the service-publishable population, not to
    # every ALS factor.  Artifact factors intentionally include unmapped items.
    service_factors = {
        service_id: bundle.factors[movielens_id]
        for movielens_id, service_id in inverse_mapping.items()
        if service_id in bundle.movie_metadata
    }
    if content is None:
        if folded is None:
            return None
        ranked_population = rank_candidates(folded.vector, service_factors, size=len(service_factors))
        return _build_ranked_results(
            bundle,
            ranked_population,
            ratings,
            onboarding,
            merged_inputs,
            folded.vector.tolist(),
            folded.factor_backed_input_count,
            groups=groups,
            discovery_popularity=discovery_popularity,
            watched_movie_ids=watched_movie_ids,
            dismissed_movie_ids=dismissed_movie_ids,
        )

    als_scores: dict[int, float] = {}
    if folded is not None:
        als_scores = {service_id: float(folded.vector @ factor) for service_id, factor in service_factors.items()}
    content_scores = _content_scores(content, ratings, onboarding)
    if not als_scores and not content_scores:
        LOG.warning("hybrid_no_component_available merged_input_count=%s", len(merged_inputs))
        return None
    ranked_population = _hybrid_candidates(als_scores, content_scores, hybrid_alpha)
    LOG.info(
        "hybrid_candidates als_supported=%s content_supported=%s published=%s alpha=%s",
        len(als_scores),
        len(content_scores),
        min(len(ranked_population), CANDIDATE_LIMIT),
        hybrid_alpha,
    )
    return _build_ranked_results(
        bundle,
        ranked_population,
        ratings,
        onboarding,
        merged_inputs,
        folded.vector.tolist() if folded is not None else [],
        folded.factor_backed_input_count if folded is not None else 0,
        groups=groups,
        discovery_popularity=discovery_popularity,
        watched_movie_ids=watched_movie_ids,
        dismissed_movie_ids=dismissed_movie_ids,
    )


def _build_ranked_results(
    bundle: ArtifactBundle,
    ranked_population: list[RankedMovie],
    ratings: list[ProfileInput],
    onboarding: list[ProfileInput],
    merged_inputs: list[ProfileInput],
    vector: list[float],
    factor_backed_input_count: int,
    *,
    groups: GroupBundle | None,
    discovery_popularity: Sequence[RankedMovie] | None,
    watched_movie_ids: set[int] | frozenset[int],
    dismissed_movie_ids: set[int] | frozenset[int],
) -> tuple[dict[str, object], dict[str, object]]:
    # TASTE stays byte-compatible with the existing producer regardless of the
    # Discovery feature flag. Spring retains its final known/dismissed filter.
    taste = ranked_population[:CANDIDATE_LIMIT]
    if len(taste) < CANDIDATE_LIMIT:
        LOG.warning("foldin_candidate_shortage available=%s requested=500", len(taste))

    discovery = None
    if groups is not None and discovery_popularity is not None:
        selection = select_discovery_candidates(
            groups,
            ranked_population,
            ratings,
            onboarding,
            popularity_ranking=discovery_popularity,
            watched_movie_ids=watched_movie_ids,
            dismissed_movie_ids=dismissed_movie_ids,
        )
        discovery = selection.candidates
        LOG.info(
            "discovery_candidates selected=%s supplying_groups=%s eligible_groups=%s "
            "mapped_viewed=%s direction_source=%s",
            len(discovery),
            selection.supplying_group_count,
            selection.eligible_group_count,
            selection.mapped_viewed_count,
            selection.direction_source,
        )
    return build_results(
        model_version=bundle.model_version,
        vector=vector,
        rating_count=len(ratings),
        factor_backed_input_count=factor_backed_input_count,
        candidates=taste,
        discovery_candidates=discovery,
        movie_metadata=bundle.movie_metadata,
        reasons=build_candidate_reasons(taste, merged_inputs, bundle.movie_metadata),
    )


def _content_scores(
    content: ContentBundle,
    ratings: list[ProfileInput],
    onboarding: list[ProfileInput],
) -> dict[int, float]:
    """Score every movie with a content vector against the user's content profile."""
    rows: list[int] = []
    values: list[float] = []
    for item in ratings:
        row = content.row_index(item.movie_id)
        if row is not None and content.supported[row]:
            rows.append(row)
            values.append(item.score)
    profile, reason = build_profile(content, rows, values)
    if profile is None:
        profile = _onboarding_content_direction(content, ratings, onboarding)
        if profile is not None:
            reason = "ONBOARDING_DIRECTION"
    if profile is None:
        LOG.warning("content_profile_unavailable reason=%s supported_history=%s", reason, len(rows))
        return {}
    scores = content.scores(profile)
    supported_rows = np.flatnonzero(content.supported)
    supported_scores = scores[supported_rows]
    # The profile is a signed combination, so a history that leans negative can
    # leave every movie at or below zero.  Those candidates are still published:
    # a list is more useful to the product than nothing.  What they are *not* is
    # a ranking — every movie sharing no token with the profile scores exactly
    # 0.0, so tens of thousands tie at the top and the published order among them
    # is the service movie ID order.  The minimum input count keeps most users
    # out of this state; the log line marks the rest.
    if supported_scores.size and float(supported_scores.max()) <= 0.0:
        LOG.warning(
            "content_scores_non_positive supported_history=%s tied_at_max=%s",
            len(rows),
            int((supported_scores == supported_scores.max()).sum()),
        )
    return dict(
        zip(
            content.service_movie_ids[supported_rows].tolist(),
            supported_scores.tolist(),
            strict=True,
        )
    )


def _onboarding_content_direction(
    content: ContentBundle,
    ratings: list[ProfileInput],
    onboarding: list[ProfileInput],
) -> np.ndarray | None:
    rated_ids = {entry.movie_id for entry in ratings}
    profile = np.zeros(content.vocabulary_size, dtype=np.float64)
    supported_count = 0
    for item in onboarding:
        if item.movie_id in rated_ids:
            continue
        row = content.row_index(item.movie_id)
        if row is None or not content.supported[row]:
            continue
        indices, values = content.nonzeros(row)
        sign = 1.0 if item.score >= 3.5 else -1.0
        profile[indices] += sign * values
        supported_count += 1
    norm = float(np.linalg.norm(profile))
    if supported_count == 0 or not np.isfinite(norm) or norm <= 1e-8:
        return None
    return profile / norm


def _hybrid_candidates(
    als_scores: dict[int, float], content_scores: dict[int, float], hybrid_alpha: float
) -> list[RankedMovie]:
    """Mix tie-aware percentiles of each component, then order and cut the candidates."""
    als_percentiles = _percentiles(als_scores)
    content_percentiles = _percentiles(content_scores)
    ranked: list[RankedMovie] = []
    for service_id in set(als_percentiles) | set(content_percentiles):
        scored = score_candidate(als_percentiles.get(service_id), content_percentiles.get(service_id), hybrid_alpha)
        if scored is not None:
            ranked.append(RankedMovie(service_id, scored.score))
    ranked.sort(key=lambda item: (-item.score, item.movie_id))
    return ranked


def _percentiles(scores: dict[int, float]) -> dict[int, float]:
    """Percentile a component over exactly the movies that component supports."""
    service_ids = sorted(scores)
    return dict(zip(service_ids, percentile_scores([scores[key] for key in service_ids]), strict=True))
