"""Local Spark execution boundary for the offline Content/ALS comparison.

This intentionally consumes small, explicit rows for local integration tests.
It exercises the same stage, immutable-artifact, candidate, evidence, and
selection boundaries that a distributed reader will later adapt; it is not an
HDFS publication adapter.
"""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from batch.content.artifacts import (
    _identifier,
    content_artifact_uri,
    load_content_artifact,
    write_content_artifact,
)
from batch.content.features import (
    build_content_vector,
    build_vocabulary,
    content_profile,
    user_equal_prior,
    validate_metadata_collection,
)
from batch.evaluation.content_hybrid import (
    ALS_ONLY,
    CONTENT_ONLY,
    HYBRID_POLICIES,
    EvaluationBundle,
    RankingSummary,
    assert_no_known_holdout_overlap,
    coverage_summary,
    create_validation_selection_record,
    evaluate_top_k,
    final_test_policies_from_selection,
    load_validation_selection,
    percentile_scores,
    rank_candidates,
    save_validation_selection,
    score_candidate,
)


@dataclass(frozen=True)
class LocalRunResult:
    selection_path: Path
    validation: Mapping[str, RankingSummary]
    final: Mapping[str, RankingSummary]
    candidates: tuple[Mapping[str, object], ...]
    coverage: Mapping[str, int]
    cache_releases: int


def run_local_content_hybrid(
    spark: object,
    *,
    metadata: Sequence[Mapping[str, object]],
    ratings: Sequence[Mapping[str, object]],
    movies: Sequence[Mapping[str, object]],
    scores: Sequence[Mapping[str, object]],
    output_uri: str | Path,
    content_version: str,
    als_model_version: str,
    mapping_snapshot: str,
    chunk_size: int = 100,
    reuse_existing_artifact: bool = False,
) -> LocalRunResult:
    """Execute prepare -> validation -> final -> coverage through local Spark.

    ``reuse_existing_artifact`` permits only an exact content/ALS/mapping
    lineage retry.  It never treats a different ALS or mapping version as
    compatible with an already published content artifact.
    """
    if chunk_size <= 0:
        raise ValueError("chunk_size must be positive")
    # Reject both stage boundaries before preparing or publishing any artifact.
    # `_evaluate_stage()` repeats its own boundary check so direct callers cannot
    # bypass this run-level guard.
    _assert_no_stage_overlap(ratings, "validation")
    _assert_no_stage_overlap(ratings, "test")
    if not reuse_existing_artifact:
        normalized = validate_metadata_collection(metadata)
        vectors = [build_content_vector(row) for row in normalized]
        vocabulary = build_vocabulary(vectors)
        features = []
        for row, vector in zip(normalized, vectors, strict=True):
            indexes, values, _ = build_content_vector(vector, vocabulary)
            features.append(
                {"tmdb_id": row["tmdb_id"], "indices": list(indexes), "values": list(values)}
            )
        write_content_artifact(
            output_uri,
            version=content_version,
            features=features,
            vocabulary=vocabulary,
            prior=[0.1] * 10,
            als_model_version=als_model_version,
            mapping_snapshot=mapping_snapshot,
        )
    # A public reader is the run boundary: staging validation cannot substitute for this.
    artifact_payload = load_content_artifact(
        content_artifact_uri(str(output_uri), content_version),
        expected_version=content_version,
        als_model_version=als_model_version,
        mapping_snapshot=mapping_snapshot,
    )
    _validate_movie_artifact_join(movies, artifact_payload)
    _validate_evaluation_input_ids(ratings, scores)
    selection_path = _selection_path(
        output_uri, content_version, als_model_version, mapping_snapshot
    )
    sealed_selection = None
    if selection_path.parent.exists():
        sealed_selection = load_validation_selection(str(selection_path))
        if (
            sealed_selection.content_version,
            sealed_selection.als_model_version,
            sealed_selection.mapping_snapshot,
        ) != (content_version, als_model_version, mapping_snapshot):
            raise ValueError("selection record input bundle mismatch")
    ratings_frame = spark.createDataFrame(list(ratings)).cache()
    scores_frame = spark.createDataFrame(list(scores)).cache()
    releases = 0
    try:
        # Materialise the real Spark execution before deterministic local ranking.
        ratings_rows = [row.asDict(recursive=True) for row in ratings_frame.collect()]
        score_rows = [row.asDict(recursive=True) for row in scores_frame.collect()]
        if sealed_selection is None:
            validation = _evaluate_stage(
                "validation",
                ratings_rows,
                movies,
                score_rows,
                artifact_payload,
                HYBRID_POLICIES + (ALS_ONLY, CONTENT_ONLY),
                chunk_size,
                content_version=content_version,
                als_model_version=als_model_version,
                mapping_snapshot=mapping_snapshot,
            )
            record = create_validation_selection_record(
                validation,
                content_version=content_version,
                als_model_version=als_model_version,
                mapping_snapshot=mapping_snapshot,
                stage="validation",
                split="validation",
            )
            save_validation_selection(record, str(selection_path))
        else:
            # A sealed selection is immutable.  An exact retry resumes only
            # final and coverage; it must neither reselect nor overwrite it.
            validation = {}
        final, evidence = _evaluate_stage(
            "test",
            ratings_rows,
            movies,
            score_rows,
            artifact_payload,
            chunk_size=chunk_size,
            evidence=True,
            selection_path=str(selection_path),
            content_version=content_version,
            als_model_version=als_model_version,
            mapping_snapshot=mapping_snapshot,
        )
        coverage_evidence, eligible = _coverage_evidence(
            ratings_rows,
            movies,
            score_rows,
            artifact_payload,
            load_validation_selection(str(selection_path)).hybrid,
            chunk_size,
        )
        evidence.extend(coverage_evidence)
        service_only = [
            int(row["service_movie_id"])
            for row in movies
            if row.get("mapping_status") == "SERVICE_ONLY_CONTENT_BASED"
        ]
        recommendations = defaultdict(list)
        for row in evidence:
            if row["catalog"] == "SERVICE_COVERAGE" and row["rank"] <= 500:
                recommendations[int(row["user_id"])].append(int(row["service_movie_id"]))
        releases = 2
        return LocalRunResult(
            selection_path,
            _named(validation),
            _named(final),
            tuple(evidence),
            coverage_summary(eligible, recommendations, service_only),
            releases,
        )
    finally:
        ratings_frame.unpersist()
        scores_frame.unpersist()


