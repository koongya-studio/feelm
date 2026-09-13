"""Deterministic validation-only ALS configuration selection."""

from __future__ import annotations

from dataclasses import dataclass
from math import isfinite

from batch.train.settings import AlsConfig

POLICY = {
    "catalog": "movielens",
    "primary_metric": "ndcg_at_10",
    "secondary_metric": "recall_at_10",
    "tie_breakers": ["rank", "max_iter", "reg_param"],
}


@dataclass(frozen=True)
class ValidationCandidate:
    config: AlsConfig
    model_version: str
    summaries: dict[str, dict[str, int | float | None]]


@dataclass(frozen=True)
class SelectionResult:
    selected: ValidationCandidate
    policy: dict[str, object]


def select_best_candidate(candidates) -> SelectionResult:
    choices = list(candidates)
    if not choices:
        raise ValueError("at least one validation candidate is required")
    configs = [item.config for item in choices]
    versions = [item.model_version for item in choices]
    if len(set(configs)) != len(configs):
        raise ValueError("duplicate candidate config")
    if len(set(versions)) != len(versions):
        raise ValueError("duplicate candidate model version")
    for item in choices:
        summary = item.summaries.get("movielens", {})
        if not summary.get("evaluated_users"):
            raise ValueError("candidate has no eligible evaluated MovieLens users")
        for name in ("ndcg_at_10", "recall_at_10"):
            value = summary.get(name)
            if not isinstance(value, int | float) or not isfinite(value):
                raise ValueError(f"candidate {name} metric must be finite")
    selected = min(
        choices,
        key=lambda item: (
            -item.summaries["movielens"]["ndcg_at_10"],
            -item.summaries["movielens"]["recall_at_10"],
            item.config.rank,
            item.config.max_iter,
            item.config.reg_param,
        ),
    )
    return SelectionResult(selected, dict(POLICY))
