package com.feelm.backend.infrastructure.redis;

import com.feelm.backend.domain.recommendation.model.RecommendationCandidate;
import com.feelm.backend.domain.recommendation.model.RecommendationCandidateBatch;
import com.feelm.backend.domain.recommendation.repository.RecommendationCandidateReader;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.node.ObjectNode;

/** Redis의 개인 추천 후보 JSON을 읽고 전환 기간의 Candidate v5/v6 계약을 검증한다. */
@Component
public class RedisRecommendationCandidateReader implements RecommendationCandidateReader {

    private static final int MAX_MODEL_VERSION_LENGTH = 50;
    private static final int MAX_GENERATION_ID_LENGTH = 100;
    private static final int READ_ATTEMPTS = 3;
    private static final long RETRY_DELAY_NANOS = 20_000_000L;
    private static final int MAX_EVIDENCE_BYTES = 2 * 1024 * 1024;
    private static final long CURRENT_CANDIDATE_VERSION = 6;
    private static final long PREVIOUS_CANDIDATE_VERSION = 5;
    private static final String SHARED_GENRE_REASON = "SHARED_GENRE";
    private static final String TOP_RATED_SOURCE_REASON = "TOP_RATED_SOURCE";
    private static final String DISCOVERY_AGGREGATE_REASON = "DISCOVERY_AGGREGATE_AFFINITY";
    private static final String DISCOVERY_PERSONAL_REASON = "DISCOVERY_PERSONAL_SCORE";
    private static final String EVIDENCE_SCHEMA_V1 = "gbt-evidence-v1";
    private static final String EVIDENCE_SCHEMA_V2 = "gbt-evidence-v2";
    private static final String EXPLANATION_V1 = "gbt-feature-attribution-v1";
    private static final String EXPLANATION_V2 = "gbt-feature-attribution-v2";
    private static final String FEATURE_VERSION = "v26-max70-kobis-flag-71-v1";
    private static final String RANKING_VERSION = "gbt-personal-raw-score-v1";
    private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern GENRE_CODE = Pattern.compile("TMDB_[1-9][0-9]*");
    private static final Set<String> EVIDENCE_GROUPS =
            Set.of(
                    "GENRE_PATTERN",
                    "RATING_PATTERN",
                    "CONTENT_RELATION",
                    "POPULARITY_SIGNAL",
                    "AUDIENCE_RATING_SIGNAL");
    private static final Set<String> EVIDENCE_ROOT_FIELDS =
            Set.of(
                    "evidenceSchemaVersion",
                    "explanationVersion",
                    "userId",
                    "generationId",
                    "modelVersion",
                    "servingRevision",
                    "featureVersion",
                    "rankingVersion",
                    "inputVersion",
                    "candidateVersion",
                    "items");
    private static final Set<String> EVIDENCE_ITEM_FIELDS =
            Set.of("movieId", "score", "coverage", "evidence");
    private static final Set<String> EVIDENCE_FIELDS =
            Set.of("evidenceId", "group", "direction", "contribution", "coverage");
    private static final Set<String> EVIDENCE_V2_FIELDS =
            Set.of(
                    "evidenceId",
                    "group",
                    "direction",
                    "contribution",
                    "coverage",
                    "genreCode",
                    "sourceMovieId");
    private static final Set<String> DIRECTION_SOURCES = Set.of("RATING", "ONBOARDING");
    private static final Set<String> CONTRIBUTOR_RELATIONS =
            Set.of("POSITIVE_ALIGNMENT", "NEGATIVE_CONTRAST");

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final int maxTasteCandidates;
    private final int maxDiscoveryCandidates;
    private final boolean acceptV5;

    public RedisRecommendationCandidateReader(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${feelm.recommendation.candidates.max-taste:500}") int maxTasteCandidates,
            @Value("${feelm.recommendation.candidates.max-discovery:100}")
                    int maxDiscoveryCandidates,
            @Value("${feelm.recommendation.candidates.accept-v5:true}") boolean acceptV5) {
        if (maxTasteCandidates < 1 || maxDiscoveryCandidates < 1) {
            throw new IllegalArgumentException("Candidate limits must be positive");
        }
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.maxTasteCandidates = maxTasteCandidates;
        this.maxDiscoveryCandidates = maxDiscoveryCandidates;
        this.acceptV5 = acceptV5;
    }