def _evaluate_stage(
    stage,
    ratings,
    movies,
    score_rows,
    artifact_payload,
    policies=None,
    chunk_size=100,
    evidence=False,
    *,
    content_version=None,
    als_model_version=None,
    mapping_snapshot=None,
    selection_path=None,
):
    _validate_evaluation_input_ids(ratings, score_rows)
    if stage == "test":
        if policies is not None or selection_path is None:
            raise ValueError("final MATCHED quality requires a sealed selection path")
        policies = final_test_policies_from_selection(
            selection_path,
            content_version=content_version,
            als_model_version=als_model_version,
            mapping_snapshot=mapping_snapshot,
        )
    elif stage != "validation" or policies is None or selection_path is not None:
        raise ValueError("validation requires its fixed policy set only")
    _assert_no_stage_overlap(ratings, stage)
    known_stages = {"train"} if stage == "validation" else {"train", "validation"}
    holdout_stage = "validation" if stage == "validation" else "test"
    known = defaultdict(set)
    positives = defaultdict(list)
    for row in ratings:
        user, movie = int(row["user_id"]), int(row["movielens_movie_id"])
        if row["stage"] in known_stages:
            known[user].add(movie)
        elif row["stage"] == holdout_stage and float(row["rating"]) >= 4:
            positives[user].append(movie)
    target_users = sorted(positives)
    by_ml = {
        int(row["movielens_movie_id"]): row
        for row in movies
        if row.get("mapping_status") == "MATCHED"
    }
    by_user = defaultdict(dict)
    for row in score_rows:
        by_user[int(row["user_id"])][int(row["service_movie_id"])] = row
    content_by_user = _content_scores_for_stage(ratings, movies, artifact_payload, stage)
    summaries, all_evidence = {}, []
    for policy in policies:
        recommendations = {}
        for offset in range(0, len(target_users), chunk_size):
            for user in target_users[offset : offset + chunk_size]:
                raw = []
                for ml_id, movie in by_ml.items():
                    if ml_id in known[user]:
                        continue
                    value = by_user[user].get(int(movie["service_movie_id"]), {})
                    raw.append(
                        (
                            movie,
                            value.get("als_score"),
                            content_by_user.get(user, {}).get(int(movie["service_movie_id"])),
                        )
                    )
                als_values = percentile_scores([float(x[1]) for x in raw if x[1] is not None])
                content_values = _content_percentiles(raw)
                als_iter = iter(als_values)
                content_iter = iter(content_values) if content_values is not None else None
                candidates = []
                for movie, als, content in raw:
                    scored = score_candidate(
                        next(als_iter) if als is not None else None,
                        next(content_iter)
                        if content is not None and content_iter is not None
                        else None,
                        policy,
                    )
                    if scored.score is not None:
                        candidates.append(
                            {
                                "service_movie_id": int(movie["service_movie_id"]),
                                "score": scored.score,
                                "branch": scored.branch,
                                "effective_als_weight": scored.effective_als_weight,
                            }
                        )
                ranked = rank_candidates(candidates, limit=500)
                recommendations[user] = [
                    int(
                        next(
                            ml
                            for ml, m in by_ml.items()
                            if int(m["service_movie_id"]) == item["service_movie_id"]
                        )
                    )
                    for item in ranked
                ]
                if evidence:
                    all_evidence.extend(
                        {
                            "stage": stage,
                            "catalog": "MATCHED_QUALITY",
                            "policy_id": _policy_id(policy),
                            "user_id": user,
                            "service_movie_id": item["service_movie_id"],
                            "rank": rank,
                            "support_branch": item["branch"],
                            "effective_als_weight": item["effective_als_weight"],
                        }
                        for rank, item in enumerate(ranked, 1)
                    )
        # MATCHED positives remain valid even if one scorer has no support;
        # service-only and ambiguous IDs cannot silently enter quality metrics.
        summaries[policy] = evaluate_top_k(
            recommendations,
            positives,
            target_users,
            allowed_positive_items=set(by_ml),
            bundle=(
                EvaluationBundle(
                    stage=stage,
                    split=stage,
                    content_version=content_version,
                    als_model_version=als_model_version,
                    mapping_snapshot=mapping_snapshot,
                )
                if content_version is not None
                and als_model_version is not None
                and mapping_snapshot is not None
                else None
            ),
        )
    return (summaries, all_evidence) if evidence else summaries


