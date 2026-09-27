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
    void readsPopularityModeAndRejectsMalformedModesAsUnavailable() throws Exception {
        var mapper = JsonMapper.builder().build();
        var fixture =
                mapper.readTree(getClass().getResourceAsStream("/gbt-personal-cold-state.json"));
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("feelm:model:current")).thenReturn(fixture.get("model").toString());
        var state =
                (tools.jackson.databind.node.ObjectNode)
                        fixture.get("cold").get("state").deepCopy();
        var candidate =
                (tools.jackson.databind.node.ObjectNode)
                        fixture.get("cold").get("candidates").deepCopy();
        when(values.get("feelm:user:58501:recommendation-state")).thenReturn(state.toString());
        when(values.get("feelm:user:58501:candidates")).thenReturn(candidate.toString());
        var reader = new RedisRecommendationCandidateReader(redis, mapper, 500, 100, true);
        assertThat(
                        reader.read(58501)
                                .items()
                                .getFirst()
                                .reason()
                                .get("recommendationMode")
                                .textValue())
                .isEqualTo("POPULARITY");
        for (var invalid :
                List.of(
                        mapper.nullNode(),
                        mapper.valueToTree(12),
                        mapper.createObjectNode(),
                        mapper.valueToTree("OTHER"))) {
            candidate.set("recommendationMode", invalid);
            when(values.get("feelm:user:58501:candidates")).thenReturn(candidate.toString());
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> reader.read(58501))
                    .isInstanceOf(com.feelm.backend.global.error.BusinessException.class);
        }
        candidate.put("recommendationMode", "PERSONALIZED");
        when(values.get("feelm:user:58501:candidates")).thenReturn(candidate.toString());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> reader.read(58501))
                .isInstanceOf(com.feelm.backend.global.error.BusinessException.class);
    }

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
