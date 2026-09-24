package com.feelm.backend.domain.recommendation.service;

import com.feelm.backend.domain.recommendation.model.GbtRecommendationReasonPrompt;
import com.feelm.backend.domain.recommendation.model.GbtRecommendationReasonPrompt.Evidence;
import com.feelm.backend.domain.recommendation.model.GbtRecommendationReasonResult;
import com.feelm.backend.domain.recommendation.model.RecommendationReasonPrompt;
import com.feelm.backend.domain.recommendation.repository.RecommendationReasonGenerator;
import com.feelm.backend.domain.recommendation.repository.RecommendationReasonRepository;
import com.feelm.backend.domain.recommendation.service.RecommendationReasonSnapshotWriter.ReasonSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 구조화 근거를 확인된 표시명으로 해석하고 사용자용 문구를 저장한다. */
@Service
public class RecommendationReasonService {

    static final String TASTE_NO_EVIDENCE = "취향 데이터를 바탕으로 영화를 골라봤어요.";
    static final String DISCOVERY_NO_EVIDENCE = "새로운 영화를 만나보세요.";
    private static final Pattern GBT_GENRE_CODE = Pattern.compile("TMDB_[1-9][0-9]*");

    private final RecommendationReasonRepository repository;
    private final RecommendationReasonGenerator generator;
    private final RecommendationReasonSnapshotWriter writer;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final boolean gbtAiEnabled;

    public RecommendationReasonService(
            RecommendationReasonRepository repository,
            RecommendationReasonGenerator generator,
            RecommendationReasonSnapshotWriter writer,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${feelm.recommendation.reason.gbt-ai.enabled:false}") boolean gbtAiEnabled) {
        this.repository = repository;
        this.generator = generator;
        this.writer = writer;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.gbtAiEnabled = gbtAiEnabled;
    }

    /** 알림 경로의 버퍼 커밋 전에 확인된 근거만으로 첫 노출용 문구를 저장한다. */
    ObjectNode initialReason(long userId, String type, JsonNode storedReason) {
        ResolvedPrompt prompt = resolve(0L, userId, storedReason.toString());
        ReasonSnapshot snapshot = render(type, prompt, null);
        ObjectNode initial = ((ObjectNode) storedReason).deepCopy();
        initial.put("summary", snapshot.summary());
        initial.set("factors", objectMapper.readTree(snapshot.factorsJson()));
        initial.set("usedEvidenceIds", objectMapper.readTree(snapshot.usedEvidenceIdsJson()));
        if (prompt.legacy().code() != null || gbtAiEnabled && prompt.gbt() != null) {
            initial.put("_reasonPending", true);
        }
        return initial;
    }

    /** LLM 호출은 버퍼 저장 트랜잭션 밖에서 수행되고, 문구 확정은 항목마다 별도 커밋한다(S15P21E106-804). 트랜잭션 안에서 호출하지 않는다. */
    public void completeUnexposed(long setId) {
        List<RecommendationReasonRepository.UnresolvedItem> unresolved =
                repository.findUnresolved(setId);
        if (unresolved.isEmpty()) {
            return;
        }
        List<ResolvedReason> resolved =
                unresolved.stream()
                        .map(
                                item ->
                                        new ResolvedReason(
                                                item.type(),
                                                resolve(item.id(), null, item.reason())))
                        .toList();
        List<GbtRecommendationReasonPrompt> gbtPrompts =
                gbtAiEnabled
                        ? resolved.stream()
                                .map(reason -> reason.prompt().gbt())
                                .filter(java.util.Objects::nonNull)
                                .toList()
                        : List.of();
        Map<Long, GbtRecommendationReasonResult> generated;
        try {
            generated = gbtPrompts.isEmpty() ? Map.of() : generator.generateGbtReasons(gbtPrompts);
        } catch (RuntimeException exception) {
            generated = Map.of();
        }
        if (generated == null) {
            generated = Map.of();
        }
        List<ReasonSnapshot> snapshots = new ArrayList<>();
        for (ResolvedReason reason : resolved) {
            ResolvedPrompt prompt = reason.prompt();
            snapshots.add(render(reason.type(), prompt, generated.get(prompt.legacy().itemId())));
        }
        writer.complete(snapshots);
    }

