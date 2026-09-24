package com.feelm.backend.domain.recommendation.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Gemini에 전달할 수 있도록 GBT evidence를 허용 표시명과 문장 문법으로 제한한 입력이다. */
public record GbtRecommendationReasonPrompt(
        long itemId, String explanationVersion, List<Evidence> evidence) {

    public GbtRecommendationReasonPrompt {
        evidence = List.copyOf(evidence);
    }

    /** 응답 ID와 문장이 제공한 근거의 제한된 조합인지 다시 검증한다. */
    public boolean supports(GbtRecommendationReasonResult result) {
        if (result == null || result.summary() == null || result.usedEvidenceIds() == null) {
            return false;
        }
        List<String> ids = result.usedEvidenceIds();
        if (ids.isEmpty()
                || ids.size() > evidence.size()
                || new HashSet<>(ids).size() != ids.size()) {
            return false;
        }
        Map<String, Evidence> byId = byId();
        List<Evidence> selected = new ArrayList<>();
        for (String id : ids) {
            Evidence item = byId.get(id);
            if (item == null) {
                return false;
            }
            selected.add(item);
        }
        return buildAllowedSummaries(selected).contains(result.summary());
    }

    public List<String> allowedSummaries(List<String> evidenceIds) {
        Map<String, Evidence> byId = byId();
        List<Evidence> selected = new ArrayList<>();
        for (String id : evidenceIds) {
            Evidence item = byId.get(id);
            if (item == null) {
                return List.of();
            }
            selected.add(item);
        }
        return buildAllowedSummaries(selected);
    }

    public GbtRecommendationReasonResult fallback() {
        Evidence first = evidence.getFirst();
        return new GbtRecommendationReasonResult(
                allowedSummaries(List.of(first.evidenceId())).getFirst(),
                List.of(first.evidenceId()));
    }

    private Map<String, Evidence> byId() {
        Map<String, Evidence> result = new LinkedHashMap<>();
        for (Evidence item : evidence) {
            result.put(item.evidenceId(), item);
        }
        return result;
    }

    private List<String> buildAllowedSummaries(List<Evidence> selected) {
        if (selected.isEmpty()) {
            return List.of();
        }
        if (selected.size() == 1 && selected.getFirst().specificSummary() != null) {
            return List.of(selected.getFirst().specificSummary());
        }
        if (selected.stream().anyMatch(item -> item.specificSummary() != null)) {
            return List.of();
        }
        List<String> labels = selected.stream().map(Evidence::label).toList();
        String joined = String.join("·", labels);
        return List.of(joined + "을 바탕으로 골랐어요.", joined + "이 추천에 긍정적으로 작용했어요.");
    }

    public record Evidence(String evidenceId, String group, String label, String specificSummary) {

        public Evidence(String evidenceId, String group, String label) {
            this(evidenceId, group, label, null);
        }
    }
}
