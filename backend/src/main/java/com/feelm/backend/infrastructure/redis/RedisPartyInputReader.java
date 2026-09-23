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

/** 파티 전체의 모델·사용자 상태·후보를 단일 Redis MGET 스냅샷으로 읽는다. */
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
        List<Long> sortedUserIds = userIds.stream().sorted().distinct().toList();
        if (sortedUserIds.size() != userIds.size() || sortedUserIds.getFirst() <= 0) {
            throw new IllegalArgumentException("Party member IDs must be positive and unique");
        }
        List<String> keys = new ArrayList<>(1 + sortedUserIds.size() * 3);
        keys.add(UserRecommendationStateContract.CURRENT_MODEL_KEY);
        for (long userId : sortedUserIds) {
            keys.add(UserRecommendationStateContract.key(userId));
            keys.add("feelm:user:" + userId + ":vector");
            keys.add("feelm:user:" + userId + ":candidates");
        }
        try {
            List<String> values = redisTemplate.opsForValue().multiGet(keys);
            if (values == null || values.size() != keys.size()) {
                throw new IllegalArgumentException("Party Redis snapshot is incomplete");
            }
            JsonNode model = parse(values.getFirst());
            if (model.has("modelKind")) {
                return readGbt(model, values, sortedUserIds);
            }
            return readLegacy(model, values, sortedUserIds);
        } catch (Exception exception) {
            throw new IllegalStateException("Party Redis snapshot is invalid", exception);
        }
    }

    private PartyInputSnapshot readGbt(JsonNode model, List<String> values, List<Long> userIds)
            throws Exception {
        var servingModel = UserRecommendationStateContract.parseGbtModel(model);
        String modelVersion = servingModel.modelVersion();
        String servingRevision = servingModel.servingRevision();
        List<PartyInputSnapshot.Member> members = new ArrayList<>(userIds.size());
        for (int index = 0; index < userIds.size(); index++) {
            int offset = index * 3 + 1;
            long userId = userIds.get(index);
            JsonNode stateValue = parse(values.get(offset));
            JsonNode candidates = parse(values.get(offset + 2));
            var state = UserRecommendationStateContract.parse(stateValue, userId);
            UserRecommendationStateContract.verifyCandidate(candidates, state);
            if (!modelVersion.equals(state.modelVersion())
                    || !servingRevision.equals(state.servingRevision())
                    || requiredPositiveInt(candidates, "candidateVersion") != CANDIDATE_VERSION) {
                throw new IllegalArgumentException("Party member serving revision mismatch");
            }
            members.add(
                    PartyInputSnapshot.Member.gbt(
                            userId,
                            state.generationId(),
                            state.inputSchemaVersion(),
                            state.inputHash(),
                            movieIds(candidates)));
        }
        return new PartyInputSnapshot(
                PartyInputSnapshot.GBT_STATE_SCHEMA_VERSION,
                modelVersion,
                servingRevision,
                List.copyOf(members));
    }

    private PartyInputSnapshot readLegacy(JsonNode model, List<String> values, List<Long> userIds)
            throws Exception {
        String modelVersion = requiredText(model, "modelVersion");
        int dimension = requiredPositiveInt(model, "factorDimension");
        List<PartyInputSnapshot.Member> members = new ArrayList<>(userIds.size());
        for (int index = 0; index < userIds.size(); index++) {
            int offset = index * 3 + 1;
            JsonNode vector = parse(values.get(offset + 1));
            JsonNode candidates = parse(values.get(offset + 2));
            String generationId = requiredText(vector, "generationId");
            if (!modelVersion.equals(requiredText(vector, "modelVersion"))
                    || !modelVersion.equals(requiredText(candidates, "modelVersion"))
                    || !generationId.equals(requiredText(candidates, "generationId"))
                    || requiredPositiveInt(vector, "candidateVersion") != CANDIDATE_VERSION
                    || requiredPositiveInt(candidates, "candidateVersion") != CANDIDATE_VERSION) {
                throw new IllegalArgumentException("Party member generation or model mismatch");
            }
            List<Double> factors = factors(vector.path("vector"), dimension);
            List<Long> movieIds = movieIds(candidates);
            members.add(
                    new PartyInputSnapshot.Member(
                            userIds.get(index), generationId, factors, movieIds));
        }
        return new PartyInputSnapshot(modelVersion, List.copyOf(members));
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
