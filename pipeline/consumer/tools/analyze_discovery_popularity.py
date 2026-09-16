"""Compare TMDB popularity signals with annual KOBIS movie rollups."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import pyarrow.parquet as pq


def _average_ranks(values: np.ndarray) -> np.ndarray:
    order = np.argsort(values, kind="mergesort")
    ranks = np.empty(len(values), dtype=np.float64)
    start = 0
    while start < len(order):
        end = start + 1
        while end < len(order) and values[order[end]] == values[order[start]]:
            end += 1
        ranks[order[start:end]] = (start + end - 1) / 2.0 + 1.0
        start = end
    return ranks


def _correlation(left: np.ndarray, right: np.ndarray) -> float | None:
    if len(left) < 2 or float(np.std(left)) <= 0 or float(np.std(right)) <= 0:
        return None
    return float(np.corrcoef(left, right)[0, 1])


def _summary(values: np.ndarray) -> dict[str, float | int | None]:
    finite = values[np.isfinite(values)]
    if not len(finite):
        return {"count": 0, "min": None, "p25": None, "median": None, "p75": None, "p90": None, "max": None}
    quantiles = np.quantile(finite, [0.25, 0.5, 0.75, 0.9])
    return {
        "count": int(len(finite)),
        "min": float(np.min(finite)),
        "p25": float(quantiles[0]),
        "median": float(quantiles[1]),
        "p75": float(quantiles[2]),
        "p90": float(quantiles[3]),
        "max": float(np.max(finite)),
    }


def _descending_percentiles(values: np.ndarray, identifiers: np.ndarray) -> np.ndarray:
    order = np.lexsort((identifiers, values))
    scores = np.empty(len(values), dtype=np.float64)
    start = 0
    while start < len(order):
        end = start + 1
        while end < len(order) and values[order[end]] == values[order[start]]:
            end += 1
        midpoint = start + 0.5 * (end - start - 1)
        scores[order[start:end]] = midpoint / max(1, len(order) - 1)
        start = end
    return scores


def _group_counts(group_ids: np.ndarray) -> dict[str, object]:
    _, counts = np.unique(group_ids, return_counts=True)
    return {
        "nonempty_group_count": int(len(counts)),
        "movies_per_nonempty_group": _summary(counts.astype(np.float64)),
        "groups_with_at_least": {
            str(limit): int(np.count_nonzero(counts >= limit)) for limit in (1, 2, 5, 10, 25)
        },
    }


def analyze(kobis_path: Path, catalog_path: Path, assignments_path: Path) -> dict[str, object]:
    kobis = pq.read_table(
        kobis_path,
        columns=[
            "kobis_movie_code",
            "last_observed_cumulative_admissions",
            "tmdb_match_status",
            "proposed_tmdb_id",
        ],
    ).to_pylist()
    catalog = pq.read_table(
        catalog_path,
        columns=[
            "service_movie_id",
            "tmdb_id",
            "title",
            "tmdb_vote_count",
            "tmdb_popularity",
            "raw_vote_average_number",
            "quality_state",
        ],
    ).to_pylist()
    by_tmdb = {int(row["tmdb_id"]): row for row in catalog}
    with np.load(assignments_path, allow_pickle=False) as assignments:
        group_by_service = {
            int(service_id): int(group_id)
            for service_id, group_id in zip(assignments["service_movie_id"], assignments["group_id"], strict=True)
        }

    all_admissions = np.asarray(
        [
            float(row["last_observed_cumulative_admissions"])
            if row["last_observed_cumulative_admissions"] is not None
            else np.nan
            for row in kobis
        ]
    )
    valid_admissions = np.isfinite(all_admissions) & (all_admissions > 0)
    valid_kobis_rows = [row for row, valid in zip(kobis, valid_admissions, strict=True) if valid]
    all_kobis_scores = _descending_percentiles(
        all_admissions[valid_admissions],
        np.asarray([row["kobis_movie_code"] for row in valid_kobis_rows]),
    )
    kobis_score_by_code = {
        str(row["kobis_movie_code"]): float(score)
        for row, score in zip(valid_kobis_rows, all_kobis_scores, strict=True)
    }

    linked: list[dict[str, object]] = []
    for row in kobis:
        if row["tmdb_match_status"] != "PROPOSED_UNIQUE":
            continue
        proposed = row["proposed_tmdb_id"]
        catalog_row = by_tmdb.get(int(proposed)) if proposed is not None else None
        if catalog_row is None:
            continue
        service_id = int(catalog_row["service_movie_id"])
        group_id = group_by_service.get(service_id)
        admissions = row["last_observed_cumulative_admissions"]
        if group_id is None or admissions is None or int(admissions) <= 0:
            continue
        linked.append(
            {
                "service_movie_id": service_id,
                "tmdb_id": int(catalog_row["tmdb_id"]),
                "title": catalog_row["title"],
                "group_id": group_id,
                "admissions": int(admissions),
                "kobis_score": kobis_score_by_code[str(row["kobis_movie_code"])],
                "vote_count": catalog_row["tmdb_vote_count"],
                "popularity": catalog_row["tmdb_popularity"],
                "vote_average": catalog_row["raw_vote_average_number"],
                "quality_state": catalog_row["quality_state"],
            }
        )

    admissions = np.asarray([row["admissions"] for row in linked], dtype=np.float64)
    service_ids = np.asarray([row["service_movie_id"] for row in linked], dtype=np.int64)
    kobis_scores = np.asarray([row["kobis_score"] for row in linked], dtype=np.float64)
    metric_results: dict[str, object] = {}
    metric_values = {
        "tmdb_vote_count": np.asarray(
            [float(row["vote_count"]) if row["vote_count"] is not None else np.nan for row in linked]
        ),
        "tmdb_popularity": np.asarray(
            [float(row["popularity"]) if row["popularity"] is not None else np.nan for row in linked]
        ),
        "tmdb_vote_average": np.asarray(
            [float(row["vote_average"]) if row["vote_average"] is not None else np.nan for row in linked]
        ),
    }
    catalog_metric_values = {
        "tmdb_vote_count": np.asarray(
            [float(row["tmdb_vote_count"]) if row["tmdb_vote_count"] is not None else np.nan for row in catalog]
        ),
        "tmdb_popularity": np.asarray(
            [float(row["tmdb_popularity"]) if row["tmdb_popularity"] is not None else np.nan for row in catalog]
        ),
        "tmdb_vote_average": np.asarray(
            [
                float(row["raw_vote_average_number"])
                if row["raw_vote_average_number"] is not None
                else np.nan
                for row in catalog
            ]
        ),
    }
    catalog_service_ids = np.asarray([row["service_movie_id"] for row in catalog], dtype=np.int64)
    for name, values in metric_values.items():
        valid = np.isfinite(values) & (values >= 0)
        left = np.log1p(admissions[valid])
        right = np.log1p(values[valid]) if name != "tmdb_vote_average" else values[valid]
        full_values = catalog_metric_values[name]
        full_valid = np.isfinite(full_values) & (full_values >= 0)
        full_scores = _descending_percentiles(full_values[full_valid], catalog_service_ids[full_valid])
        score_by_service = {
            int(service_id): float(score)
            for service_id, score in zip(catalog_service_ids[full_valid], full_scores, strict=True)
        }
        source_scores = np.asarray([score_by_service[int(service_id)] for service_id in service_ids[valid]])
        comparable_kobis_scores = kobis_scores[valid]
        cutoff_order = np.lexsort((catalog_service_ids[full_valid], -full_values[full_valid]))
        equal_size_index = min(len(valid_kobis_rows), len(cutoff_order)) - 1
        metric_results[name] = {
            "values": _summary(values[valid]),
            "full_catalog_valid_count": int(np.count_nonzero(full_valid)),
            "full_catalog_top_kobis_size_cutoff": float(full_values[full_valid][cutoff_order[equal_size_index]]),
            "pearson_after_log": _correlation(left, right),
            "spearman": _correlation(_average_ranks(left), _average_ranks(right)),
            "max_source_winner_counts": {
                "tmdb": int(np.count_nonzero(source_scores > comparable_kobis_scores)),
                "kobis": int(np.count_nonzero(source_scores < comparable_kobis_scores)),
                "tie": int(np.count_nonzero(source_scores == comparable_kobis_scores)),
            },
        }

    group_ids = np.asarray([row["group_id"] for row in linked], dtype=np.int64)
    top_kobis = np.lexsort((service_ids, -admissions))[:20]
    return {
        "scope": {
            "kobis_rollup_rows": len(kobis),
            "proposed_unique_tmdb_links": sum(row["tmdb_match_status"] == "PROPOSED_UNIQUE" for row in kobis),
            "comparable_positive_admissions_rows": len(linked),
            "linkage_status": "PROPOSAL_ONLY_NOT_SERVICE_VERIFIED",
        },
        "kobis_admissions_all": _summary(all_admissions[valid_admissions]),
        "kobis_admissions_comparable": _summary(admissions),
        "metrics": metric_results,
        "group_coverage": _group_counts(group_ids),
        "normalization": {
            "method": "global descending percentile within each source population",
            "merge": "max(tmdb_percentile, kobis_percentile)",
            "note": "Raw TMDB and KOBIS values are never compared directly.",
        },
        "top_kobis_comparable": [
            {
                "title": linked[index]["title"],
                "service_movie_id": linked[index]["service_movie_id"],
                "admissions": linked[index]["admissions"],
                "tmdb_vote_count": linked[index]["vote_count"],
                "tmdb_popularity": linked[index]["popularity"],
            }
            for index in top_kobis
        ],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kobis", required=True, type=Path)
    parser.add_argument("--catalog", required=True, type=Path)
    parser.add_argument("--assignments", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    report = analyze(args.kobis, args.catalog, args.assignments)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True, allow_nan=False) + "\n",
        encoding="utf-8",
    )
    print(args.output.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
