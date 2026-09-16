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
import java.util.Map;
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

/**
 * {@code feelm:user:{userId}:candidates}의 JSON을 읽어 추천 후보 묶음으로 변환한다.
 *
 * <p>이 클래스의 검증은 Redis 값이 합의된 입력 계약을 따르는지 확인하는 역할이다. 사용자에게 이미 처리된 영화인지와 같은 비즈니스 필터링은 {@code
 * RecommendationBufferWriter}에서 수행한다.
 */
@Component
public class RedisRecommendationCandidateReader implements RecommendationCandidateReader {

    private static final int MAX_MODEL_VERSION_LENGTH = 50;
    private static final int MAX_GENERATION_ID_LENGTH = 100;
    private static final int MAX_FLAVOR_CODE_LENGTH = 30;
    private static final String SHARED_FEATURES_REASON = "SHARED_FEATURES_WITH_LIKED_MOVIE";

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
                || batch.candidateVersion() < 0
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

        // 같은 영화의 점수나 분류를 Spring이 임의로 선택하지 않도록 후보 묶음 전체를 거부한다.
        Set<Long> movieIds = new HashSet<>();
        for (StoredTasteCandidate item : batch.taste()) {
            if (item == null
                    || item.movieId() <= 0
                    || item.score() == null
                    || !validReasons(item.reasons())
                    || !movieIds.add(item.movieId())) {
                throw new IllegalArgumentException("Invalid candidate item");
            }
        }
        for (StoredDiscoveryCandidate item : batch.discovery()) {
            if (item == null
                    || item.movieId() <= 0
                    || item.score() == null
                    || item.flavorCode() == null
                    || item.flavorCode().isBlank()
                    || item.flavorCode().length() > MAX_FLAVOR_CODE_LENGTH
                    || !validReasons(item.reasons())
                    || !movieIds.add(item.movieId())) {
                throw new IllegalArgumentException("Invalid candidate item");
            }
        }
    }

    private boolean validReasons(List<StoredReason> reasons) {
        if (reasons == null) {
            return false;
        }
        for (StoredReason reason : reasons) {
            if (reason == null
                    || !SHARED_FEATURES_REASON.equals(reason.code())
                    || reason.sourceMovieId() <= 0
                    || reason.sharedFeatures() == null
                    || !validFeatureIds(reason.sharedFeatures().tmdbGenreIds())
                    || !validFeatureIds(reason.sharedFeatures().tmdbDirectorIds())
                    || !validFeatureIds(reason.sharedFeatures().tmdbCastIds())
                    || !validFeatureIds(reason.sharedFeatures().tmdbKeywordIds())
                    || reason.sharedFeatures().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private boolean validFeatureIds(List<Long> ids) {
        return ids != null && ids.stream().allMatch(id -> id != null && id > 0);
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
                                                item.flavorCode(),
                                                reason(item.reasons()))));
        return new RecommendationCandidateBatch(
                stored.modelVersion(),
                stored.candidateVersion(),
                stored.generationId(),
                stored.updatedAt(),
                List.copyOf(items));
    }

    private JsonNode reason(List<StoredReason> reasons) {
        return objectMapper.valueToTree(
                Map.of(
                        "reasons",
                        reasons.stream()
                                .map(
                                        reason ->
                                                Map.of(
                                                        "code",
                                                        reason.code(),
                                                        "sourceMovieId",
                                                        reason.sourceMovieId(),
                                                        "sharedFeatures",
                                                        Map.of(
                                                                "tmdbGenreIds",
                                                                reason.sharedFeatures()
                                                                        .tmdbGenreIds(),
                                                                "tmdbDirectorIds",
                                                                reason.sharedFeatures()
                                                                        .tmdbDirectorIds(),
                                                                "tmdbCastIds",
                                                                reason.sharedFeatures()
                                                                        .tmdbCastIds(),
                                                                "tmdbKeywordIds",
                                                                reason.sharedFeatures()
                                                                        .tmdbKeywordIds())))
                                .toList()));
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
            List<StoredReason> reasons) {}

    private record StoredDiscoveryCandidate(
            @JsonDeserialize(using = StrictLongDeserializer.class) long movieId,
            @JsonDeserialize(using = StrictDecimalDeserializer.class) BigDecimal score,
            String flavorCode,
            List<StoredReason> reasons) {}

    private record StoredReason(
            String code,
            @JsonDeserialize(using = StrictLongDeserializer.class) long sourceMovieId,
            StoredSharedFeatures sharedFeatures) {}

    private record StoredSharedFeatures(
            @JsonDeserialize(contentUsing = StrictLongDeserializer.class) List<Long> tmdbGenreIds,
            @JsonDeserialize(contentUsing = StrictLongDeserializer.class)
                    List<Long> tmdbDirectorIds,
            @JsonDeserialize(contentUsing = StrictLongDeserializer.class) List<Long> tmdbCastIds,
            @JsonDeserialize(contentUsing = StrictLongDeserializer.class)
                    List<Long> tmdbKeywordIds) {

        private boolean isEmpty() {
            return tmdbGenreIds.isEmpty()
                    && tmdbDirectorIds.isEmpty()
                    && tmdbCastIds.isEmpty()
                    && tmdbKeywordIds.isEmpty();
        }
    }

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