    private ResolvedPrompt resolve(long itemId, Long initialUserId, String storedReason) {
        try {
            JsonNode root = objectMapper.readTree(storedReason);
            GbtRecommendationReasonPrompt gbt = resolveGbt(itemId, initialUserId, root);
            JsonNode reasons = root.path("reasons");
            if (!reasons.isArray() || reasons.isEmpty()) {
                return new ResolvedPrompt(empty(itemId), gbt);
            }
            for (JsonNode reason : reasons) {
                RecommendationReasonPrompt prompt = resolveOne(itemId, reason);
                if (prompt.code() != null) {
                    return new ResolvedPrompt(prompt, gbt);
                }
            }
            return new ResolvedPrompt(empty(itemId), gbt);
        } catch (JacksonException exception) {
            return new ResolvedPrompt(empty(itemId), null);
        }
    }

    private GbtRecommendationReasonPrompt resolveGbt(
            long itemId, Long initialUserId, JsonNode root) {
        if (!root.has("modelKind")
                || !root.has("modelVersion")
                || !root.has("rankingVersion")
                || !root.has("explanationVersion")
                || !root.has("evidence")) {
            return null;
        }
        JsonNode snapshot = root.path("evidence");
        String explanationVersion = root.path("explanationVersion").textValue();
        String evidenceSchemaVersion = snapshot.path("evidenceSchemaVersion").textValue();
        boolean v1 =
                "gbt-feature-attribution-v1".equals(explanationVersion)
                        && "gbt-evidence-v1".equals(evidenceSchemaVersion);
        boolean v2 =
                "gbt-feature-attribution-v2".equals(explanationVersion)
                        && "gbt-evidence-v2".equals(evidenceSchemaVersion);
        if (!"GBT".equals(root.path("modelKind").textValue())
                || !v1 && !v2
                || !root.path("modelVersion").equals(snapshot.path("modelVersion"))
                || !root.path("rankingVersion").equals(snapshot.path("rankingVersion"))
                || !root.path("explanationVersion").equals(snapshot.path("explanationVersion"))) {
            return null;
        }
        JsonNode snapshotUserId = snapshot.path("userId");
        if (v2
                && (snapshotUserId == null
                        || !snapshotUserId.isIntegralNumber()
                        || !snapshotUserId.canConvertToLong()
                        || snapshotUserId.longValue() <= 0
                        || initialUserId != null
                                && snapshotUserId.longValue() != initialUserId.longValue())) {
            return null;
        }
        JsonNode values = snapshot.path("item").path("evidence");
        if (!values.isArray() || values.isEmpty() || values.size() > 3) {
            return null;
        }
        Map<String, String> labels = evidenceLabels();
        List<Evidence> evidence = new ArrayList<>();
        for (JsonNode value : values) {
            String id = value.path("evidenceId").textValue();
            String group = value.path("group").textValue();
            String label = labels.get(group);
            if (id == null
                    || label == null
                    || !"POSITIVE".equals(value.path("direction").textValue())) {
                return null;
            }
            String specificSummary =
                    v2 ? specificSummary(itemId, initialUserId, snapshot, group, value) : null;
            evidence.add(new Evidence(id, group, label, specificSummary));
        }
        evidence.sort(
                Comparator.comparing(
                        item -> item.specificSummary() == null, Comparator.naturalOrder()));
        return new GbtRecommendationReasonPrompt(itemId, explanationVersion, evidence);
    }