def _assert_no_stage_overlap(ratings: Sequence[Mapping[str, object]], stage: str) -> None:
    """Reject known/holdout pairs before either evaluation stage consumes ratings."""
    _validate_rating_ids(ratings)
    if stage == "validation":
        known_stages, holdout_stage = {"train"}, "validation"
    elif stage == "test":
        known_stages, holdout_stage = {"train", "validation"}, "test"
    else:
        raise ValueError("stage must be validation or test")
    known_pairs = [
        (int(row["user_id"]), int(row["movielens_movie_id"]))
        for row in ratings
        if row["stage"] in known_stages
    ]
    holdout_pairs = [
        (int(row["user_id"]), int(row["movielens_movie_id"]))
        for row in ratings
        if row["stage"] == holdout_stage
    ]
    assert_no_known_holdout_overlap(known_pairs, holdout_pairs)


def _coverage_evidence(ratings, movies, score_rows, artifact_payload, policy, chunk_size):
    """Rank the service catalog separately from MATCHED quality metrics."""
    _validate_evaluation_input_ids(ratings, score_rows)
    known = defaultdict(set)
    target_users = set()
    for row in ratings:
        user, movie = int(row["user_id"]), int(row["movielens_movie_id"])
        if row["stage"] in {"train", "validation"}:
            known[user].add(movie)
        elif row["stage"] == "test" and float(row["rating"]) >= 4:
            target_users.add(user)
    catalog = [
        row
        for row in movies
        if row.get("mapping_status") in {"MATCHED", "SERVICE_ONLY_CONTENT_BASED"}
    ]
    by_user = defaultdict(dict)
    for row in score_rows:
        by_user[int(row["user_id"])][int(row["service_movie_id"])] = row
    content_by_user = _content_scores_for_stage(ratings, movies, artifact_payload, "test")
    evidence, eligible = [], set()
    for offset in range(0, len(target_users), chunk_size):
        for user in sorted(target_users)[offset : offset + chunk_size]:
            raw = []
            for movie in catalog:
                if (
                    movie.get("mapping_status") == "MATCHED"
                    and int(movie["movielens_movie_id"]) in known[user]
                ):
                    continue
                eligible.add(int(movie["service_movie_id"]))
                value = by_user[user].get(int(movie["service_movie_id"]), {})
                raw.append(
                    (
                        movie,
                        value.get("als_score"),
                        content_by_user.get(user, {}).get(int(movie["service_movie_id"])),
                    )
                )
            als_values = iter(percentile_scores([float(x[1]) for x in raw if x[1] is not None]))
            content_percentiles = _content_percentiles(raw)
            content_values = iter(content_percentiles) if content_percentiles is not None else None
            candidates = []
            for movie, als, content in raw:
                scored = score_candidate(
                    next(als_values) if als is not None else None,
                    next(content_values)
                    if content is not None and content_values is not None
                    else None,
                    policy,
                )
                if scored.score is not None:
                    candidates.append(
                        {
                            "service_movie_id": int(movie["service_movie_id"]),
                            "score": scored.score,
                            "branch": scored.branch,
                            "effective_als_weight": scored.effective_als_weight,
                        }
                    )
            for rank, item in enumerate(rank_candidates(candidates, limit=500), 1):
                evidence.append(
                    {
                        "stage": "test",
                        "catalog": "SERVICE_COVERAGE",
                        "policy_id": _policy_id(policy),
                        "user_id": user,
                        "service_movie_id": item["service_movie_id"],
                        "rank": rank,
                        "support_branch": item["branch"],
                        "effective_als_weight": item["effective_als_weight"],
                    }
                )
    return evidence, eligible


