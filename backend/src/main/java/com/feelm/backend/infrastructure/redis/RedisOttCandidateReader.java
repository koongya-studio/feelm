package com.feelm.backend.infrastructure.redis;

import com.feelm.backend.domain.recommendation.model.OttCandidateSnapshot;
import com.feelm.backend.domain.recommendation.model.OttCandidateSnapshot.ScoredMovie;
import com.feelm.backend.domain.recommendation.repository.OttCandidateReader;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Consumer Candidate의 TASTE ID와 점수만 읽는다. 추천 이유 형식은 이 API의 입력이 아니다. */
@Component
@RequiredArgsConstructor
public class RedisOttCandidateReader implements OttCandidateReader {

    private static final int MAX_TASTE_CANDIDATES = 500;
    private static final int SUPPORTED_CANDIDATE_VERSION = 6;
    private static final int RETRY_DELAY_MILLIS = 20;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public OttCandidateSnapshot read(long userId) {
        Exception lastFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return readOnce(userId);
            } catch (Exception exception) {
                lastFailure = exception;
                if (attempt == 0) {
                    try {
                        Thread.sleep(RETRY_DELAY_MILLIS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        lastFailure = interrupted;
                        break;
                    }
                }
            }
        }
        BusinessException unavailable = new BusinessException(ErrorCode.RECOMMENDATION_UNAVAILABLE);
        unavailable.initCause(lastFailure);
        throw unavailable;
    }

    private OttCandidateSnapshot readOnce(long userId) throws JacksonException {
        String stored = redisTemplate.opsForValue().get("feelm:user:" + userId + ":candidates");
        if (stored == null || stored.isBlank()) {
            throw new IllegalArgumentException("Candidate key is missing");
        }
        JsonNode root = objectMapper.readTree(stored);
        JsonNode taste = root.path("taste");
        if (!root.path("candidateVersion").isIntegralNumber()
                || !root.path("candidateVersion").canConvertToLong()
                || root.path("candidateVersion").longValue() != SUPPORTED_CANDIDATE_VERSION
                || !root.path("generationId").isTextual()
                || root.path("generationId").asText().isBlank()
                || !taste.isArray()
                || taste.isEmpty()
                || taste.size() > MAX_TASTE_CANDIDATES) {
            throw new IllegalArgumentException("Invalid OTT candidate metadata");
        }
        List<ScoredMovie> items = new ArrayList<>(taste.size());
        Set<Long> movieIds = new HashSet<>();
        for (JsonNode candidate : taste) {
            JsonNode id = candidate.path("movieId");
            JsonNode score = candidate.path("score");
            if (!id.isIntegralNumber()
                    || !id.canConvertToLong()
                    || id.longValue() <= 0
                    || !score.isNumber()
                    || !movieIds.add(id.longValue())) {
                throw new IllegalArgumentException("Invalid OTT candidate item");
            }
            BigDecimal value = score.decimalValue();
            items.add(new ScoredMovie(id.longValue(), value));
        }
        return new OttCandidateSnapshot(List.copyOf(items));
    }
}
