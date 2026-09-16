package com.feelm.backend.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.recommendation.model.RecommendationCandidateBatch;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.infrastructure.redis.RedisRecommendationCandidateReader;
import java.time.Instant;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Redis 개인 추천 후보 계약")
class RedisRecommendationCandidateReaderTest {

    private ValueOperations<String, String> values;
    private RedisRecommendationCandidateReader reader;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        values = org.mockito.Mockito.mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        reader = new RedisRecommendationCandidateReader(redis, new ObjectMapper(), 500, 100);
    }

    @Test
    @DisplayName("T1 taste와 discovery 배열에 종류를 붙여 하나의 후보 묶음으로 읽는다")
    void readsTasteAndDiscoveryFromExactUserKey() {
        when(values.get("feelm:user:10:candidates"))
                .thenReturn(
                        """
                        {
                          "modelVersion":"als-serving-example",
                          "candidateVersion":3,
                          "generationId":"als-20260916T083000Z-a1b2c3d4",
                          "updatedAt":"2026-09-16T08:30:00Z",
                          "taste":[{
                            "movieId":101,
                            "score":4.32,
                            "reasons":[{
                              "code":"SHARED_FEATURES_WITH_LIKED_MOVIE",
                              "sourceMovieId":55,
                              "sharedFeatures":{
                                "tmdbGenreIds":[878],
                                "tmdbDirectorIds":[525],
                                "tmdbCastIds":[],
                                "tmdbKeywordIds":[]
                              }
                            }]
                          }],
                          "discovery":[
                            {"movieId":202,"score":3.91,"flavorCode":"FLAVOR_MYSTERY","reasons":[]}
                          ]
                        }
                        """);

        RecommendationCandidateBatch result = reader.read(10);

        verify(values).get("feelm:user:10:candidates");
        assertThat(result.modelVersion()).isEqualTo("als-serving-example");
        assertThat(result.candidateVersion()).isEqualTo(3);
        assertThat(result.generationId()).isEqualTo("als-20260916T083000Z-a1b2c3d4");
        assertThat(result.updatedAt()).isEqualTo(Instant.parse("2026-09-16T08:30:00Z"));
        assertThat(result.items())
                .extracting(item -> item.movieId() + ":" + item.type() + ":" + item.flavorCode())
                .containsExactly("101:TASTE:null", "202:DISCOVERY:FLAVOR_MYSTERY");
        assertThat(
                        result.items()
                                .get(0)
                                .reason()
                                .get("reasons")
                                .get(0)
                                .get("sourceMovieId")
                                .asLong())
                .isEqualTo(55);
        assertThat(result.items().get(0).reason().toString())
                .contains("\"sharedFeatures\"", "\"tmdbGenreIds\":[878]");
        assertThat(result.items().get(1).reason().get("reasons")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "{broken-json"})
    @DisplayName("T2 없거나 깨진 Redis 값은 추천 불가로 처리한다")
    void rejectsMissingAndMalformedValues(String storedValue) {
        when(values.get("feelm:user:10:candidates")).thenReturn(storedValue);
        assertUnavailable(() -> reader.read(10));
    }

    @Test
    @DisplayName("T2 Redis 키가 없으면 추천 불가로 처리한다")
    void rejectsMissingKey() {
        when(values.get("feelm:user:10:candidates")).thenReturn(null);
        assertUnavailable(() -> reader.read(10));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                """
                {"modelVersion":"als","candidateVersion":3,"generationId":"",
                 "updatedAt":"2026-09-16T08:30:00Z","taste":[{"movieId":1,"score":4}],
                 "discovery":[]}
                """,
                """
                {"modelVersion":"als","candidateVersion":3,"generationId":"g1",
                 "updatedAt":"2026-09-16T08:30:00Z","taste":[],"discovery":[]}
                """,
                """
                {"modelVersion":"als","candidateVersion":3,"generationId":"g1",
                 "updatedAt":"2026-09-16T08:30:00Z","taste":[{"movieId":0,"score":4}],
                 "discovery":[]}
                """,
                """
                {"modelVersion":"als","candidateVersion":3,"generationId":"g1",
                 "updatedAt":"2026-09-16T08:30:00Z","taste":[],
                 "discovery":[{"movieId":2,"score":3,"flavorCode":null}]}
                """,
                """
                {"modelVersion":"als","candidateVersion":"3","generationId":"g1",
                 "updatedAt":"2026-09-16T08:30:00Z","taste":[{"movieId":1,"score":4}],
                 "discovery":[]}
                """,
                """
                {"modelVersion":"als","candidateVersion":3,"generationId":"g1",
                 "updatedAt":"2026-09-16T08:30:00Z","taste":[{"movieId":1.5,"score":4}],
                 "discovery":[]}
                """,
                """
                {"modelVersion":"als","candidateVersion":3,"generationId":"g1",
                 "updatedAt":"2026-09-16T08:30:00Z","taste":[{"movieId":1,"score":"4"}],
                 "discovery":[]}
                """,
                """
                {"modelVersion":"als","candidateVersion":4,"generationId":"g1",
                 "updatedAt":"2026-09-16T08:30:00Z","taste":[{"movieId":1,"score":4}],
                 "discovery":[]}
                """,
                """
                {"modelVersion":"als","candidateVersion":4,"generationId":"g1",
                 "updatedAt":"2026-09-16T08:30:00Z","taste":[{
                   "movieId":1,"score":4,"reasons":[{
                     "code":"SHARED_FEATURES_WITH_LIKED_MOVIE","sourceMovieId":2,
                     "sharedFeatures":{"tmdbGenreIds":[],"tmdbDirectorIds":[],"tmdbCastIds":[],"tmdbKeywordIds":[]}
                   }] }],"discovery":[]}
                """
            })
    @DisplayName("T3 계약에 맞지 않는 메타데이터와 후보는 전체를 거절한다")
    void rejectsInvalidMetadataAndItems(String json) {
        when(values.get("feelm:user:10:candidates")).thenReturn(json);
        assertUnavailable(() -> reader.read(10));
    }

    @Test
    @DisplayName("T3 TASTE 후보가 설정된 500개를 넘으면 전체를 거절한다")
    void rejectsMoreThanConfiguredTasteItems() {
        String taste =
                IntStream.rangeClosed(1, 501)
                        .mapToObj(
                                id -> "{\"movieId\":%d,\"score\":4.0,\"reasons\":[]}".formatted(id))
                        .collect(Collectors.joining(","));
        when(values.get("feelm:user:10:candidates"))
                .thenReturn(
                        """
                        {"modelVersion":"als","candidateVersion":3,"generationId":"g1",
                         "updatedAt":"2026-09-16T08:30:00Z","taste":[%s],"discovery":[]}
                        """
                                .formatted(taste));
        assertUnavailable(() -> reader.read(10));
    }

    @Test
    @DisplayName("T3 DISCOVERY 후보가 설정된 100개를 넘으면 전체를 거절한다")
    void rejectsMoreThanConfiguredDiscoveryItems() {
        String discovery =
                IntStream.rangeClosed(1, 101)
                        .mapToObj(
                                id ->
                                        "{\"movieId\":%d,\"score\":4.0,\"flavorCode\":\"F\",\"reasons\":[]}"
                                                .formatted(id))
                        .collect(Collectors.joining(","));
        when(values.get("feelm:user:10:candidates"))
                .thenReturn(
                        """
                        {"modelVersion":"als","candidateVersion":3,"generationId":"g1",
                         "updatedAt":"2026-09-16T08:30:00Z","taste":[],"discovery":[%s]}
                        """
                                .formatted(discovery));
        assertUnavailable(() -> reader.read(10));
    }

    @Test
    @DisplayName("T3 TASTE 500개와 DISCOVERY 100개까지는 허용한다")
    void acceptsConfiguredMaximumForEachType() {
        String taste =
                IntStream.rangeClosed(1, 500)
                        .mapToObj(
                                id -> "{\"movieId\":%d,\"score\":4.0,\"reasons\":[]}".formatted(id))
                        .collect(Collectors.joining(","));
        String discovery =
                IntStream.rangeClosed(501, 600)
                        .mapToObj(
                                id ->
                                        "{\"movieId\":%d,\"score\":4.0,\"flavorCode\":\"F\",\"reasons\":[]}"
                                                .formatted(id))
                        .collect(Collectors.joining(","));
        when(values.get("feelm:user:10:candidates"))
                .thenReturn(
                        """
                        {"modelVersion":"als","candidateVersion":3,"generationId":"g1",
                         "updatedAt":"2026-09-16T08:30:00Z","taste":[%s],"discovery":[%s]}
                        """
                                .formatted(taste, discovery));

        assertThat(reader.read(10).items()).hasSize(600);
    }

    @Test
    @DisplayName("T4 두 배열에 같은 영화가 들어오면 전체를 거절한다")
    void rejectsDuplicateMovieIdsAcrossTypes() {
        when(values.get("feelm:user:10:candidates"))
                .thenReturn(
                        """
                        {"modelVersion":"als","candidateVersion":3,"generationId":"g1",
                         "updatedAt":"2026-09-16T08:30:00Z",
                         "taste":[{"movieId":101,"score":4.32,"reasons":[]}],
                         "discovery":[{"movieId":101,"score":3.2,"flavorCode":"FLAVOR_A","reasons":[]}]}
                        """);
        assertUnavailable(() -> reader.read(10));
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.RECOMMENDATION_UNAVAILABLE);
    }
}
