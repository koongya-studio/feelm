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
    static final String DISCOVERY_HISTORY = "좋아하신 영화들을 보면 도전해 볼 만해요.";

    /**
     * 취향 발견에서 기여 영화 한 편을 짚어 말할 수 있는 최소 `topContributionCoverage`(S15P21E106-925, 담당자 결정
     * 2026-09-26). coverage는 상위 3편 합의 비중이라, 기준 이상이면 기여가 소수 영화에 모여 있고 그 1위는 양의 기여의 적어도 6분의 1을 차지한다.
     * 기여 영화가 3편 이하이면 coverage는 정의상 1.0이다.
     */
    static final double DISCOVERY_TITLE_MIN_COVERAGE = 0.5;

    /** streaming-contract의 `topContributors` 최대 편수. */
    private static final int DISCOVERY_MAX_CONTRIBUTORS = 3;

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
    ObjectNode initialReason(
            long userId, long candidateMovieId, String type, JsonNode storedReason) {
        ResolvedPrompt prompt = resolve(0L, userId, candidateMovieId, storedReason.toString());
        ReasonSnapshot snapshot = render(type, prompt, null);
        ObjectNode initial = ((ObjectNode) storedReason).deepCopy();
        initial.put("summary", snapshot.summary());
        initial.set("factors", objectMapper.readTree(snapshot.factorsJson()));
        initial.set("usedEvidenceIds", objectMapper.readTree(snapshot.usedEvidenceIdsJson()));
        if (!"POPULARITY".equals(prompt.legacy().code())
                && (prompt.legacy().code() != null || gbtAiEnabled && prompt.gbt() != null)) {
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
                                                resolve(item.id(), null, null, item.reason())))
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

    private ResolvedPrompt resolve(
            long itemId, Long initialUserId, Long candidateMovieId, String storedReason) {
        try {
            JsonNode root = objectMapper.readTree(storedReason);
            if (root.has("recommendationMode")
                    && "POPULARITY".equals(root.get("recommendationMode").textValue())) {
                return new ResolvedPrompt(
                        new RecommendationReasonPrompt(
                                itemId, "POPULARITY", null, null, null, false),
                        null);
            }
            GbtRecommendationReasonPrompt gbt = resolveGbt(itemId, initialUserId, root);
            JsonNode reasons = root.path("reasons");
            if (!reasons.isArray() || reasons.isEmpty()) {
                return new ResolvedPrompt(empty(itemId), gbt);
            }
            for (JsonNode reason : reasons) {
                RecommendationReasonPrompt prompt =
                        resolveOne(itemId, initialUserId, candidateMovieId, reason);
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

    private RecommendationReasonPrompt resolveOne(
            long itemId, Long initialUserId, Long candidateMovieId, JsonNode reason) {
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
                            : new RecommendationReasonPrompt(
                                    itemId, code, null, genre, null, false);
            case "TOP_RATED_SOURCE" ->
                    source == null
                            ? empty(itemId)
                            : new RecommendationReasonPrompt(
                                    itemId, code, source, null, null, false);
            case "DISCOVERY_PERSONAL_SCORE" ->
                    source == null
                            ? empty(itemId)
                            : new RecommendationReasonPrompt(
                                    itemId, code, null, genre, null, false);
            case "DISCOVERY_AGGREGATE_AFFINITY" -> {
                DiscoveryEvidence evidence =
                        discoveryEvidence(itemId, initialUserId, candidateMovieId, reason);
                // 말할 근거가 없으면 해석하지 못한 항목이다. 뒤의 reason을 계속 찾는다(API-045 v6 규칙)
                if (evidence == DiscoveryEvidence.NONE) {
                    yield empty(itemId);
                }
                yield new RecommendationReasonPrompt(
                        itemId,
                        code,
                        evidence.title(),
                        null,
                        reason.path("directionSource").textValue(),
                        evidence.historyOnly());
            }
            default -> empty(itemId);
        };
    }

    private RecommendationReasonPrompt empty(long itemId) {
        return new RecommendationReasonPrompt(itemId, null, null, null, null, false);
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

    /**
     * 취향 발견 그룹을 고르는 데 기여한 영화 중 사용자에게 보여 줄 수 있는 근거(S15P21E106-925).
     *
     * <p>`POSITIVE_ALIGNMENT`(높게 평가했고 후보 그룹과 결이 같은 영화)만 쓴다. `NEGATIVE_CONTRAST`는 낮게 평가한 영화의 반대 방향이라
     * 사실이어도 이유로 보여 주지 않는다. 기여 영화는 그 사용자의 실제 4점 이상 평가인지와 제목을 다시 확인하고, 추천 영화 자신은 쓰지 않는다. 문구가 낯선 쪽에
     * "도전해 볼 만하다"고 말하므로 그룹 안에서 본 영화가 두 편 이하(Consumer 선택 규칙)일 때만 쓴다.
     *
     * <p>영화명은 기여 1위가 검증된 좋아한 영화이고 `topContributionCoverage`가 {@link #DISCOVERY_TITLE_MIN_COVERAGE}
     * 이상일 때만 쓴다. coverage가 낮으면 그룹 적합도가 여러 영화에 고르게 퍼진 것이라, 영화 한 편을 짚지 않고 전체 평가 이력 문구를
     * 쓴다(streaming-contract 그룹 근거 규칙). 이때도 확인된 좋아한 영화가 하나는 있어야 "좋아하신 영화들"이 사실이 된다.
     */
    private DiscoveryEvidence discoveryEvidence(
            long itemId, Long initialUserId, Long candidateMovieId, JsonNode reason) {
        JsonNode viewed = reason.path("viewedCountInGroup");
        JsonNode coverage = reason.path("topContributionCoverage");
        JsonNode contributors = reason.path("topContributors");
        if (!viewed.isIntegralNumber()
                || !viewed.canConvertToLong()
                || viewed.longValue() < 0
                || viewed.longValue() > 2
                || !coverage.isNumber()
                || !(coverage.doubleValue() >= 0.0 && coverage.doubleValue() <= 1.0)
                || !contributors.isArray()) {
            return DiscoveryEvidence.NONE;
        }
        // 영화명은 기여 1위가 검증된 좋아한 영화일 때만 쓴다(S15P21E106-925 리뷰). 상위 3편에는 싫어한
        // 영화의 반대 방향(NEGATIVE_CONTRAST)도 들어가므로, 1위를 건너뛰고 뒤 영화를 짚으면 coverage가
        // 그 영화의 비중을 말해 주지 않는다. 1위라면 비중이 적어도 coverage의 3분의 1이다.
        boolean first = true;
        boolean likedFound = false;
        int seen = 0;
        for (JsonNode contributor : contributors) {
            // Reader가 거부하지만 저장된 과거 payload도 계약(최대 3편) 밖 영화를 조회하지 않는다
            if (++seen > DISCOVERY_MAX_CONTRIBUTORS) {
                break;
            }
            boolean isFirst = first;
            first = false;
            JsonNode movieId = contributor.path("movieId");
            if (!"POSITIVE_ALIGNMENT".equals(contributor.path("relation").textValue())
                    || !movieId.isIntegralNumber()
                    || !movieId.canConvertToLong()
                    || movieId.longValue() <= 0) {
                continue;
            }
            java.util.Optional<String> title =
                    initialUserId != null
                            ? candidateMovieId != null && candidateMovieId > 0
                                    ? repository.findPositiveRatedMovieTitleBeforeInsert(
                                            initialUserId, candidateMovieId, movieId.longValue())
                                    : java.util.Optional.empty()
                            : repository.findOwnerPositiveRatedMovieTitle(
                                    itemId, movieId.longValue());
            if (title.isEmpty()) {
                continue;
            }
            if (isFirst && coverage.doubleValue() >= DISCOVERY_TITLE_MIN_COVERAGE) {
                return new DiscoveryEvidence(title.get(), false);
            }
            likedFound = true;
            break;
        }
        return likedFound ? new DiscoveryEvidence(null, true) : DiscoveryEvidence.NONE;
    }

    private record DiscoveryEvidence(String title, boolean historyOnly) {
        static final DiscoveryEvidence NONE = new DiscoveryEvidence(null, false);
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
                case "POPULARITY" -> summary = "아직 취향 정보가 없어, 인기도를 기준으로 영화를 골랐어요.";
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
                case "DISCOVERY_AGGREGATE_AFFINITY" -> {
                    // 그룹 단위로만 말한다. 근거는 "기여 영화의 방향에 있는 그룹에서 골랐다"이지 후보가
                    // 그 영화와 비슷하다는 것이 아니다(S15P21E106-925)
                    if (legacy.historyOnly()) {
                        summary = DISCOVERY_HISTORY;
                    } else if (legacy.sourceTitle() == null) {
                        summary = DISCOVERY_NO_EVIDENCE;
                    } else {
                        String title = sourceTitleForSummary(legacy.sourceTitle());
                        summary = "'" + title + "'" + objectParticle(title) + " 좋아하셨다면 도전해 볼 만해요.";
                    }
                }
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

    /**
     * 제목 뒤의 목적격 조사(을/를). 따옴표 뒤에 붙지만 읽을 때는 제목 끝소리를 따른다(S15P21E106-925).
     *
     * <p>끝의 괄호 · 줄임표 같은 기호는 건너뛰고 그 앞 글자로 판단한다. 한글은 받침, 숫자는 읽는 소리로 정한다. 영문은 발음을 확정할 수 없어 흔한 규칙(l · m
     * · n · k · p로 끝나면 받침)으로 고르고, 판단할 수 없는 글자는 `을(를)`로 둔다.
     */
    static String objectParticle(String title) {
        int index = title.length();
        while (index > 0) {
            int codePoint = title.codePointBefore(index);
            if (Character.isLetterOrDigit(codePoint)) {
                break;
            }
            index -= Character.charCount(codePoint);
        }
        if (index == 0) {
            return "을(를)";
        }
        int last = title.codePointBefore(index);
        if (last >= 0xAC00 && last <= 0xD7A3) {
            return (last - 0xAC00) % 28 == 0 ? "를" : "을";
        }
        if (last >= '0' && last <= '9') {
            // 영 일 이 삼 사 오 육 칠 팔 구
            return "013678".indexOf(last) >= 0 ? "을" : "를";
        }
        int letter = Character.toLowerCase(last);
        if (letter >= 'a' && letter <= 'z') {
            return "lmnkp".indexOf(letter) >= 0 ? "을" : "를";
        }
        return "을(를)";
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
