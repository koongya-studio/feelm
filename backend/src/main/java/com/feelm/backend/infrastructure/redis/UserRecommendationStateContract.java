package com.feelm.backend.infrastructure.redis;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** GBT 사용자 상태와 같은 generation의 Candidate를 결합하는 Redis 내부 계약. */
final class UserRecommendationStateContract {
    static final int STATE_VERSION = 1;
    static final String MODEL_KIND = "GBT";
    static final String KEY_SUFFIX = ":recommendation-state";
    static final String CURRENT_MODEL_KEY = "feelm:model:current";

    private static final int MAX_MODEL_VERSION_LENGTH = 50;
    private static final int MAX_REVISION_LENGTH = 100;
    private static final int MAX_GENERATION_LENGTH = 100;
    private static final int MAX_INPUT_SCHEMA_LENGTH = 50;
    private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern MODEL_VERSION = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    private UserRecommendationStateContract() {}

    static boolean hasGbtMetadata(JsonNode candidate) {
        return candidate.has("servingRevision")
                || candidate.has("inputSchemaVersion")
                || candidate.has("inputHash");
    }

    static Snapshot parse(JsonNode root, long expectedUserId) {
        if (root == null
                || !root.isObject()
                || requiredPositiveInt(root, "stateVersion") != STATE_VERSION
                || requiredPositiveLong(root, "userId") != expectedUserId
                || !MODEL_KIND.equals(requiredText(root, "modelKind", 10))) {
            throw new IllegalArgumentException("Invalid recommendation state identity");
        }
        String modelVersion = requiredModelVersion(root);
        String servingRevision = requiredText(root, "servingRevision", MAX_REVISION_LENGTH);
        String generationId = requiredText(root, "generationId", MAX_GENERATION_LENGTH);
        String inputSchemaVersion =
                requiredText(root, "inputSchemaVersion", MAX_INPUT_SCHEMA_LENGTH);
        String inputHash = requiredHash(root, "inputHash");
        long ratingCount = requiredNonNegativeLong(root, "ratingCount");
        long dismissCount = requiredNonNegativeLong(root, "dismissCount");
        long supportedInputCount = requiredNonNegativeLong(root, "supportedInputCount");
        Instant updatedAt;
        try {
            updatedAt = Instant.parse(requiredText(root, "updatedAt", 40));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Invalid updatedAt", exception);
        }
        return new Snapshot(
                expectedUserId,
                modelVersion,
                servingRevision,
                generationId,
                inputSchemaVersion,
                inputHash,
                ratingCount,
                dismissCount,
                supportedInputCount,
                updatedAt);
    }

    static void verifyCandidate(JsonNode candidate, Snapshot state) {
        if (candidate == null
                || !candidate.isObject()
                || !state.modelVersion().equals(requiredModelVersion(candidate))
                || !state.servingRevision()
                        .equals(requiredText(candidate, "servingRevision", MAX_REVISION_LENGTH))
                || !state.generationId()
                        .equals(requiredText(candidate, "generationId", MAX_GENERATION_LENGTH))
                || !state.inputSchemaVersion()
                        .equals(
                                requiredText(
                                        candidate, "inputSchemaVersion", MAX_INPUT_SCHEMA_LENGTH))
                || !state.inputHash().equals(requiredHash(candidate, "inputHash"))) {
            throw new IllegalArgumentException("Recommendation state and candidates differ");
        }
    }

    static ServingModel parseGbtModel(JsonNode root) {
        if (root == null
                || !root.isObject()
                || !MODEL_KIND.equals(requiredText(root, "modelKind", 10))) {
            throw new IllegalArgumentException("Invalid current GBT model");
        }
        return new ServingModel(
                requiredModelVersion(root),
                requiredText(root, "servingRevision", MAX_REVISION_LENGTH));
    }

    static void verifyCurrentModel(JsonNode currentModel, Snapshot state) {
        ServingModel servingModel = parseGbtModel(currentModel);
        if (!servingModel.modelVersion().equals(state.modelVersion())
                || !servingModel.servingRevision().equals(state.servingRevision())) {
            throw new IllegalArgumentException("Recommendation state is not current");
        }
    }

    static String key(long userId) {
        return "feelm:user:" + userId + KEY_SUFFIX;
    }

    static String requiredText(JsonNode root, String field, int maxLength) {
        JsonNode value = root.path(field);
        if (!value.isTextual()
                || value.textValue().isBlank()
                || value.textValue().length() > maxLength) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value.textValue();
    }

    private static String requiredModelVersion(JsonNode root) {
        String value = requiredText(root, "modelVersion", MAX_MODEL_VERSION_LENGTH);
        if (!MODEL_VERSION.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid modelVersion");
        }
        return value;
    }

    private static String requiredHash(JsonNode root, String field) {
        String value = requiredText(root, field, 71);
        if (!SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value;
    }

    private static int requiredPositiveInt(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value.intValue();
    }

    private static long requiredPositiveLong(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value.longValue();
    }

    private static long requiredNonNegativeLong(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value.longValue();
    }

    record Snapshot(
            long userId,
            String modelVersion,
            String servingRevision,
            String generationId,
            String inputSchemaVersion,
            String inputHash,
            long ratingCount,
            long dismissCount,
            long supportedInputCount,
            Instant updatedAt) {}

    record ServingModel(String modelVersion, String servingRevision) {}
}
