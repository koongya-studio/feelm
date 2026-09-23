package com.feelm.backend.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.party.model.PartyInputSnapshot;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

class RedisPartyInputReaderTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedisPartyInputReader reader =
            new RedisPartyInputReader(redis, JsonMapper.builder().build());

    @Test
    void readsOneSnapshotAndKeepsPreferenceOrder() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList()))
                .thenReturn(
                        Arrays.asList(
                                model(),
                                null,
                                vector("g1", "v1", "[0.1,0.2]"),
                                candidates("g1", "v1", "[1,2]", "[2,3]")));

        var snapshot = reader.read(List.of(1024L));

        assertThat(snapshot.modelVersion()).isEqualTo("v1");
        assertThat(snapshot.members().getFirst().legacyVector()).containsExactly(0.1, 0.2);
        assertThat(snapshot.members().getFirst().candidateMovieIds()).containsExactly(1L, 2L, 3L);
        verify(values)
                .multiGet(
                        List.of(
                                "feelm:model:current",
                                "feelm:user:1024:recommendation-state",
                                "feelm:user:1024:vector",
                                "feelm:user:1024:candidates"));
    }

    @Test
    void rejectsMixedGenerationAndModel() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList()))
                .thenReturn(
                        Arrays.asList(
                                model(),
                                null,
                                vector("g1", "v1", "[]"),
                                candidates("g2", "v1", "[1]", "[]")));

        assertThatThrownBy(() -> reader.read(List.of(1024L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    void rejectsMissingMemberKey() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList()))
                .thenReturn(
                        java.util.Arrays.asList(
                                model(), null, null, candidates("g1", "v1", "[1]", "[]")));

        assertThatThrownBy(() -> reader.read(List.of(1024L)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readsFourMembersWithEmptyVectorsAndAtMostSixHundredCandidates() {
        when(redis.opsForValue()).thenReturn(values);
        String taste = movieIds(1, 500);
        String discovery = movieIds(501, 600);
        when(values.multiGet(anyList()))
                .thenReturn(
                        Arrays.asList(
                                model(),
                                null,
                                vector("g1", "v1", "[]"),
                                candidates("g1", "v1", taste, discovery),
                                null,
                                vector("g2", "v1", "[]"),
                                candidates("g2", "v1", "[1,2]", "[2,3]"),
                                null,
                                vector("g3", "v1", "[]"),
                                candidates("g3", "v1", "[4]", "[]"),
                                null,
                                vector("g4", "v1", "[]"),
                                candidates("g4", "v1", "[5]", "[]")));

        var snapshot = reader.read(List.of(101L, 102L, 103L, 104L));

        assertThat(snapshot.members())
                .extracting(member -> member.userId())
                .containsExactly(101L, 102L, 103L, 104L);
        assertThat(snapshot.members().getFirst().legacyVector()).isEmpty();
        assertThat(snapshot.members().getFirst().candidateMovieIds()).hasSize(600);
        assertThat(snapshot.members().get(1).candidateMovieIds()).containsExactly(1L, 2L, 3L);
    }

    @Test
    void rejectsMissingOrMismatchedServingModel() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList()))
                .thenReturn(
                        java.util.Arrays.asList(
                                null,
                                null,
                                vector("g1", "v1", "[]"),
                                candidates("g1", "v1", "[1]", "[]")))
                .thenReturn(
                        Arrays.asList(
                                model(),
                                null,
                                vector("g1", "v2", "[]"),
                                candidates("g1", "v2", "[1]", "[]")));

        assertThatThrownBy(() -> reader.read(List.of(101L)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reader.read(List.of(101L)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readsGbtStateWithoutVectorAndSortsMembers() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList()))
                .thenReturn(
                        Arrays.asList(
                                gbtModel(),
                                state(101, "g1", INPUT_HASH),
                                null,
                                gbtCandidates("g1", INPUT_HASH, "[1,2]", "[2,3]"),
                                state(102, "g2", OTHER_INPUT_HASH),
                                null,
                                gbtCandidates("g2", OTHER_INPUT_HASH, "[4]", "[]")));

        var snapshot = reader.read(List.of(102L, 101L));

        assertThat(snapshot.isGbtState()).isTrue();
        assertThat(snapshot.modelVersion()).isEqualTo("gbt-v26");
        assertThat(snapshot.servingRevision()).isEqualTo("serving-r1");
        assertThat(snapshot.members())
                .extracting(PartyInputSnapshot.Member::userId)
                .containsExactly(101L, 102L);
        assertThat(snapshot.members().getFirst().legacyVector()).isNull();
        assertThat(snapshot.members().getFirst().inputHash()).isEqualTo(INPUT_HASH);
    }

    @Test
    void rejectsGbtStateCandidateMismatchAndMissingState() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList()))
                .thenReturn(
                        Arrays.asList(
                                gbtModel(),
                                state(101, "g1", INPUT_HASH),
                                null,
                                gbtCandidates("g2", INPUT_HASH, "[1]", "[]")))
                .thenReturn(
                        Arrays.asList(
                                gbtModel(),
                                null,
                                null,
                                gbtCandidates("g1", INPUT_HASH, "[1]", "[]")));

        assertThatThrownBy(() -> reader.read(List.of(101L)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reader.read(List.of(101L)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsGbtModelVersionOutsideCanonicalFormat() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList()))
                .thenReturn(
                        Arrays.asList(
                                "{\"modelKind\":\"GBT\",\"modelVersion\":\"GBT-V26\","
                                        + "\"servingRevision\":\"serving-r1\"}",
                                state(101, "g1", INPUT_HASH),
                                null,
                                gbtCandidates("g1", INPUT_HASH, "[1]", "[]")));

        assertThatThrownBy(() -> reader.read(List.of(101L)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static final String INPUT_HASH = "sha256:" + "a".repeat(64);
    private static final String OTHER_INPUT_HASH = "sha256:" + "b".repeat(64);

    private static String movieIds(long first, long last) {
        return LongStream.rangeClosed(first, last)
                .mapToObj(Long::toString)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String model() {
        return "{\"modelVersion\":\"v1\",\"factorDimension\":2}";
    }

    private static String gbtModel() {
        return "{\"modelKind\":\"GBT\",\"modelVersion\":\"gbt-v26\","
                + "\"servingRevision\":\"serving-r1\"}";
    }

    private static String state(long userId, String generation, String inputHash) {
        return "{\"stateVersion\":1,\"userId\":"
                + userId
                + ",\"modelKind\":\"GBT\",\"modelVersion\":\"gbt-v26\","
                + "\"servingRevision\":\"serving-r1\",\"generationId\":\""
                + generation
                + "\",\"inputSchemaVersion\":\"gbt-input-v1\",\"inputHash\":\""
                + inputHash
                + "\",\"ratingCount\":3,\"dismissCount\":1,"
                + "\"supportedInputCount\":4,\"updatedAt\":\"2026-09-23T00:00:00Z\"}";
    }

    private static String gbtCandidates(
            String generation, String inputHash, String taste, String discovery) {
        return "{\"modelVersion\":\"gbt-v26\",\"servingRevision\":\"serving-r1\","
                + "\"candidateVersion\":6,\"generationId\":\""
                + generation
                + "\",\"inputSchemaVersion\":\"gbt-input-v1\",\"inputHash\":\""
                + inputHash
                + "\",\"taste\":"
                + items(taste)
                + ",\"discovery\":"
                + items(discovery)
                + "}";
    }

    private static String vector(String generation, String model, String factors) {
        return "{\"modelVersion\":\""
                + model
                + "\",\"candidateVersion\":6,\"generationId\":\""
                + generation
                + "\",\"vector\":"
                + factors
                + "}";
    }

    private static String candidates(
            String generation, String model, String taste, String discovery) {
        return "{\"modelVersion\":\""
                + model
                + "\",\"candidateVersion\":6,\"generationId\":\""
                + generation
                + "\",\"taste\":"
                + items(taste)
                + ",\"discovery\":"
                + items(discovery)
                + "}";
    }

    private static String items(String ids) {
        return ids.replaceAll("(\\d+)", "{\"movieId\":$1}");
    }
}
