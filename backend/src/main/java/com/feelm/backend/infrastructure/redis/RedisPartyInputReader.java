package com.feelm.backend.infrastructure.redis;

import com.feelm.backend.domain.party.model.PartyInputSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 파티 전체의 모델·vector·candidates를 단일 Redis MGET 스냅샷으로 읽는다. */
@Component
@RequiredArgsConstructor
public class RedisPartyInputReader {
    private static final int CANDIDATE_VERSION = 6;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public PartyInputSnapshot read(List<Long> userIds) {
        if (userIds.isEmpty() || userIds.size() > 4) {
            throw new IllegalArgumentException("Party must have 1–4 members");
        }
        List<String> keys = new ArrayList<>(1 + userIds.size() * 2);
        keys.add("feelm:model:current");
        for (long userId : userIds) {
            keys.add("feelm:user:" + userId + ":vector");
            keys.add("feelm:user:" + userId + ":candidates");
        }
        try {
            List<String> values = redisTemplate.opsForValue().multiGet(keys);
            if (values == null || values.size() != keys.size()) {
                throw new IllegalArgumentException("Party Redis snapshot is incomplete");
            }
            JsonNode model = parse(values.getFirst());
            String modelVersion = requiredText(model, "modelVersion");
            int dimension = requiredPositiveInt(model, "factorDimension");
            List<PartyInputSnapshot.Member> members = new ArrayList<>(userIds.size());
            for (int index = 0; index < userIds.size(); index++) {
                JsonNode vector = parse(values.get(index * 2 + 1));
                JsonNode candidates = parse(values.get(index * 2 + 2));
                String generationId = requiredText(vector, "generationId");
                if (!modelVersion.equals(requiredText(vector, "modelVersion"))
                        || !modelVersion.equals(requiredText(candidates, "modelVersion"))
                        || !generationId.equals(requiredText(candidates, "generationId"))
                        || requiredPositiveInt(vector, "candidateVersion") != CANDIDATE_VERSION
                        || requiredPositiveInt(candidates, "candidateVersion")
                                != CANDIDATE_VERSION) {
                    throw new IllegalArgumentException("Party member generation or model mismatch");
                }
                List<Double> factors = factors(vector.path("vector"), dimension);
                List<Long> movieIds = movieIds(candidates);
                members.add(
                        new PartyInputSnapshot.Member(
                                userIds.get(index), generationId, factors, movieIds));
            }
            return new PartyInputSnapshot(modelVersion, List.copyOf(members));
        } catch (Exception exception) {
            throw new IllegalStateException("Party Redis snapshot is invalid", exception);
        }
    }

    private JsonNode parse(String value) throws Exception {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Party Redis key is missing");
        }
        JsonNode root = objectMapper.readTree(value);
        if (!root.isObject()) {
            throw new IllegalArgumentException("Party Redis value must be an object");
        }
        return root;
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 100) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value.asText();
    }

    private static int requiredPositiveInt(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value.intValue();
    }

    private static List<Double> factors(JsonNode values, int dimension) {
        if (!values.isArray() || (!values.isEmpty() && values.size() != dimension)) {
            throw new IllegalArgumentException("Invalid vector dimension");
        }
        List<Double> factors = new ArrayList<>(values.size());
        for (JsonNode value : values) {
            if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
                throw new IllegalArgumentException("Invalid vector factor");
            }
            factors.add(value.doubleValue());
        }
        return List.copyOf(factors);
    }

    private static List<Long> movieIds(JsonNode candidates) {
        JsonNode taste = candidates.path("taste");
        JsonNode discovery = candidates.path("discovery");
        if (!taste.isArray()
                || !discovery.isArray()
                || taste.size() > 500
                || discovery.size() > 100) {
            throw new IllegalArgumentException("Invalid candidate groups");
        }
        Set<Long> movieIds = new LinkedHashSet<>();
        appendMovieIds(taste, movieIds);
        appendMovieIds(discovery, movieIds);
        if (movieIds.isEmpty()) {
            throw new IllegalArgumentException("Party candidate list is empty");
        }
        return List.copyOf(movieIds);
    }

    private static void appendMovieIds(JsonNode group, Set<Long> movieIds) {
        for (JsonNode candidate : group) {
            JsonNode id = candidate.path("movieId");
            if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0) {
                throw new IllegalArgumentException("Invalid candidate movie ID");
            }
            movieIds.add(id.longValue());
        }
    }
}
