package com.feelm.backend.domain.recommendation.service;

import com.feelm.backend.domain.recommendation.model.RecommendationReasonPrompt;
import com.feelm.backend.domain.recommendation.repository.RecommendationReasonGenerator;
import com.feelm.backend.domain.recommendation.repository.RecommendationReasonRepository;
import com.feelm.backend.domain.recommendation.service.RecommendationReasonSnapshotWriter.ReasonSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 구조화 근거를 확인된 표시명으로 해석하고 사용자용 문구를 저장한다. */
@Service
@RequiredArgsConstructor
public class RecommendationReasonService {

    static final String NO_EVIDENCE = "새로운 영화를 만나보세요.";

    private final RecommendationReasonRepository repository;
    private final RecommendationReasonGenerator generator;
    private final RecommendationReasonSnapshotWriter writer;
    private final ObjectMapper objectMapper;

    /** 알림 경로의 버퍼 커밋 전에 확인된 근거만으로 첫 노출용 문구를 저장한다. */
    ObjectNode initialReason(JsonNode storedReason) {
        RecommendationReasonPrompt prompt = resolve(0L, storedReason.toString());
        ReasonSnapshot snapshot = render(prompt, null);
        ObjectNode initial = ((ObjectNode) storedReason).deepCopy();
        initial.put("summary", snapshot.summary());
        initial.set("factors", objectMapper.readTree(snapshot.factorsJson()));
        if (prompt.code() != null) {
            initial.put("_reasonPending", true);
        }
        return initial;
    }

    /** LLM 호출은 버퍼 저장 및 스냅샷 갱신 트랜잭션 밖에서 수행된다. */
    public void completeUnexposed(long setId) {
        List<RecommendationReasonRepository.UnresolvedItem> unresolved =
                repository.findUnresolved(setId);
        if (unresolved.isEmpty()) {
            return;
        }
        List<RecommendationReasonPrompt> prompts =
                unresolved.stream().map(item -> resolve(item.id(), item.reason())).toList();
        List<RecommendationReasonPrompt> grounded =
                prompts.stream().filter(prompt -> prompt.code() != null).toList();
        Map<Long, String> variants;
        try {
            variants = grounded.isEmpty() ? Map.of() : generator.selectVariants(grounded);
        } catch (RuntimeException exception) {
            variants = Map.of();
        }
        if (variants == null) {
            variants = Map.of();
        }
        List<ReasonSnapshot> snapshots = new ArrayList<>();
        for (RecommendationReasonPrompt prompt : prompts) {
            snapshots.add(render(prompt, variants.get(prompt.itemId())));
        }
        writer.complete(snapshots);
    }

    private RecommendationReasonPrompt resolve(long itemId, String storedReason) {
        try {
            JsonNode reasons = objectMapper.readTree(storedReason).path("reasons");
            if (!reasons.isArray() || reasons.isEmpty()) {
                return empty(itemId);
            }
            for (JsonNode reason : reasons) {
                RecommendationReasonPrompt prompt = resolveOne(itemId, reason);
                if (prompt.code() != null) {
                    return prompt;
                }
            }
            return empty(itemId);
        } catch (JacksonException exception) {
            return empty(itemId);
        }
    }

    private RecommendationReasonPrompt resolveOne(long itemId, JsonNode reason) {
        String code = reason.path("code").textValue();
        if (code == null) {
            return empty(itemId);
        }
        String source = firstSourceName(reason.path("sourceMovieIds"));
        String genre = firstGenreName(reason.path("matchedGenreCodes"));
        return switch (code) {
            case "SHARED_GENRE" ->
                    source == null || genre == null
                            ? empty(itemId)
                            : new RecommendationReasonPrompt(itemId, code, null, genre, null);
            case "TOP_RATED_SOURCE" ->
                    source == null
                            ? empty(itemId)
                            : new RecommendationReasonPrompt(itemId, code, source, null, null);
            case "DISCOVERY_PERSONAL_SCORE" ->
                    source == null
                            ? empty(itemId)
                            : new RecommendationReasonPrompt(itemId, code, null, genre, null);
            case "DISCOVERY_AGGREGATE_AFFINITY" ->
                    new RecommendationReasonPrompt(
                            itemId, code, null, null, reason.path("directionSource").textValue());
            default -> empty(itemId);
        };
    }

    private RecommendationReasonPrompt empty(long itemId) {
        return new RecommendationReasonPrompt(itemId, null, null, null, null);
    }

    private String firstSourceName(JsonNode ids) {
        if (!ids.isArray()) {
            return null;
        }
        for (JsonNode id : ids) {
            if (id.isIntegralNumber() && id.canConvertToLong() && id.longValue() > 0) {
                String title = repository.findMovieTitle(id.longValue()).orElse(null);
                if (title != null) {
                    return title;
                }
            }
        }
        return null;
    }

    private String firstGenreName(JsonNode codes) {
        if (!codes.isArray()) {
            return null;
        }
        for (JsonNode code : codes) {
            if (code.isTextual()) {
                String name = repository.findGenreName(code.textValue()).orElse(null);
                if (name != null) {
                    return name;
                }
            }
        }
        return null;
    }

    private ReasonSnapshot render(RecommendationReasonPrompt prompt, String variant) {
        String summary = NO_EVIDENCE;
        ArrayNode factors = objectMapper.createArrayNode();
        if (prompt.code() != null) {
            switch (prompt.code()) {
                case "SHARED_GENRE" -> {
                    summary = "좋아하신 영화와 " + prompt.genreName() + " 장르가 같아요.";
                    factors.addObject().put("type", "GENRE").put("label", prompt.genreName());
                }
                case "TOP_RATED_SOURCE" -> {
                    String title = sourceTitleForSummary(prompt.sourceTitle());
                    summary =
                            "HISTORY".equals(variant)
                                    ? "영화 '" + title + "'을 좋아하신 기록을 참고해 골랐어요."
                                    : "좋아하신 영화 '" + title + "'을 바탕으로 골랐어요.";
                }
                case "DISCOVERY_PERSONAL_SCORE" -> {
                    if (prompt.genreName() != null) {
                        summary = "좋아하신 영화와 " + prompt.genreName() + " 장르가 같아요.";
                        factors.addObject().put("type", "GENRE").put("label", prompt.genreName());
                    } else {
                        summary = "취향을 바탕으로 새롭게 고른 영화예요.";
                    }
                }
                case "DISCOVERY_AGGREGATE_AFFINITY" ->
                        summary =
                                "ONBOARDING".equals(prompt.directionSource())
                                        ? "선호 이력을 바탕으로 발견한 영화예요."
                                        : "평가 이력을 바탕으로 발견한 영화예요.";
                default -> {}
            }
        }
        if (summary.length() > 200) {
            summary = NO_EVIDENCE;
            factors = objectMapper.createArrayNode();
        }
        return new ReasonSnapshot(prompt.itemId(), summary, factors.toString());
    }

    private String sourceTitleForSummary(String title) {
        if (title.codePointCount(0, title.length()) <= 60) {
            return title;
        }
        return title.substring(0, title.offsetByCodePoints(0, 59)) + "…";
    }
}
