package com.feelm.backend.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                        List.of(
                                model(),
                                vector("g1", "v1", "[0.1,0.2]"),
                                candidates("g1", "v1", "[1,2]", "[2,3]")));

        var snapshot = reader.read(List.of(1024L));

        assertThat(snapshot.modelVersion()).isEqualTo("v1");
        assertThat(snapshot.members().getFirst().vector()).containsExactly(0.1, 0.2);
        assertThat(snapshot.members().getFirst().candidateMovieIds()).containsExactly(1L, 2L, 3L);
        verify(values)
                .multiGet(
                        List.of(
                                "feelm:model:current",
                                "feelm:user:1024:vector",
                                "feelm:user:1024:candidates"));
    }

    @Test
    void rejectsMixedGenerationAndModel() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList()))
                .thenReturn(
                        List.of(
                                model(),
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
                                model(), null, candidates("g1", "v1", "[1]", "[]")));

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
                        List.of(
                                model(),
                                vector("g1", "v1", "[]"),
                                candidates("g1", "v1", taste, discovery),
                                vector("g2", "v1", "[]"),
                                candidates("g2", "v1", "[1,2]", "[2,3]"),
                                vector("g3", "v1", "[]"),
                                candidates("g3", "v1", "[4]", "[]"),
                                vector("g4", "v1", "[]"),
                                candidates("g4", "v1", "[5]", "[]")));

        var snapshot = reader.read(List.of(101L, 102L, 103L, 104L));

        assertThat(snapshot.members())
                .extracting(member -> member.userId())
                .containsExactly(101L, 102L, 103L, 104L);
        assertThat(snapshot.members().getFirst().vector()).isEmpty();
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
                                vector("g1", "v1", "[]"),
                                candidates("g1", "v1", "[1]", "[]")))
                .thenReturn(
                        List.of(
                                model(),
                                vector("g1", "v2", "[]"),
                                candidates("g1", "v2", "[1]", "[]")));

        assertThatThrownBy(() -> reader.read(List.of(101L)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reader.read(List.of(101L)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static String movieIds(long first, long last) {
        return LongStream.rangeClosed(first, last)
                .mapToObj(Long::toString)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String model() {
        return "{\"modelVersion\":\"v1\",\"factorDimension\":2}";
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
