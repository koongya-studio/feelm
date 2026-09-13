"""Deterministic validation-only model selection policy for S15P21E106-119."""

import importlib
import math

import pytest

from batch.train.settings import AlsConfig


def selection_module():
    # Import inside tests so the RED suite still records every required assertion.
    return importlib.import_module("batch.train.selection")


def candidate(
    rank,
    reg_param,
    max_iter,
    *,
    ndcg,
    recall,
    eligible=5,
    service_ndcg=0.1,
    model_version=None,
):
    module = selection_module()
    return module.ValidationCandidate(
        config=AlsConfig(rank, reg_param, max_iter),
        model_version=model_version or f"model-{rank}-{reg_param}-{max_iter}",
        summaries={
            "movielens": {
                "evaluated_users": eligible,
                "ndcg_at_10": ndcg,
                "recall_at_10": recall,
            },
            "service": {
                "evaluated_users": eligible,
                "ndcg_at_10": service_ndcg,
                "recall_at_10": 0.1,
            },
        },
    )


def select(candidates):
    return selection_module().select_best_candidate(candidates)


def test_movielens_ndcg_is_primary_and_service_score_does_not_select():
    higher_service = candidate(16, 0.1, 2, ndcg=0.2, recall=0.9, service_ndcg=1.0)
    higher_movielens = candidate(32, 0.1, 2, ndcg=0.3, recall=0.1, service_ndcg=0.0)
    result = select([higher_service, higher_movielens])
    assert result.selected == higher_movielens
    assert result.policy == {
        "catalog": "movielens",
        "primary_metric": "ndcg_at_10",
        "secondary_metric": "recall_at_10",
        "tie_breakers": ["rank", "max_iter", "reg_param"],
    }


def test_recall_breaks_equal_ndcg_before_model_cost():
    cheap = candidate(2, 0.01, 1, ndcg=0.3, recall=0.2)
    higher_recall = candidate(64, 0.5, 20, ndcg=0.3, recall=0.4)
    assert select([cheap, higher_recall]).selected == higher_recall


@pytest.mark.parametrize(
    ("left", "right", "winner"),
    [
        ((16, 0.2, 10), (32, 0.01, 1), (16, 0.2, 10)),
        ((16, 0.2, 5), (16, 0.01, 10), (16, 0.2, 5)),
        ((16, 0.05, 5), (16, 0.2, 5), (16, 0.05, 5)),
    ],
)
def test_complete_metric_ties_use_rank_then_iterations_then_regularization(left, right, winner):
    choices = [candidate(*values, ndcg=0.3, recall=0.4) for values in (left, right)]
    selected = select(choices).selected.config
    assert (selected.rank, selected.reg_param, selected.max_iter) == winner


def test_input_order_and_display_rounding_do_not_change_selection():
    lower = candidate(16, 0.1, 2, ndcg=0.30000000000000004, recall=0.9)
    higher = candidate(32, 0.1, 2, ndcg=0.3000000000000001, recall=0.1)
    assert select([lower, higher]).selected == higher
    assert select([higher, lower]).selected == higher


def test_zero_scores_are_valid():
    zero = candidate(16, 0.1, 2, ndcg=0.0, recall=0.0)
    assert select([zero]).selected == zero


@pytest.mark.parametrize("value", [None, math.nan, math.inf, -math.inf])
def test_invalid_metric_fails_entire_selection_instead_of_dropping_candidate(value):
    valid = candidate(16, 0.1, 2, ndcg=0.3, recall=0.2)
    invalid = candidate(32, 0.1, 2, ndcg=value, recall=0.9)
    with pytest.raises(ValueError, match="(?i)(finite|metric|ndcg)"):
        select([valid, invalid])


def test_no_eligible_movielens_users_fails_selection():
    with pytest.raises(ValueError, match="(?i)(eligible|evaluated|user)"):
        select([candidate(16, 0.1, 2, ndcg=None, recall=None, eligible=0)])


def test_duplicate_config_and_model_version_are_rejected():
    first = candidate(16, 0.1, 2, ndcg=0.2, recall=0.2, model_version="same")
    duplicate_config = candidate(16, 0.1, 2, ndcg=0.3, recall=0.3, model_version="other")
    duplicate_version = candidate(32, 0.1, 2, ndcg=0.3, recall=0.3, model_version="same")
    with pytest.raises(ValueError, match="duplicate.*config"):
        select([first, duplicate_config])
    with pytest.raises(ValueError, match="duplicate.*version"):
        select([first, duplicate_version])


def test_empty_candidate_list_is_rejected():
    with pytest.raises(ValueError, match="candidate"):
        select([])
