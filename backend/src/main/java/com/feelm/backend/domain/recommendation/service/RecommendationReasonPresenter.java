package com.feelm.backend.domain.recommendation.service;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 저장된 원본 근거는 감추고 확정된 사용자용 이유만 API에 제공한다. */
@Component
public class RecommendationReasonPresenter {

    private final ObjectMapper objectMapper;

    public RecommendationReasonPresenter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode present(String storedReason) {
        JsonNode stored;
        try {
            stored = objectMapper.readTree(storedReason);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored recommendation reason is invalid", exception);
        }
        if (stored == null || !stored.isObject()) {
            throw new IllegalStateException("Stored recommendation reason is invalid");
        }
        ObjectNode response = objectMapper.createObjectNode();
        JsonNode summaryNode = stored.path("summary");
        String summary = summaryNode.isTextual() ? summaryNode.textValue() : null;
        boolean hasSummary = summary != null && !summary.isBlank();
        response.put("summary", hasSummary ? summary : RecommendationReasonService.NO_EVIDENCE);
        JsonNode factors = stored.path("factors");
        response.set(
                "factors",
                hasSummary && factors.isArray()
                        ? factors.deepCopy()
                        : objectMapper.createArrayNode());
        return response;
    }
}