def _policy_id(policy):
    if policy == ALS_ONLY:
        return "ALS_ONLY"
    if policy == CONTENT_ONLY:
        return "CONTENT_ONLY"
    return f"HYBRID_A{int(policy.alpha * 100):03d}"


def _named(summaries):
    return {_policy_id(policy): summary for policy, summary in summaries.items()}


def _content_percentiles(raw):
    """Return content percentiles only when the catalog has discrimination."""
    values = [float(content) for _, _, content in raw if content is not None]
    if any(not np.isfinite(value) for value in values):
        raise ValueError("content scores must be finite")
    if len(values) < 2 or len(set(values)) == 1:
        return None
    return percentile_scores(values)


def _selection_path(
    output_uri: str | Path,
    content_version: str,
    als_model_version: str,
    mapping_snapshot: str,
) -> Path:
    """Return the immutable evaluation-run path for one exact input bundle."""
    for label, value in {
        "content_version": content_version,
        "als_model_version": als_model_version,
        "mapping_snapshot": mapping_snapshot,
    }.items():
        _identifier(value, label)
    return (
        Path(output_uri)
        / "content-evaluations"
        / content_version
        / als_model_version
        / mapping_snapshot
        / "selection"
        / "selection.json"
    )


def _validate_movie_artifact_join(movies, artifact_payload) -> None:
    """Reject malformed service/MovieLens/TMDB joins before scoring starts."""
    feature_tmdb_ids = {int(feature["tmdb_id"]) for feature in artifact_payload["features"]}
    service_ids, tmdb_ids, matched_ml_ids = set(), set(), set()
    for movie in movies:
        service_id = _positive_int(movie.get("service_movie_id"), "service_movie_id")
        tmdb_id = _positive_int(movie.get("tmdb_id"), "tmdb_id")
        if service_id in service_ids or tmdb_id in tmdb_ids:
            raise ValueError("movie mapping service_movie_id and tmdb_id must be unique")
        service_ids.add(service_id)
        tmdb_ids.add(tmdb_id)
        status = movie.get("mapping_status")
        if status == "MATCHED":
            movielens_id = _positive_int(
                movie.get("movielens_movie_id"), "MATCHED movielens_movie_id"
            )
            if movielens_id in matched_ml_ids:
                raise ValueError("MATCHED movielens_movie_id must be unique and positive")
            matched_ml_ids.add(movielens_id)
        elif status in {"SERVICE_ONLY_CONTENT_BASED", "TMDB_ID_AMBIGUOUS"}:
            if movie.get("movielens_movie_id") is not None:
                raise ValueError(f"{status} movie must not have movielens_movie_id")
        else:
            raise ValueError("unsupported movie mapping_status")
        if status in {"MATCHED", "SERVICE_ONLY_CONTENT_BASED"} and tmdb_id not in feature_tmdb_ids:
            raise ValueError("candidate movie tmdb_id is missing from content artifact")


