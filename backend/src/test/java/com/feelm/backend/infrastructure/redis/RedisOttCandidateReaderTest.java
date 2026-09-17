package com.feelm.backend.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

class RedisOttCandidateReaderTest {

    private static final String KEY = "feelm:user:7:candidates";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedisOttCandidateReader reader =
            new RedisOttCandidateReader(redis, new ObjectMapper());

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void readsTasteScoresWithoutDependingOnReasonShape() {
        when(values.get(KEY))
                .thenReturn(
                        """
                        {"candidateVersion":6,"generationId":"g-1","taste":[
                          {"movieId":11,"score":-0.25,"reasons":[{"code":"ALS"}]},
                          {"movieId":12,"score":0.0,"reasons":[]}
                        ],"discovery":[]}
                        """);

        var snapshot = reader.read(7);

        assertThat(snapshot.taste()).hasSize(2);
        assertThat(snapshot.taste().getFirst().movieId()).isEqualTo(11);
        assertThat(snapshot.taste().getFirst().score())
                .isEqualByComparingTo(new BigDecimal("-0.25"));
        assertThat(snapshot.taste().get(1).score()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void rejectsMissingOrDuplicateCandidates() {
        assertThatThrownBy(() -> reader.read(7)).isInstanceOf(BusinessException.class);

        when(values.get(KEY))
                .thenReturn(
                        """
                        {"candidateVersion":6,"generationId":"g-1","taste":[
                          {"movieId":11,"score":1},{"movieId":11,"score":0}
                        ]}
                        """);
        assertThatThrownBy(() -> reader.read(7)).isInstanceOf(BusinessException.class);
    }

    @Test
    void retriesOnceWhenCandidateAppearsAfterFirstRead() {
        when(values.get(KEY))
                .thenReturn(
                        null,
                        """
                        {"candidateVersion":6,"generationId":"g-1","taste":[
                          {"movieId":11,"score":0}
                        ]}
                        """);

        assertThat(reader.read(7).taste()).hasSize(1);
        verify(values, times(2)).get(KEY);
    }

    @Test
    void rejectsEmptyTasteAndUnsupportedVersionsAfterRetry() {
        for (String stored :
                new String[] {
                    """
            {"candidateVersion":6,"generationId":"g-1","taste":[]}
            """,
                    """
            {"candidateVersion":0,"generationId":"g-1","taste":[{"movieId":11,"score":0}]}
            """,
                    """
            {"candidateVersion":7,"generationId":"g-1","taste":[{"movieId":11,"score":0}]}
            """,
                    """
            {"candidateVersion":4294967302,"generationId":"g-1","taste":[{"movieId":11,"score":0}]}
            """
                }) {
            when(values.get(KEY)).thenReturn(stored);
            assertThatThrownBy(() -> reader.read(7))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(
                            exception ->
                                    assertThat(((BusinessException) exception).getErrorCode())
                                            .isEqualTo(ErrorCode.RECOMMENDATION_UNAVAILABLE));
        }
        verify(values, times(8)).get(KEY);
    }

    @Test
    void retriesWhenEmptyTasteIsReplaced() {
        when(values.get(KEY))
                .thenReturn(
                        """
                        {"candidateVersion":6,"generationId":"g-1","taste":[]}
                        """,
                        """
                        {"candidateVersion":6,"generationId":"g-2","taste":[{"movieId":11,"score":0}]}
                        """);

        assertThat(reader.read(7).taste()).hasSize(1);
        verify(values, times(2)).get(KEY);
    }
}
