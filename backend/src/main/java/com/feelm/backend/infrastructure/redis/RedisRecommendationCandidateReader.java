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

/** Redis의 개인 추천 후보 JSON을 읽고 Candidate v6 계약을 검증한다. */
@Component
public class RedisRecommendationCandidateReader implements RecommendationCandidateReader {

    private static final int MAX_MODEL_VERSION_LENGTH = 50;
    private static final int MAX_GENERATION_ID_LENGTH = 100;
    private static final long SUPPORTED_CANDIDATE_VERSION = 6;
    private static final String SHARED_FEATURES_REASON = "SHARED_FEATURES_WITH_LIKED_MOVIE";
    private static final String DISCOVERY_AGGREGATE_REASON = "DISCOVERY_AGGREGATE_AFFINITY";
    private static final String DISCOVERY_PERSONAL_REASON = "DISCOVERY_PERSONAL_SCORE";
    private static final Set<String> DIRECTION_SOURCES = Set.of("RATING", "ONBOARDING");
    private static final Set<String> CONTRIBUTOR_RELATIONS =
            Set.of("POSITIVE_ALIGNMENT", "NEGATIVE_CONTRAST");

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final int maxTasteCandidates;
    private final int maxDiscoveryCandidates;

    public RedisRecommendationCandidateReader(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${feelm.recommendation.candidates.max-taste:500}") int maxTasteCandidates,
            @Value("${feelm.recommendation.candidates.max-discovery:100}")
                    int maxDiscoveryCandidates) {
        if (maxTasteCandidates < 1 || maxDiscoveryCandidates < 1) {
            throw new IllegalArgumentException("Candidate limits must be positive");
        }
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.maxTasteCandidates = maxTasteCandidates;
        this.maxDiscoveryCandidates = maxDiscoveryCandidates;
    }

    @Override
    public RecommendationCandidateBatch read(long userId) {
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
            StoredCandidateBatch storedBatch =
                    objectMapper.readValue(stored, StoredCandidateBatch.class);
            validate(storedBatch);
            return toBatch(storedBatch);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw unavailable(exception);
        }
    }

    private String key(long userId) {
        return "feelm:user:" + userId + ":candidates";
    }

    private void validate(StoredCandidateBatch batch) {
        if (batch == null
                || batch.modelVersion() == null
                || batch.modelVersion().isBlank()
                || batch.modelVersion().length() > MAX_MODEL_VERSION_LENGTH
                || batch.candidateVersion() == null
                || batch.candidateVersion() != SUPPORTED_CANDIDATE_VERSION
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
                    || !validTasteReasons(item.reasons())
                    || !movieIds.add(item.movieId())) {
                throw new IllegalArgumentException("Invalid candidate item");
            }
        }
        for (StoredDiscoveryCandidate item : batch.discovery()) {
            if (item == null
                    || item.movieId() <= 0
                    || item.score() == null
                    || !validDiscoveryReasons(item.reasons())
                    || !movieIds.add(item.movieId())) {
                throw new IllegalArgumentException("Invalid candidate item");
            }
        }
    }

    private boolean validTasteReasons(JsonNode reasons) {
        if (reasons == null || !reasons.isArray()) {
            return false;
        }
        for (JsonNode reason : reasons) {
            JsonNode sharedFeatures = reason.path("sharedFeatures");
            if (!reason.isObject()
                    || !SHARED_FEATURES_REASON.equals(reason.path("code").textValue())
                    || !positiveLong(reason.path("sourceMovieId"))
                    || !sharedFeatures.isObject()
                    || !validPositiveLongArray(sharedFeatures.path("tmdbGenreIds"), false)
                    || !validPositiveLongArray(sharedFeatures.path("tmdbDirectorIds"), false)
                    || !validPositiveLongArray(sharedFeatures.path("tmdbCastIds"), false)
                    || !validPositiveLongArray(sharedFeatures.path("tmdbKeywordIds"), false)
                    || allFeatureArraysEmpty(sharedFeatures)) {
                return false;
            }
        }
        return true;
    }

    private boolean allFeatureArraysEmpty(JsonNode sharedFeatures) {
        return sharedFeatures.path("tmdbGenreIds").isEmpty()
                && sharedFeatures.path("tmdbDirectorIds").isEmpty()
                && sharedFeatures.path("tmdbCastIds").isEmpty()
                && sharedFeatures.path("tmdbKeywordIds").isEmpty();
    }

    private boolean validDiscoveryReasons(JsonNode reasons) {
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
                if (!validPersonalReason(reason)) {
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

    private boolean validPersonalReason(JsonNode reason) {
        return validPositiveLongArray(reason.path("sourceMovieIds"), true)
                && validTextArray(reason.path("matchedGenreCodes"));
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
                                                reason(item.reasons()))));
        stored.discovery()
                .forEach(
                        item ->
                                items.add(
                                        new RecommendationCandidate(
                                                item.movieId(),
                                                item.score(),
                                                "DISCOVERY",
                                                null,
                                                reason(item.reasons()))));
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

    private record StoredTasteCandidate(
            @JsonDeserialize(using = StrictLongDeserializer.class) long movieId,
            @JsonDeserialize(using = StrictDecimalDeserializer.class) BigDecimal score,
            JsonNode reasons) {}

    private record StoredDiscoveryCandidate(
            @JsonDeserialize(using = StrictLongDeserializer.class) long movieId,
            @JsonDeserialize(using = StrictDecimalDeserializer.class) BigDecimal score,
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
