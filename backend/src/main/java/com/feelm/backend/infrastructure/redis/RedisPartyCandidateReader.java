package com.feelm.backend.infrastructure.redis;

import com.feelm.backend.domain.party.model.PartyCandidateSnapshot;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Spark 파티 결과를 읽고 API-058이 신뢰할 수 있는 구조인지 검증한다. */
@Component
@RequiredArgsConstructor
public class RedisPartyCandidateReader {
    private static final int MAX_CANDIDATES = 500;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public PartyCandidateSnapshot read(long partyId) {
        try {
            String value = redisTemplate.opsForValue().get(key(partyId));
            JsonNode root = parse(value);
            long storedPartyId = requiredPositiveLong(root, "partyId");
            String modelVersion = requiredText(root, "modelVersion", 50);
            String algorithmVersion = requiredText(root, "algorithmVersion", 100);
            String inputId = requiredText(root, "inputId", 100);
            Instant generatedAt = requiredInstant(root, "generatedAt");
            if (storedPartyId != partyId) {
                throw new IllegalArgumentException("Party candidate metadata mismatch");
            }
            return new PartyCandidateSnapshot(
                    storedPartyId,
                    modelVersion,
                    algorithmVersion,
                    inputId,
                    generatedAt,
                    candidates(root.path("candidates")),
                    mode(root));
        } catch (Exception exception) {
            throw new IllegalStateException("Party candidate Redis value is invalid", exception);
        }
    }

    private String mode(JsonNode root) {
        if (!root.has("recommendationMode")) return "PERSONALIZED";
        String mode = requiredText(root, "recommendationMode", 20);
        if (!Set.of("PERSONALIZED", "POPULARITY").contains(mode)) {
            throw new IllegalArgumentException("Invalid recommendation mode");
        }
        return mode;
    }

    private JsonNode parse(String value) throws Exception {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Party candidate key is missing");
        }
        JsonNode root = objectMapper.readTree(value);
        if (!root.isObject()) {
            throw new IllegalArgumentException("Party candidate value must be an object");
        }
        return root;
    }

    private List<PartyCandidateSnapshot.Candidate> candidates(JsonNode values) {
        if (!values.isArray() || values.isEmpty() || values.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("Invalid party candidates");
        }
        Set<Long> movieIds = new HashSet<>();
        List<PartyCandidateSnapshot.Candidate> result = new ArrayList<>(values.size());
        for (JsonNode value : values) {
            long movieId = requiredPositiveLong(value, "movieId");
            if (!movieIds.add(movieId)) {
                throw new IllegalArgumentException("Duplicate party candidate movie");
            }
            result.add(new PartyCandidateSnapshot.Candidate(movieId));
        }
        return List.copyOf(result);
    }

    private static long requiredPositiveLong(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value.longValue();
    }

    private static String requiredText(JsonNode root, String field, int maxLength) {
        JsonNode value = root.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > maxLength) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value.asText();
    }

    private static Instant requiredInstant(JsonNode root, String field) {
        try {
            return Instant.parse(requiredText(root, field, 50));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Invalid " + field, exception);
        }
    }

    private static String key(long partyId) {
        if (partyId <= 0) {
            throw new IllegalArgumentException("Party ID must be positive");
        }
        return "feelm:party:" + partyId + ":candidates";
    }
}
