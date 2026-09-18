package com.feelm.backend.infrastructure.redis;

import com.feelm.backend.domain.recommendation.model.RecommendationCandidate;
import com.feelm.backend.domain.recommendation.model.RecommendationCandidateBatch;
import com.feelm.backend.domain.recommendation.repository.RecommendationCandidateReader;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
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

/** Redis의 개인 추천 후보 JSON을 읽고 전환 기간의 Candidate v5/v6 계약을 검증한다. */
@Component
public class RedisRecommendationCandidateReader implements RecommendationCandidateReader {

    private static final int MAX_MODEL_VERSION_LENGTH = 50;
    private static final int MAX_GENERATION_ID_LENGTH = 100;
    private static final int READ_ATTEMPTS = 3;
    private static final long RETRY_DELAY_NANOS = 20_000_000L;
    private static final long CURRENT_CANDIDATE_VERSION = 6;
    private static final long PREVIOUS_CANDIDATE_VERSION = 5;
    private static final String SHARED_GENRE_REASON = "SHARED_GENRE";
    private static final String TOP_RATED_SOURCE_REASON = "TOP_RATED_SOURCE";
    private static final String DISCOVERY_AGGREGATE_REASON = "DISCOVERY_AGGREGATE_AFFINITY";
    private static final String DISCOVERY_PERSONAL_REASON = "DISCOVERY_PERSONAL_SCORE";
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
        String vector;
        try {
            stored = redisTemplate.opsForValue().get(key(userId));
            vector = redisTemplate.opsForValue().get(vectorKey(userId));
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        if (stored == null || stored.isBlank() || vector == null || vector.isBlank()) {
            throw unavailable(null);
        }

        try {
            StoredCandidateBatch storedBatch =
                    objectMapper.readValue(stored, StoredCandidateBatch.class);
            StoredVector storedVector = objectMapper.readValue(vector, StoredVector.class);
            validate(storedBatch);
            validateVector(storedBatch, storedVector);
            return toBatch(storedBatch);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw unavailable(exception);
        }
    }

    private String key(long userId) {
        return "feelm:user:" + userId + ":candidates";
    }

    private String vectorKey(long userId) {
        return "feelm:user:" + userId + ":vector";
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

    private RecommendationCandidateBatch toBatch(StoredCandidateBatch stored) {
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
                                                                item.reasons())))));
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
                                                                item.reasons())))));
        return new RecommendationCandidateBatch(
                stored.modelVersion(),
                stored.candidateVersion(),
                stored.generationId(),
                stored.updatedAt(),
                List.copyOf(items));
    }

    /** 이유 객체의 코드별 필드를 재조립하지 않고 수신한 배열 그대로 버퍼 저장 계층에 전달한다. */
    private JsonNode reason(JsonNode reasons) {
        return objectMapper.createObjectNode().set("reasons", reasons.deepCopy());
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
