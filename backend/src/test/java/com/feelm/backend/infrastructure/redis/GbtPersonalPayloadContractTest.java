package com.feelm.backend.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

class GbtPersonalPayloadContractTest {
    @Test
    void readsPayloadProducedByPersonalGbtWithoutAnyAlsVector() throws Exception {
        var mapper = JsonMapper.builder().build();
        var fixture = mapper.readTree(getClass().getResourceAsStream("/gbt-personal-state.json"));
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList()))
                .thenReturn(
                        Arrays.asList(
                                fixture.get("model").toString(),
                                fixture.get("state").toString(),
                                null,
                                fixture.get("candidates").toString()));

        var snapshot = new RedisPartyInputReader(redis, mapper).read(List.of(10L));

        assertThat(snapshot.isGbtState()).isTrue();
        assertThat(snapshot.schemaVersion()).isEqualTo(2);
        assertThat(snapshot.modelVersion()).isEqualTo("gbt-test627");
        var member = snapshot.members().getFirst();
        assertThat(member.legacyVector()).isNull();
        assertThat(member.generationId()).isEqualTo("g1");
        assertThat(member.inputSchemaVersion()).isEqualTo("gbt-input-v1");
        assertThat(member.inputHash()).isEqualTo(fixture.get("state").get("inputHash").asText());
        assertThat(member.candidateMovieIds()).containsExactlyInAnyOrder(3L, 4L, 5L);
    }
}
