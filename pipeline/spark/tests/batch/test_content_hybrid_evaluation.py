"""Population, leakage and validation selection boundaries for hybrid evaluation."""

from __future__ import annotations

import hashlib
import json
import math

import pytest

from batch.evaluation.content_hybrid import (
    ALS_ONLY,
    CONTENT_ONLY,
    HYBRID_POLICIES,
    EvaluationBundle,
    HybridPolicy,
    RankingSummary,
    ValidationSelectionRecord,
    assert_no_known_holdout_overlap,
    coverage_summary,
    create_validation_selection_record,
    evaluate_top_k,
    final_test_policies_from_selection,
    load_validation_selection,
    rank_candidates,
    save_validation_selection,
    select_validation_policies,
    stage_lineage,
)


def _validation_summaries():
    bundle = EvaluationBundle("validation", "validation", "content-v1", "als-v1", "map-v1")
    return {
        policy: evaluate_top_k({1: [1]}, {1: [1]}, [1], bundle=bundle)
        for policy in (ALS_ONLY, CONTENT_ONLY, *HYBRID_POLICIES)
    }


def _selection_path(tmp_path, content="content-v1", als="als-v1", mapping="map-v1"):
    return (
        tmp_path
        / "content-evaluations"
        / content
        / als
        / mapping
        / "selection"
        / "selection.json"
    )


def test_metric_population_keeps_empty_recommendations_and_excludes_no_positive_users():
    summary = evaluate_top_k({1: [1], 2: []}, {1: [1, 2], 2: [3], 3: []}, [1, 2, 3])
    assert summary.target_users == 3
    assert summary.evaluated_users == 2
    assert summary.users_without_positives == 1
    assert summary.positive_items == 3
    assert summary.recall_at_10 == pytest.approx(0.25)
    assert summary.ndcg_at_10 == pytest.approx(0.5 / (1 + 1 / math.log2(3)))
    assert (
        rank_candidates(
            [
                {"service_movie_id": 2, "score": 1.0},
                {"service_movie_id": 1, "score": 1.0},
            ],
            limit=10,
        )[0]["service_movie_id"]
        == 1
    )


def test_no_leakage_rejects_known_holdout_overlap():
    with pytest.raises(ValueError, match="overlap"):
        assert_no_known_holdout_overlap([(1, 10)], [(1, 10)])
    assert_no_known_holdout_overlap([(1, 10)], [(1, 11)])


def test_validation_selects_fixed_ties():
    summaries = {
        policy: RankingSummary(1, 1, 0, 1, 0.5, 0.5)
        for policy in (ALS_ONLY, CONTENT_ONLY, *HYBRID_POLICIES)
    }
    selection = select_validation_policies(summaries)
    assert selection.overall == ALS_ONLY
    assert selection.hybrid == HybridPolicy(0.25)


def test_coverage_counts_do_not_change_quality_population():
    coverage = coverage_summary([1, 2, 3, 4], {1: [1, 2]}, [2])
    assert coverage == {
        "eligible_items": 4,
        "recommended_items": 2,
        "service_only_recommended_items": 1,
    }


def test_quality_rejects_content_only_and_ambiguous_positives_but_keeps_unsupported_matched():
    with pytest.raises(ValueError, match="content-only"):
        evaluate_top_k(
            {1: []}, {1: [99]}, [1], allowed_positive_items={1, 2}, rejected_positive_items={99}
        )
    summary = evaluate_top_k({1: []}, {1: [2]}, [1], allowed_positive_items={1, 2})
    assert summary.positive_items == 1 and summary.recall_at_10 == 0


