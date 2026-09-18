package com.feelm.backend.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

class RedisPartyCandidateReaderTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedisPartyCandidateReader reader =
            new RedisPartyCandidateReader(redis, JsonMapper.builder().build());

    @Test
    void readsValidMeanFitV2SnapshotAndIgnoresFallback() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("feelm:party:9001:candidates"))
                .thenReturn(
                        """
                        {
                          "partyId":9001,"modelVersion":"v1",
                          "algorithmVersion":"party-mean-fit-v2","inputId":"sha256:abc",
                          "generatedAt":"2026-09-17T05:21:00Z",
                          "candidates":[{
                            "movieId":550,"partyScore":0.8,"meanFit":0.8,
                            "minFit":0.7,"maxFit":0.9,"stddevFit":0.1,
                            "memberFit":[{"userId":10,"score":0.9},{"userId":20,"score":0.7}]
                          }],
                          "fallback":{"missingItemFactorMovieIds":[550],"emptyVectorUserIds":[]}
                        }
                        """);

        var snapshot = reader.read(9001);

        assertThat(snapshot.partyId()).isEqualTo(9001);
        assertThat(snapshot.candidates().getFirst().movieId()).isEqualTo(550);
        verify(values).get("feelm:party:9001:candidates");
    }

    @Test
    void acceptsAnotherAlgorithmVersionWithoutInterpretingScores() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("feelm:party:9001:candidates"))
                .thenReturn(
                        """
                        {
                          "partyId":9001,"modelVersion":"v1",
                          "algorithmVersion":"party-balanced-fit-v3","inputId":"sha256:abc",
                          "generatedAt":"2026-09-17T05:21:00Z",
                          "candidates":[{
                            "movieId":550,"partyScore":0.75,"meanFit":0.8,
                            "minFit":0.7,"maxFit":0.9,"stddevFit":0.1,
                            "memberFit":[{"userId":10,"score":0.9},{"userId":20,"score":0.7}]
                          }]
                        }
                        """);

        var snapshot = reader.read(9001);

        assertThat(snapshot.algorithmVersion()).isEqualTo("party-balanced-fit-v3");
        assertThat(snapshot.candidates().getFirst().movieId()).isEqualTo(550);
    }

    @Test
    void rejectsWrongPartyAndDuplicateMovies() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("feelm:party:9001:candidates"))
                .thenReturn(payload(9002, "party-mean-fit-v2", "0.8", "550"))
                .thenReturn(
                        payload(9001, "party-mean-fit-v2", "0.8", "550")
                                .replace("]}", ",{\"movieId\":550,\"partyScore\":0.1}]}"));

        assertThatThrownBy(() -> reader.read(9001)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reader.read(9001)).isInstanceOf(IllegalStateException.class);
    }

    private static String payload(long partyId, String algorithm, String score, String movieId) {
        return "{\"partyId\":"
                + partyId
                + ",\"modelVersion\":\"v1\",\"algorithmVersion\":\""
                + algorithm
                + "\",\"inputId\":\"sha256:abc\",\"generatedAt\":\"2026-09-17T05:21:00Z\","
                + "\"candidates\":[{\"movieId\":"
                + movieId
                + ",\"partyScore\":"
                + score
                + ",\"meanFit\":"
                + score
                + ",\"minFit\":0.7,\"maxFit\":0.9,\"stddevFit\":0.1,"
                + "\"memberFit\":[{\"userId\":10,\"score\":0.8}]}]}";
    }
}