    @Override
    public RecommendationCandidateBatch read(long userId) {
        BusinessException failure = null;
        for (int attempt = 0; attempt < READ_ATTEMPTS; attempt++) {
            try {
                return readOnce(userId);
            } catch (BusinessException exception) {
                failure = exception;
                if (attempt + 1 < READ_ATTEMPTS && !Thread.currentThread().isInterrupted()) {
                    LockSupport.parkNanos(RETRY_DELAY_NANOS);
                }
            }
        }
        throw failure;
    }

    private RecommendationCandidateBatch readOnce(long userId) {
        String stored;
        try {
            stored = redisTemplate.opsForValue().get(key(userId));
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        if (stored == null || stored.isBlank()) {
            throw unavailable(null);
        }

        try {
            JsonNode candidate = objectMapper.readTree(stored);
            StoredCandidateBatch storedBatch =
                    objectMapper.readValue(stored, StoredCandidateBatch.class);
            validate(storedBatch);
            String storedState = readState(userId);
            JsonNode currentModel = readCurrentModel();
            Map<Long, JsonNode> evidence = Map.of();
            if (currentModel != null && currentModel.has("modelKind")
                    || UserRecommendationStateContract.hasGbtMetadata(candidate)
                    || storedState != null && !storedState.isBlank()) {
                var state =
                        validateGbtState(userId, storedBatch, candidate, storedState, currentModel);
                evidence = readGbtEvidence(userId, storedBatch, state);
            } else {
                validateLegacyVector(userId, storedBatch);
            }
            return toBatch(storedBatch, evidence);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw unavailable(exception);
        }
    }

    private JsonNode readCurrentModel() throws JacksonException {
        String stored;
        try {
            stored =
                    redisTemplate
                            .opsForValue()
                            .get(UserRecommendationStateContract.CURRENT_MODEL_KEY);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        if (stored == null || stored.isBlank()) {
            return null;
        }
        JsonNode currentModel = objectMapper.readTree(stored);
        if (!currentModel.isObject()) {
            throw new IllegalArgumentException("Invalid current serving model");
        }
        return currentModel;
    }

    private String readState(long userId) {
        try {
            return redisTemplate.opsForValue().get(UserRecommendationStateContract.key(userId));
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private UserRecommendationStateContract.Snapshot validateGbtState(
            long userId,
            StoredCandidateBatch batch,
            JsonNode candidate,
            String storedState,
            JsonNode currentModel)
            throws JacksonException {
        if (batch.candidateVersion() != CURRENT_CANDIDATE_VERSION) {
            throw new IllegalArgumentException("GBT candidates require Candidate v6");
        }
        if (storedState == null || storedState.isBlank()) {
            throw new IllegalArgumentException("Recommendation state is missing");
        }
        var state =
                UserRecommendationStateContract.parse(objectMapper.readTree(storedState), userId);
        UserRecommendationStateContract.verifyCandidate(candidate, state);
        UserRecommendationStateContract.verifyCurrentModel(currentModel, state);
        return state;
    }

    /** evidence 장애나 버전 불일치는 추천 후보 자체를 사용할 수 없게 만들지 않고 설명만 fallback한다. */
    private Map<Long, JsonNode> readGbtEvidence(
            long userId,
            StoredCandidateBatch candidates,
            UserRecommendationStateContract.Snapshot state) {
        String stored;
        try {
            stored = redisTemplate.opsForValue().get(evidenceKey(userId, state.generationId()));
            if (stored == null
                    || stored.isBlank()
                    || stored.getBytes(StandardCharsets.UTF_8).length > MAX_EVIDENCE_BYTES) {
                return Map.of();
            }
            return validateGbtEvidence(objectMapper.readTree(stored), userId, candidates, state);
        } catch (RuntimeException exception) {
            return Map.of();
        }
    }

    private Map<Long, JsonNode> validateGbtEvidence(
            JsonNode root,
            long userId,
            StoredCandidateBatch candidates,
            UserRecommendationStateContract.Snapshot state) {
        String evidenceSchemaVersion = root.path("evidenceSchemaVersion").textValue();
        String explanationVersion = root.path("explanationVersion").textValue();
        boolean v1 =
                EVIDENCE_SCHEMA_V1.equals(evidenceSchemaVersion)
                        && EXPLANATION_V1.equals(explanationVersion);
        boolean v2 =
                EVIDENCE_SCHEMA_V2.equals(evidenceSchemaVersion)
                        && EXPLANATION_V2.equals(explanationVersion);
        if (!hasExactFields(root, EVIDENCE_ROOT_FIELDS)
                || !v1 && !v2
                || !positiveLong(root.path("userId"))
                || root.path("userId").longValue() != userId
                || !state.generationId().equals(root.path("generationId").textValue())
                || !state.modelVersion().equals(root.path("modelVersion").textValue())
                || !state.servingRevision().equals(root.path("servingRevision").textValue())
                || !FEATURE_VERSION.equals(root.path("featureVersion").textValue())
                || !RANKING_VERSION.equals(root.path("rankingVersion").textValue())
                || !state.inputHash().equals(root.path("inputVersion").textValue())
                || !positiveLong(root.path("candidateVersion"))
                || root.path("candidateVersion").longValue() != CURRENT_CANDIDATE_VERSION
                || !root.path("items").isArray()
                || root.path("items").size() != candidates.taste().size()) {
            throw new IllegalArgumentException("GBT evidence metadata differs");
        }

        Map<Long, JsonNode> snapshots = new HashMap<>();
        for (int index = 0; index < candidates.taste().size(); index++) {
            StoredTasteCandidate candidate = candidates.taste().get(index);
            JsonNode item = root.path("items").get(index);
            validateEvidenceItem(item, candidate, v2, state);
            ObjectNode snapshot = objectMapper.createObjectNode();
            snapshot.put("evidenceSchemaVersion", evidenceSchemaVersion);
            snapshot.put("explanationVersion", explanationVersion);
            snapshot.put("userId", userId);
            snapshot.put("generationId", state.generationId());
            snapshot.put("modelVersion", state.modelVersion());
            snapshot.put("servingRevision", state.servingRevision());
            snapshot.put("featureVersion", FEATURE_VERSION);
            snapshot.put("rankingVersion", RANKING_VERSION);
            snapshot.put("inputVersion", state.inputHash());
            snapshot.put("candidateVersion", CURRENT_CANDIDATE_VERSION);
            snapshot.set("item", item.deepCopy());
            snapshots.put(candidate.movieId(), snapshot);
        }
        return Map.copyOf(snapshots);
    }

    private void validateEvidenceItem(
            JsonNode item,
            StoredTasteCandidate candidate,
            boolean v2,
            UserRecommendationStateContract.Snapshot state) {
        if (!hasExactFields(item, EVIDENCE_ITEM_FIELDS)
                || !positiveLong(item.path("movieId"))
                || item.path("movieId").longValue() != candidate.movieId()
                || !item.path("score").isNumber()
                || item.path("score").decimalValue().compareTo(candidate.score()) != 0
                || !decimalBetweenZeroAndOne(item.path("coverage"))
                || !item.path("evidence").isArray()
                || item.path("evidence").size() > 3) {
            throw new IllegalArgumentException("Invalid GBT evidence item");
        }
        Set<String> ids = new HashSet<>();
        Set<String> groups = new HashSet<>();
        BigDecimal coverage = BigDecimal.ZERO;
        BigDecimal previousContribution = null;
        String previousGroup = null;
        for (JsonNode evidence : item.path("evidence")) {
            String id = evidence.path("evidenceId").textValue();
            String group = evidence.path("group").textValue();
            Set<String> fields = new HashSet<>();
            evidence.properties().forEach(property -> fields.add(property.getKey()));
            boolean validFields =
                    v2
                            ? EVIDENCE_V2_FIELDS.containsAll(fields)
                                    && fields.containsAll(EVIDENCE_FIELDS)
                            : fields.equals(EVIDENCE_FIELDS);
            JsonNode genreCode = evidence.get("genreCode");
            JsonNode sourceMovieId = evidence.get("sourceMovieId");
            if (!validFields
                    || id == null
                    || !SHA256.matcher(id).matches()
                    || !ids.add(id)
                    || !EVIDENCE_GROUPS.contains(group)
                    || !groups.add(group)
                    || !"POSITIVE".equals(evidence.path("direction").textValue())
                    || !evidence.path("contribution").isNumber()
                    || evidence.path("contribution").decimalValue().compareTo(BigDecimal.ZERO) <= 0
                    || !decimalBetweenZeroAndOne(evidence.path("coverage"))
                    || genreCode != null
                            && (!"GENRE_PATTERN".equals(group)
                                    || !genreCode.isTextual()
                                    || !GENRE_CODE.matcher(genreCode.textValue()).matches())
                    || sourceMovieId != null
                            && (!"CONTENT_RELATION".equals(group) || !positiveLong(sourceMovieId))
                    || v2
                            && !id.equals(
                                    evidenceIdV2(
                                            state,
                                            candidate.movieId(),
                                            group,
                                            genreCode == null ? "" : genreCode.textValue(),
                                            sourceMovieId == null
                                                    ? ""
                                                    : Long.toString(sourceMovieId.longValue())))) {
                throw new IllegalArgumentException("Invalid GBT evidence");
            }
            BigDecimal contribution = evidence.path("contribution").decimalValue();
            if (previousContribution != null
                    && (contribution.compareTo(previousContribution) > 0
                            || contribution.compareTo(previousContribution) == 0
                                    && group.compareTo(previousGroup) < 0)) {
                throw new IllegalArgumentException("Unsorted GBT evidence");
            }
            previousContribution = contribution;
            previousGroup = group;
            coverage = coverage.add(evidence.path("coverage").decimalValue());
        }
        if (coverage.compareTo(item.path("coverage").decimalValue()) != 0) {
            throw new IllegalArgumentException("GBT evidence coverage differs");
        }
    }

    private String evidenceIdV2(
            UserRecommendationStateContract.Snapshot state,
            long movieId,
            String group,
            String genreCode,
            String sourceMovieId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value :
                    List.of(
                            EXPLANATION_V2,
                            state.modelVersion(),
                            state.servingRevision(),
                            FEATURE_VERSION,
                            RANKING_VERSION,
                            state.inputHash(),
                            Long.toString(movieId),
                            group,
                            genreCode,
                            sourceMovieId)) {
                byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
                digest.update(encoded);
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private boolean hasExactFields(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject()) {
            return false;
        }
        Set<String> actual = new HashSet<>();
        node.properties().forEach(property -> actual.add(property.getKey()));
        return actual.equals(fields);
    }

    private void validateLegacyVector(long userId, StoredCandidateBatch batch)
            throws JacksonException {
        String vector;
        try {
            vector = redisTemplate.opsForValue().get(vectorKey(userId));
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        if (vector == null || vector.isBlank()) {
            throw new IllegalArgumentException("Legacy vector is missing");
        }
        StoredVector storedVector = objectMapper.readValue(vector, StoredVector.class);
        validateVector(batch, storedVector);
    }

    private String key(long userId) {
        return "feelm:user:" + userId + ":candidates";
    }

    private String vectorKey(long userId) {
        return "feelm:user:" + userId + ":vector";
    }

    private String evidenceKey(long userId, String generationId) {
        return "feelm:user:" + userId + ":gbt-evidence:" + generationId;
    }

    private void validate(StoredCandidateBatch batch) {
        if (batch == null
                || batch.modelVersion() == null
                || batch.modelVersion().isBlank()
                || batch.modelVersion().length() > MAX_MODEL_VERSION_LENGTH
                || batch.candidateVersion() == null
                || !supportedVersion(batch.candidateVersion())
                || batch.generationId() == null
                || batch.generationId().isBlank()
                || batch.generationId().length() > MAX_GENERATION_ID_LENGTH
                || batch.updatedAt() == null
                || batch.taste() == null
                || batch.discovery() == null
                || batch.taste().size() + batch.discovery().size() == 0
                || batch.taste().size() > maxTasteCandidates
                || batch.discovery().size() > maxDiscoveryCandidates) {
            throw new IllegalArgumentException("Invalid candidate metadata");
        }

        Set<Long> movieIds = new HashSet<>();
        for (StoredTasteCandidate item : batch.taste()) {
            if (item == null
                    || item.movieId() <= 0
                    || item.score() == null
                    || !validTasteReasons(
                            reasons(batch.candidateVersion(), item.reason(), item.reasons()))
                    || !movieIds.add(item.movieId())) {
                throw new IllegalArgumentException("Invalid candidate item");
            }
        }
        movieIds.clear(); // DISCOVERY may reuse a TASTE movie when its own pool is exhausted.
        for (StoredDiscoveryCandidate item : batch.discovery()) {
            if (item == null
                    || item.movieId() <= 0
                    || item.score() == null
                    || !validDiscoveryReasons(
                            reasons(batch.candidateVersion(), item.reason(), item.reasons()),
                            batch.candidateVersion())
                    || !movieIds.add(item.movieId())) {
                throw new IllegalArgumentException("Invalid candidate item");
            }
        }
    }

    private boolean supportedVersion(Long version) {
        return version != null
                && (version == CURRENT_CANDIDATE_VERSION
                        || acceptV5 && version == PREVIOUS_CANDIDATE_VERSION);
    }

    private void validateVector(StoredCandidateBatch batch, StoredVector vector) {
        if (vector == null
                || !batch.modelVersion().equals(vector.modelVersion())
                || !batch.candidateVersion().equals(vector.candidateVersion())
                || !batch.generationId().equals(vector.generationId())) {
            throw new IllegalArgumentException("Candidate and vector generations differ");
        }
    }

    /** 실제 v5의 단일 reason과 v6의 reasons 배열을 구분해 내부 배열 형태로 읽는다. */
    private JsonNode reasons(long version, JsonNode singular, JsonNode plural) {
        if (version == PREVIOUS_CANDIDATE_VERSION) {
            if (singular == null || !singular.isObject() || plural != null) {
                throw new IllegalArgumentException("Invalid v5 candidate reason");
            }
            return objectMapper.createArrayNode().add(singular.deepCopy());
        }
        if (singular != null || plural == null || !plural.isArray()) {
            throw new IllegalArgumentException("Invalid v6 candidate reasons");
        }
        return plural;
    }

    private boolean validTasteReasons(JsonNode reasons) {
        if (reasons == null || !reasons.isArray()) {
            return false;
        }
        for (JsonNode reason : reasons) {
            if (!reason.isObject() || !validPersonalSourceReason(reason, true)) {
                return false;
            }
        }
        return true;
    }

    private boolean validPersonalSourceReason(JsonNode reason, boolean taste) {
        String code = reason.path("code").textValue();
        if (taste && !SHARED_GENRE_REASON.equals(code) && !TOP_RATED_SOURCE_REASON.equals(code)) {
            return false;
        }
        JsonNode sources = reason.path("sourceMovieIds");
        JsonNode genres = reason.path("matchedGenreCodes");
        if (!validPositiveLongArray(sources, false) || !validTextArray(genres)) {
            return false;
        }
        if (SHARED_GENRE_REASON.equals(code)) {
            return !sources.isEmpty() && !genres.isEmpty();
        }
        if (TOP_RATED_SOURCE_REASON.equals(code)) {
            return genres.isEmpty();
        }
        return !sources.isEmpty() || genres.isEmpty();
    }

    private boolean validDiscoveryReasons(JsonNode reasons, long candidateVersion) {
        if (reasons == null || !reasons.isArray()) {
            return false;
        }
        for (JsonNode reason : reasons) {
            if (!reason.isObject()) {
                return false;
            }
            String code = reason.path("code").textValue();
            if (DISCOVERY_AGGREGATE_REASON.equals(code)) {
                if (!validAggregateReason(reason)) {
                    return false;
                }
            } else if (DISCOVERY_PERSONAL_REASON.equals(code)) {
                if (candidateVersion != CURRENT_CANDIDATE_VERSION
                        || !validPersonalSourceReason(reason, false)) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
    }

    private boolean validAggregateReason(JsonNode reason) {
        JsonNode contributors = reason.path("topContributors");
        if (!DIRECTION_SOURCES.contains(reason.path("directionSource").textValue())
                || !nonNegativeLong(reason.path("contributorCount"))
                || !contributors.isArray()
                || !decimalBetweenZeroAndOne(reason.path("topContributionCoverage"))
                || !nonNegativeLong(reason.path("viewedCountInGroup"))
                || !positiveLong(reason.path("affinityRank"))
                || !positiveLong(reason.path("popularityRankInGroup"))) {
            return false;
        }
        for (JsonNode contributor : contributors) {
            if (!contributor.isObject()
                    || !positiveLong(contributor.path("movieId"))
                    || !CONTRIBUTOR_RELATIONS.contains(contributor.path("relation").textValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean validPositiveLongArray(JsonNode values, boolean requireValue) {
        if (!values.isArray() || requireValue && values.isEmpty()) {
            return false;
        }
        for (JsonNode value : values) {
            if (!positiveLong(value)) {
                return false;
            }
        }
        return true;
    }

    private boolean validTextArray(JsonNode values) {
        if (!values.isArray()) {
            return false;
        }
        for (JsonNode value : values) {
            if (!value.isTextual() || value.textValue().isBlank()) {
                return false;
            }
        }
        return true;
    }

    private boolean positiveLong(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0;
    }

    private boolean nonNegativeLong(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0;
    }

    private boolean decimalBetweenZeroAndOne(JsonNode value) {
        return value.isNumber()
                && value.decimalValue().compareTo(BigDecimal.ZERO) >= 0
                && value.decimalValue().compareTo(BigDecimal.ONE) <= 0;
    }

    private RecommendationCandidateBatch toBatch(
            StoredCandidateBatch stored, Map<Long, JsonNode> evidence) {
        List<RecommendationCandidate> items = new ArrayList<>();
        stored.taste()
                .forEach(
                        item ->
                                items.add(
                                        new RecommendationCandidate(
                                                item.movieId(),
                                                item.score(),
                                                "TASTE",
                                                null,
                                                reason(
                                                        reasons(
                                                                stored.candidateVersion(),
                                                                item.reason(),
                                                                item.reasons()),
                                                        evidence.get(item.movieId())))));
        stored.discovery()
                .forEach(
                        item ->
                                items.add(
                                        new RecommendationCandidate(
                                                item.movieId(),
                                                item.score(),
                                                "DISCOVERY",
                                                null,
                                                reason(
                                                        reasons(
                                                                stored.candidateVersion(),
                                                                item.reason(),
                                                                item.reasons()),
                                                        null))));
        return new RecommendationCandidateBatch(
                stored.modelVersion(),
                stored.candidateVersion(),
                stored.generationId(),
                stored.updatedAt(),
                List.copyOf(items));
    }

    /** 이유 객체의 코드별 필드를 재조립하지 않고 수신한 배열 그대로 버퍼 저장 계층에 전달한다. */
    private JsonNode reason(JsonNode reasons, JsonNode evidence) {
        ObjectNode reason = objectMapper.createObjectNode();
        reason.set("reasons", reasons.deepCopy());
        if (evidence != null) {
            reason.put("modelKind", "GBT");
            reason.put("modelVersion", evidence.path("modelVersion").textValue());
            reason.put("rankingVersion", evidence.path("rankingVersion").textValue());
            reason.put("explanationVersion", evidence.path("explanationVersion").textValue());
            reason.set("evidence", evidence.deepCopy());
        }
        return reason;
    }

    private BusinessException unavailable(Exception cause) {
        BusinessException exception = new BusinessException(ErrorCode.RECOMMENDATION_UNAVAILABLE);
        if (cause != null) {
            exception.initCause(cause);
        }
        return exception;
    }

    private record StoredCandidateBatch(
            String modelVersion,
            @JsonDeserialize(using = StrictLongDeserializer.class) Long candidateVersion,
            String generationId,
            Instant updatedAt,
            List<StoredTasteCandidate> taste,
            List<StoredDiscoveryCandidate> discovery) {}

    private record StoredVector(
            String modelVersion,
            @JsonDeserialize(using = StrictLongDeserializer.class) Long candidateVersion,
            String generationId) {}

    private record StoredTasteCandidate(
            @JsonDeserialize(using = StrictLongDeserializer.class) long movieId,
            @JsonDeserialize(using = StrictDecimalDeserializer.class) BigDecimal score,
            JsonNode reason,
            JsonNode reasons) {}

    private record StoredDiscoveryCandidate(
            @JsonDeserialize(using = StrictLongDeserializer.class) long movieId,
            @JsonDeserialize(using = StrictDecimalDeserializer.class) BigDecimal score,
            JsonNode reason,
            JsonNode reasons) {}

    /** Redis 계약의 정수 필드가 문자열이나 소수에서 묵시적으로 변환되는 것을 막는다. */
    private static final class StrictLongDeserializer extends ValueDeserializer<Long> {

        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode value = context.readTree(parser);
            if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                return context.reportInputMismatch(Long.class, "Expected an integer JSON value");
            }
            return value.longValue();
        }
    }

    /** 추천 점수는 JSON number만 허용하고 숫자 문자열의 묵시적 변환은 막는다. */
    private static final class StrictDecimalDeserializer extends ValueDeserializer<BigDecimal> {

        @Override
        public BigDecimal deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode value = context.readTree(parser);
            if (!value.isNumber()) {
                return context.reportInputMismatch(
                        BigDecimal.class, "Expected a numeric JSON value");
            }
            return value.decimalValue();
        }
    }
}