def test_selection_record_is_immutable_and_rejects_split_or_bundle_mismatch(tmp_path):
    summaries = _validation_summaries()
    record = create_validation_selection_record(
        summaries,
        content_version="content-v1",
        als_model_version="als-v1",
        mapping_snapshot="map-v1",
        stage="validation",
        split="validation",
    )
    path = _selection_path(tmp_path)
    save_validation_selection(record, str(path))
    assert final_test_policies_from_selection(
        str(path),
        content_version="content-v1",
        als_model_version="als-v1",
        mapping_snapshot="map-v1",
    ) == (ALS_ONLY, CONTENT_ONLY, HybridPolicy(0.25))
    for mismatch in (
        {"content_version": "other"},
        {"als_model_version": "other"},
        {"mapping_snapshot": "other"},
        {"stage": "validation"},
        {"split": "validation"},
    ):
        with pytest.raises(ValueError, match="bundle mismatch|test stage"):
            final_test_policies_from_selection(
                str(path),
                content_version=mismatch.get("content_version", "content-v1"),
                als_model_version=mismatch.get("als_model_version", "als-v1"),
                mapping_snapshot=mismatch.get("mapping_snapshot", "map-v1"),
                stage=mismatch.get("stage", "test"),
                split=mismatch.get("split", "test"),
            )
    with pytest.raises(FileExistsError):
        save_validation_selection(record, str(path))
    assert load_validation_selection(str(path)).hybrid == HybridPolicy(0.25)
    assert not list(tmp_path.glob(".selection.stage-*"))
    path.write_text(path.read_text().replace("HYBRID_A025", "HYBRID_A075"))
    with pytest.raises(ValueError, match="invalid validation selection record"):
        load_validation_selection(str(path))
    with pytest.raises(ValueError, match="validation"):
        create_validation_selection_record(
            summaries,
            content_version="content-v1",
            als_model_version="als-v1",
            mapping_snapshot="map-v1",
            stage="test",
            split="test",
        )
    with pytest.raises(ValueError, match="validation"):
        create_validation_selection_record(
            summaries,
            content_version="content-v1",
            als_model_version="als-v1",
            mapping_snapshot="map-v1",
            stage="validation",
            split="test",
        )


def test_selection_rejects_test_provenance_and_forged_validation_labels(tmp_path):
    test_bundle = EvaluationBundle("test", "test", "content-v1", "als-v1", "map-v1")
    test_summaries = {
        policy: evaluate_top_k({1: [1]}, {1: [1]}, [1], bundle=test_bundle)
        for policy in (ALS_ONLY, CONTENT_ONLY, *HYBRID_POLICIES)
    }
    with pytest.raises(ValueError, match="evaluator-issued"):
        create_validation_selection_record(
            test_summaries,
            content_version="content-v1",
            als_model_version="als-v1",
            mapping_snapshot="map-v1",
            stage="validation",
            split="validation",
        )
    forged = {
        policy: RankingSummary(
            target_users=1,
            evaluated_users=1,
            users_without_positives=0,
            positive_items=1,
            recall_at_10=0.5,
            ndcg_at_10=0.5,
            stage="validation",
            split="validation",
            content_version="content-v1",
            als_model_version="als-v1",
            mapping_snapshot="map-v1",
        )
        for policy in (ALS_ONLY, CONTENT_ONLY, *HYBRID_POLICIES)
    }
    with pytest.raises(ValueError, match="evaluator-issued"):
        create_validation_selection_record(
            forged,
            content_version="content-v1",
            als_model_version="als-v1",
            mapping_snapshot="map-v1",
            stage="validation",
            split="validation",
        )


