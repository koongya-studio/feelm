from __future__ import annotations

import logging

from feelm_consumer.artifacts import ArtifactBundle
from feelm_consumer.profile import ProfileInput, fold_in, merge_inputs
from feelm_consumer.ranking import rank_candidates
from feelm_consumer.results import build_results

LOG = logging.getLogger(__name__)


def calculate_user_results(
    bundle: ArtifactBundle,
    ratings: list[ProfileInput],
    onboarding: list[ProfileInput],
    candidate_version: int,
) -> tuple[dict[str, object], dict[str, object]] | None:
    inverse_mapping = {movielens_id: service_id for service_id, movielens_id in bundle.service_to_movielens.items()}
    merged_inputs = merge_inputs(ratings, onboarding)
    factor_inputs = [
        ProfileInput(bundle.service_to_movielens[item.movie_id], item.score, item.source)
        for item in merged_inputs
        if item.movie_id in bundle.service_to_movielens
    ]
    unmapped_input_count = len(merged_inputs) - len(factor_inputs)
    if unmapped_input_count:
        LOG.warning("foldin_input_not_in_publishable_mapping count=%s", unmapped_input_count)
    folded = fold_in(bundle.factors, factor_inputs, bundle.reg_param)
    if folded is None:
        LOG.warning("foldin_no_factor_backed_input merged_input_count=%s", len(merged_inputs))
        return None
    # The candidate limit applies to the service-publishable population, not to
    # every ALS factor.  Artifact factors intentionally include unmapped items.
    service_factors = {
        service_id: bundle.factors[movielens_id]
        for movielens_id, service_id in inverse_mapping.items()
        if service_id in bundle.movie_metadata
    }
    service_ranked = rank_candidates(folded.vector, service_factors)
    if len(service_ranked) < 500:
        LOG.warning("foldin_candidate_shortage available=%s requested=500", len(service_ranked))
    return build_results(
        model_version=bundle.model_version,
        vector=folded.vector.tolist(),
        rating_count=len(ratings),
        factor_backed_input_count=folded.factor_backed_input_count,
        candidates=service_ranked,
        candidate_version=candidate_version,
        movie_metadata=bundle.movie_metadata,
    )