def _positive_int(value: object, label: str) -> int:
    """Accept JSON integer IDs only; never coerce bool, float, or string values."""
    if type(value) is not int or value <= 0:
        raise ValueError(f"{label} must be a positive integer")
    return value


def _validate_evaluation_input_ids(ratings, score_rows) -> None:
    """Reject non-JSON integer IDs before Spark or ranking can coerce them."""
    _validate_rating_ids(ratings)
    score_keys = set()
    for row in score_rows:
        user_id = _positive_int(row.get("user_id"), "score user_id")
        service_movie_id = _positive_int(
            row.get("service_movie_id"), "score service_movie_id"
        )
        key = (user_id, service_movie_id)
        if key in score_keys:
            raise ValueError("score rows must not duplicate (user_id, service_movie_id)")
        score_keys.add(key)


def _validate_rating_ids(ratings) -> None:
    for row in ratings:
        _positive_int(row.get("user_id"), "rating user_id")
        _positive_int(row.get("movielens_movie_id"), "rating movielens_movie_id")


def _content_scores_for_stage(ratings, movies, artifact_payload, stage):
    """Calculate content scores from the sealed artifact and stage-known ratings.

    Input score rows provide ALS output only.  Content scores always come from
    ``user_equal_prior`` and ``content_profile`` over the reloaded artifact,
    so an input JSON cannot inject or replace them.
    """
    known_stages = {"train"} if stage == "validation" else {"train", "validation"}
    features = artifact_payload["features"]
    vocabulary = artifact_payload["vocabulary"]
    dimension = len(vocabulary)
    vectors_by_tmdb = {}
    for feature in features:
        vector = np.zeros(dimension, dtype=np.float64)
        vector[np.asarray(feature["indices"], dtype=np.intp)] = np.asarray(
            feature["values"], dtype=np.float64
        )
        vectors_by_tmdb[int(feature["tmdb_id"])] = vector
    matched_by_ml = {
        int(movie["movielens_movie_id"]): movie
        for movie in movies
        if movie.get("mapping_status") == "MATCHED" and movie.get("movielens_movie_id") is not None
    }
    history = defaultdict(list)
    for row in ratings:
        if row["stage"] in known_stages:
            history[int(row["user_id"])].append(row)
    if not history:
        return {}
    histograms = []
    for user in sorted(history):
        histogram = [0.0] * 10
        for row in history[user]:
            histogram[int(float(row["rating"]) * 2) - 1] += 1
        histograms.append(histogram)
    _, prior_midrank = user_equal_prior(histograms)
    results = {}
    for user, rows in history.items():
        supported_vectors, supported = [], []
        ratings_for_profile = []
        for row in rows:
            movie = matched_by_ml.get(int(row["movielens_movie_id"]))
            vector = vectors_by_tmdb.get(int(movie["tmdb_id"])) if movie else None
            is_supported = vector is not None and bool(np.any(vector))
            supported.append(is_supported)
            ratings_for_profile.append(float(row["rating"]))
            if is_supported:
                supported_vectors.append(vector)
        supported_matrix = np.asarray(supported_vectors, dtype=np.float64).reshape(
            len(supported_vectors), dimension
        )
        profile, _, _, status = content_profile(
            supported_matrix,
            ratings_for_profile,
            supported,
            prior_midrank,
        )
        if status != "READY" or profile is None:
            continue
        results[user] = {
            int(movie["service_movie_id"]): float(np.dot(profile, vector))
            for movie in movies
            if (vector := vectors_by_tmdb.get(int(movie["tmdb_id"]))) is not None
            and bool(np.any(vector))
        }
    return results