def test_final_policy_api_reloads_only_a_sealed_selection_path(tmp_path):
    record = create_validation_selection_record(
        _validation_summaries(),
        content_version="content-v1",
        als_model_version="als-v1",
        mapping_snapshot="map-v1",
        stage="validation",
        split="validation",
    )
    path = _selection_path(tmp_path)
    save_validation_selection(record, str(path))
    for raw in (HybridPolicy(0.33), (ALS_ONLY, CONTENT_ONLY, HybridPolicy(0.33)), record):
        with pytest.raises(ValueError, match="sealed selection path"):
            final_test_policies_from_selection(  # type: ignore[arg-type]
                raw,
                content_version="content-v1",
                als_model_version="als-v1",
                mapping_snapshot="map-v1",
            )
    from batch.evaluation.content_hybrid_run import _evaluate_stage

    with pytest.raises(ValueError, match="sealed selection path"):
        _evaluate_stage(
            "test",
            [],
            [],
            [],
            None,
            (ALS_ONLY, CONTENT_ONLY, HybridPolicy(0.33)),
            1,
            content_version="content-v1",
            als_model_version="als-v1",
            mapping_snapshot="map-v1",
        )


def test_selection_save_rejects_a_raw_record_without_validation_selector_provenance(tmp_path):
    raw_record = ValidationSelectionRecord(
        "validation",
        "validation",
        "content-v1",
        "als-v1",
        "map-v1",
        HybridPolicy(0.75),
        HybridPolicy(0.75),
    )
    with pytest.raises(ValueError, match="issued by the validation selector"):
        save_validation_selection(raw_record, str(_selection_path(tmp_path)))


@pytest.mark.parametrize(
    "invalid", [".", "..", "nested/value", r"nested\value", "nul\x00value", "has space"]
)
@pytest.mark.parametrize("field", ["content_version", "als_model_version", "mapping_snapshot"])
def test_selection_boundaries_reject_every_forbidden_version_component(tmp_path, field, invalid):
    versions = {
        "content_version": "content-v1",
        "als_model_version": "als-v1",
        "mapping_snapshot": "map-v1",
    }
    versions[field] = invalid
    with pytest.raises(ValueError, match="path segment"):
        EvaluationBundle("validation", "validation", **versions)
    with pytest.raises(ValueError, match="path segment"):
        ValidationSelectionRecord(
            "validation", "validation", overall=ALS_ONLY, hybrid=HybridPolicy(0.25), **versions
        )

    valid_record = create_validation_selection_record(
        _validation_summaries(),
        content_version="content-v1",
        als_model_version="als-v1",
        mapping_snapshot="map-v1",
        stage="validation",
        split="validation",
    )
    object.__setattr__(valid_record, field, invalid)
    with pytest.raises(ValueError, match="path segment"):
        save_validation_selection(valid_record, str(_selection_path(tmp_path)))

    payload = {
        "stage": "validation",
        "split": "validation",
        **versions,
        "overall": "ALS_ONLY",
        "hybrid": "HYBRID_A025",
    }
    serialized = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
    path = _selection_path(tmp_path)
    path.parent.mkdir(parents=True)
    path.write_bytes(serialized)
    path.with_name("selection.json.sha256").write_text(
        hashlib.sha256(serialized).hexdigest(), encoding="ascii"
    )
    with pytest.raises(ValueError, match="invalid validation selection record"):
        load_validation_selection(str(path))
    with pytest.raises(ValueError, match="invalid validation selection record"):
        final_test_policies_from_selection(
            str(path),
            content_version="content-v1",
            als_model_version="als-v1",
            mapping_snapshot="map-v1",
        )


def test_selection_save_rejects_noncanonical_destination(tmp_path):
    record = create_validation_selection_record(
        _validation_summaries(),
        content_version="content-v1",
        als_model_version="als-v1",
        mapping_snapshot="map-v1",
        stage="validation",
        split="validation",
    )
    with pytest.raises(ValueError, match="canonical tuple layout"):
        save_validation_selection(record, str(tmp_path / "selection" / "selection.json"))


def test_stage_lineage_never_uses_test_for_profiles_or_validation():
    train, validation, test = ["train"], ["validation"], ["test"]
    assert stage_lineage("validation", train, validation, test) == (("train",), ("validation",))
    assert stage_lineage("test", train, validation, test) == (
        ("train", "validation"),
        ("test",),
    )
    with pytest.raises(ValueError, match="serving"):
        stage_lineage("serving", train, validation, test)