    private String specificSummary(
            long itemId, Long initialUserId, JsonNode snapshot, String group, JsonNode value) {
        if ("GENRE_PATTERN".equals(group) && value.path("genreCode").isTextual()) {
            String code = value.path("genreCode").textValue();
            if (GBT_GENRE_CODE.matcher(code).matches()) {
                return repository
                        .findGenreName(code)
                        .map(name -> "평소 " + name + " 장르를 좋아해서 골랐어요.")
                        .orElse(null);
            }
        }
        if ("CONTENT_RELATION".equals(group)
                && value.path("sourceMovieId").isIntegralNumber()
                && value.path("sourceMovieId").canConvertToLong()
                && value.path("sourceMovieId").longValue() > 0
                && snapshot.path("userId").isIntegralNumber()
                && snapshot.path("userId").canConvertToLong()
                && snapshot.path("userId").longValue() > 0) {
            long userId = snapshot.path("userId").longValue();
            long sourceMovieId = value.path("sourceMovieId").longValue();
            long candidateMovieId = snapshot.path("item").path("movieId").longValue();
            var verifiedTitle =
                    initialUserId == null
                            ? repository.findPositiveRatedMovieTitle(itemId, userId, sourceMovieId)
                            : candidateMovieId > 0
                                    ? repository.findPositiveRatedMovieTitleBeforeInsert(
                                            userId, candidateMovieId, sourceMovieId)
                                    : java.util.Optional.<String>empty();
            return verifiedTitle
                    .map(this::sourceTitleForSummary)
                    .map(sourceTitle -> "\"" + sourceTitle + "\"을 재미있게 봐서 골랐어요.")
                    .orElse(null);
        }
        return null;
    }

    private Map<String, String> evidenceLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("GENRE_PATTERN", "장르 취향 패턴");
        labels.put("RATING_PATTERN", "평가 성향");
        labels.put("CONTENT_RELATION", "선호 콘텐츠와의 연관성");
        labels.put("POPULARITY_SIGNAL", "인기도 경향");
        labels.put("AUDIENCE_RATING_SIGNAL", "관객 평가 경향");
        return labels;
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

    private ReasonSnapshot render(
            String type, ResolvedPrompt prompt, GbtRecommendationReasonResult generated) {
        if (gbtAiEnabled && prompt.gbt() != null) {
            GbtRecommendationReasonResult selected = generated;
            String outcome = "success";
            if (!prompt.gbt().supports(selected)) {
                selected = prompt.gbt().fallback();
                outcome = "fallback";
            }
            meterRegistry
                    .counter("feelm.recommendation.reason.gbt.ai", "outcome", outcome)
                    .increment();
            return new ReasonSnapshot(
                    prompt.legacy().itemId(),
                    selected.summary(),
                    "[]",
                    objectMapper.valueToTree(selected.usedEvidenceIds()).toString());
        }
        RecommendationReasonPrompt legacy = prompt.legacy();
        String summary = fallbackSummary(type);
        ArrayNode factors = objectMapper.createArrayNode();
        if (legacy.code() != null) {
            switch (legacy.code()) {
                case "SHARED_GENRE" -> {
                    summary = "좋아하신 영화와 " + legacy.genreName() + " 장르가 같아요.";
                    factors.addObject().put("type", "GENRE").put("label", legacy.genreName());
                }
                case "TOP_RATED_SOURCE" -> {
                    String title = sourceTitleForSummary(legacy.sourceTitle());
                    summary = "좋아하신 영화 '" + title + "'을 바탕으로 골랐어요.";
                }
                case "DISCOVERY_PERSONAL_SCORE" -> {
                    if (legacy.genreName() != null) {
                        summary = "좋아하신 영화와 " + legacy.genreName() + " 장르가 같아요.";
                        factors.addObject().put("type", "GENRE").put("label", legacy.genreName());
                    } else {
                        summary = DISCOVERY_NO_EVIDENCE;
                    }
                }
                case "DISCOVERY_AGGREGATE_AFFINITY" -> summary = DISCOVERY_NO_EVIDENCE;
                default -> {}
            }
        }
        if (summary.length() > 200) {
            summary = fallbackSummary(type);
            factors = objectMapper.createArrayNode();
        }
        return new ReasonSnapshot(legacy.itemId(), summary, factors.toString(), "[]");
    }

    static String fallbackSummary(String type) {
        return "TASTE".equals(type) ? TASTE_NO_EVIDENCE : DISCOVERY_NO_EVIDENCE;
    }

    private String sourceTitleForSummary(String title) {
        if (title.codePointCount(0, title.length()) <= 60) {
            return title;
        }
        return title.substring(0, title.offsetByCodePoints(0, 59)) + "…";
    }

    private record ResolvedPrompt(
            RecommendationReasonPrompt legacy, GbtRecommendationReasonPrompt gbt) {}

    private record ResolvedReason(String type, ResolvedPrompt prompt) {}
}
