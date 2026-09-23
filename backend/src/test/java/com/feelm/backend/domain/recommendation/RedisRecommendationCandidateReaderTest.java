package com.feelm.backend.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
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

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private RedisRecommendationCandidateReader reader;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        values = org.mockito.Mockito.mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("feelm:user:10:vector"))
                .thenReturn(
                        """
                        {"modelVersion":"als","candidateVersion":6,
                         "generationId":"g1"}
                        """);
        reader = new RedisRecommendationCandidateReader(redis, new ObjectMapper(), 500, 100, true);
    }

    @Test
    @DisplayName("T1 taste와 discovery 배열에 종류를 붙여 하나의 후보 묶음으로 읽는다")
    void readsTasteAndDiscoveryFromExactUserKey() {
        when(values.get("feelm:user:10:vector"))
                .thenReturn(
                        """
                        {"modelVersion":"als-serving-example","candidateVersion":6,
                         "generationId":"als-20260916T083000Z-a1b2c3d4"}
                        """);
        when(values.get("feelm:user:10:candidates"))
                .thenReturn(
                        """
                        {
                          "modelVersion":"als-serving-example",
                          "candidateVersion":6,
                          "generationId":"als-20260916T083000Z-a1b2c3d4",
                          "updatedAt":"2026-09-16T08:30:00Z",
                          "taste":[{
                            "movieId":101,
                            "score":4.32,
                            "reasons":[{
                              "code":"SHARED_GENRE",
                              "sourceMovieIds":[55],
                              "matchedGenreCodes":["THRILLER"]
                            }]
                          }],
                          "discovery":[
                            {"movieId":202,"score":3.91,"reasons":[{
                              "code":"DISCOVERY_AGGREGATE_AFFINITY",
                              "directionSource":"RATING",
                              "contributorCount":12,
                              "topContributors":[
                                {"movieId":55,"relation":"POSITIVE_ALIGNMENT"},
                                {"movieId":77,"relation":"NEGATIVE_CONTRAST"}
                              ],
                              "topContributionCoverage":0.43,
                              "viewedCountInGroup":0,
                              "affinityRank":2,
                              "popularityRankInGroup":1
                            }]},
                            {"movieId":203,"score":3.82,"reasons":[{
                              "code":"DISCOVERY_PERSONAL_SCORE",
                              "sourceMovieIds":[55],
                              "matchedGenreCodes":["THRILLER"]
                            }]},
                            {"movieId":204,"score":3.7,"reasons":[]}
                          ]
                        }
                        """);

        RecommendationCandidateBatch result = reader.read(10);

        verify(values).get("feelm:user:10:candidates");
        assertThat(result.modelVersion()).isEqualTo("als-serving-example");
        assertThat(result.candidateVersion()).isEqualTo(6);
        assertThat(result.generationId()).isEqualTo("als-20260916T083000Z-a1b2c3d4");
        assertThat(result.updatedAt()).isEqualTo(Instant.parse("2026-09-16T08:30:00Z"));
        assertThat(result.items())
                .extracting(item -> item.movieId() + ":" + item.type() + ":" + item.flavorCode())
                .containsExactly(
                        "101:TASTE:null",
                        "202:DISCOVERY:null",
                        "203:DISCOVERY:null",
                        "204:DISCOVERY:null");
        assertThat(
                        result.items()
                                .get(0)
                                .reason()
                                .get("reasons")
                                .get(0)
                                .get("sourceMovieIds")
                                .get(0)
                                .asLong())
                .isEqualTo(55);
        assertThat(result.items().get(0).reason().toString())
                .contains("\"SHARED_GENRE\"", "\"matchedGenreCodes\":[\"THRILLER\"]");
        assertThat(result.items().get(1).reason().get("reasons").get(0).toString())
                .isEqualTo(
                        "{\"code\":\"DISCOVERY_AGGREGATE_AFFINITY\","
                                + "\"directionSource\":\"RATING\",\"contributorCount\":12,"
                                + "\"topContributors\":[{\"movieId\":55,"
                                + "\"relation\":\"POSITIVE_ALIGNMENT\"},{\"movieId\":77,"
                                + "\"relation\":\"NEGATIVE_CONTRAST\"}],"
                                + "\"topContributionCoverage\":0.43,\"viewedCountInGroup\":0,"
                                + "\"affinityRank\":2,\"popularityRankInGroup\":1}");
        assertThat(result.items().get(2).reason().get("reasons").get(0).toString())
                .isEqualTo(
                        "{\"code\":\"DISCOVERY_PERSONAL_SCORE\",\"sourceMovieIds\":[55],"
                                + "\"matchedGenreCodes\":[\"THRILLER\"]}");
        assertThat(result.items().get(3).reason().get("reasons")).isEmpty();
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
                {"modelVersion":"als","candidateVersion":6,"generationId":"",
                 "updatedAt":"2026-09-16T08:30:00Z","taste":[{"movieId":1,"score":4}],
                 "discovery":[]}
                """,
                """
                {"modelVersion":"als","candidateVersion":6,"generationId":"g1",
                 "updatedAt":"2026-09-16T08:30:00Z","taste":[],"discovery":[]}
                """,
                """
                {"modelVersion":"als","candidateVersion":6,"generationId":"g1",
                 "updatedAt":"2026-09-16T08:30:00Z","taste":[{"movieId":0,"score":4}],
                 "discovery":[]}
                """,
                """
                {"modelVersion":"als","candidateVersion":6,"generationId":"g1",
                 "updatedAt":"2026-09-16T08:30:00Z","taste":[],
                 "discovery":[{"movieId":2,"score":3}]}
                """,
                """
                {"modelVersion":"als","candidateVersion":"6","generationId":"g1",
                 "updatedAt":"2026-09-16T08:30:00Z","taste":[{"movieId":1,"score":4}],
                 "discovery":[]}
                """,
                """
                {"modelVersion":"als","candidateVersion":4,"generationId":"g1",
                 "updatedAt":"2026-09-16T08:30:00Z",
                 "taste":[{"movieId":1,"score":4,"reasons":[]}],"discovery":[]}
                """,
                """
                {"modelVersion":"als","candidateVersion":6,"generationId":"g1",
                 "updatedAt":"2026-09-16T08:30:00Z","taste":[{"movieId":1.5,"score":4}],
                 "discovery":[]}
                """,
                """
                {"modelVersion":"als","candidateVersion":6,"generationId":"g1",
                 "updatedAt":"2026-09-16T08:30:00Z","taste":[{"movieId":1,"score":"4"}],
                 "discovery":[]}
                """,
                """
                {"modelVersion":"als","candidateVersion":6,"generationId":"g1",
                 "updatedAt":"2026-09-16T08:30:00Z","taste":[{"movieId":1,"score":4}],
                 "discovery":[]}
                """,
                """
                {"modelVersion":"als","candidateVersion":6,"generationId":"g1",
                 "updatedAt":"2026-09-16T08:30:00Z","taste":[{
                   "movieId":1,"score":4,"reasons":[{
                     "code":"SHARED_FEATURES_WITH_LIKED_MOVIE","sourceMovieId":2,
                     "sharedFeatures":{"tmdbGenreIds":[],"tmdbDirectorIds":[],"tmdbCastIds":[],"tmdbKeywordIds":[]}
                   }] }],"discovery":[]}
                """,
                """
                {"modelVersion":"als","candidateVersion":6,"generationId":"g1",
                 "updatedAt":"2026-09-16T08:30:00Z","taste":[],"discovery":[{
                   "movieId":2,"score":3,"reasons":{
                     "code":"DISCOVERY_PERSONAL_SCORE","sourceMovieIds":[1],"matchedGenreCodes":[]
                   }}]}
                """,
                """
                {"modelVersion":"als","candidateVersion":6,"generationId":"g1",
                 "updatedAt":"2026-09-16T08:30:00Z","taste":[],"discovery":[{
                   "movieId":2,"score":3,"reasons":[{
                     "code":"DISCOVERY_AGGREGATE_AFFINITY","directionSource":"RATING",
                     "contributorCount":1,"topContributors":[{"movieId":1,"relation":"UNKNOWN"}],
                     "topContributionCoverage":0.5,"viewedCountInGroup":0,
                     "affinityRank":1,"popularityRankInGroup":1
                   }]}]}
                """,
                """
                {"modelVersion":"als","candidateVersion":6,"generationId":"g1",
                 "updatedAt":"2026-09-16T08:30:00Z","taste":[],"discovery":[{
                   "movieId":2,"score":3,"reasons":[{
                     "code":"DISCOVERY_PERSONAL_SCORE","sourceMovieIds":[],
                     "matchedGenreCodes":["THRILLER"]
                   }]}]}
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
                        {"modelVersion":"als","candidateVersion":6,"generationId":"g1",
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
                                id -> "{\"movieId\":%d,\"score\":4.0,\"reasons\":[]}".formatted(id))
                        .collect(Collectors.joining(","));
        when(values.get("feelm:user:10:candidates"))
                .thenReturn(
                        """
                        {"modelVersion":"als","candidateVersion":6,"generationId":"g1",
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
                                id -> "{\"movieId\":%d,\"score\":4.0,\"reasons\":[]}".formatted(id))
                        .collect(Collectors.joining(","));
        when(values.get("feelm:user:10:candidates"))
                .thenReturn(
                        """
                        {"modelVersion":"als","candidateVersion":6,"generationId":"g1",
                         "updatedAt":"2026-09-16T08:30:00Z","taste":[%s],"discovery":[%s]}
                        """
                                .formatted(taste, discovery));

        assertThat(reader.read(10).items()).hasSize(600);
    }

    @Test
    @DisplayName("T4 DISCOVERY의 TASTE 중복 보충 후보를 허용한다")
    void acceptsDuplicateMovieIdsAcrossTypes() {
        when(values.get("feelm:user:10:candidates"))
                .thenReturn(
                        """
                        {"modelVersion":"als","candidateVersion":6,"generationId":"g1",
                         "updatedAt":"2026-09-16T08:30:00Z",
                         "taste":[{"movieId":101,"score":4.32,"reasons":[]}],
                         "discovery":[{"movieId":101,"score":3.2,"reasons":[]}]}
                        """);
        assertThat(reader.read(10).items()).hasSize(2);
    }

    @Test
    @DisplayName("전환 기간에는 실제 v5 단일 reason 형식의 TASTE와 DISCOVERY를 읽는다")
    void readsV5DuringTransition() {
        when(values.get("feelm:user:10:vector"))
                .thenReturn(
                        "{\"modelVersion\":\"als\",\"candidateVersion\":5,\"generationId\":\"g1\"}");
        when(values.get("feelm:user:10:candidates"))
                .thenReturn(
                        """
                        {"modelVersion":"als","candidateVersion":5,"generationId":"g1",
                         "updatedAt":"2026-09-16T08:30:00Z",
                         "taste":[{"movieId":101,"score":4.32,"reason":{
                           "code":"TOP_RATED_SOURCE","sourceMovieIds":[55],"matchedGenreCodes":[]
                         }}],"discovery":[{"movieId":202,"score":3.91,"reason":{
                           "code":"DISCOVERY_AGGREGATE_AFFINITY","directionSource":"RATING",
                           "contributorCount":1,"topContributors":[{"movieId":55,
                           "relation":"POSITIVE_ALIGNMENT"}],"topContributionCoverage":0.5,
                           "viewedCountInGroup":0,"affinityRank":1,"popularityRankInGroup":1
                         }}]}
                        """);

        RecommendationCandidateBatch result = reader.read(10);
        assertThat(result.candidateVersion()).isEqualTo(5);
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).reason().path("reasons").get(0).path("code").textValue())
                .isEqualTo("TOP_RATED_SOURCE");
        assertThat(result.items().get(1).reason().path("reasons").get(0).path("code").textValue())
                .isEqualTo("DISCOVERY_AGGREGATE_AFFINITY");
        assertThat(result.items().get(1).reason().path("reasons").get(0).path("topContributors"))
                .hasSize(1);
    }

    @Test
    @DisplayName("v5 번호에 v6 배열을 붙인 가짜 전환 후보는 거절한다")
    void rejectsV6ReasonShapeLabeledV5() {
        when(values.get("feelm:user:10:vector"))
                .thenReturn(
                        "{\"modelVersion\":\"als\",\"candidateVersion\":5,\"generationId\":\"g1\"}");
        when(values.get("feelm:user:10:candidates"))
                .thenReturn(
                        """
                        {"modelVersion":"als","candidateVersion":5,"generationId":"g1",
                         "updatedAt":"2026-09-16T08:30:00Z",
                         "taste":[{"movieId":101,"score":4.32,"reasons":[{
                           "code":"TOP_RATED_SOURCE","sourceMovieIds":[55],"matchedGenreCodes":[]
                         }]}],"discovery":[]}
                        """);
        assertUnavailable(() -> reader.read(10));
    }

    @Test
    @DisplayName("v6 번호에 v5 단일 reason을 붙인 후보는 거절한다")
    void rejectsV5ReasonShapeLabeledV6() {
        when(values.get("feelm:user:10:candidates"))
                .thenReturn(
                        """
                        {"modelVersion":"als","candidateVersion":6,"generationId":"g1",
                         "updatedAt":"2026-09-16T08:30:00Z",
                         "taste":[{"movieId":101,"score":4.32,"reason":{
                           "code":"TOP_RATED_SOURCE","sourceMovieIds":[55],"matchedGenreCodes":[]
                         }}],"discovery":[]}
                        """);
        assertUnavailable(() -> reader.read(10));
    }

    @Test
    @DisplayName("v5 호환 제거 설정 후에는 v5를 거절한다")
    void rejectsV5AfterTransition() {
        reader = new RedisRecommendationCandidateReader(redis, new ObjectMapper(), 500, 100, false);
        when(values.get("feelm:user:10:candidates"))
                .thenReturn(
                        """
                        {"modelVersion":"als","candidateVersion":5,"generationId":"g1",
                         "updatedAt":"2026-09-16T08:30:00Z",
                         "taste":[{"movieId":101,"score":4.32,"reason":{
                           "code":"TOP_RATED_SOURCE","sourceMovieIds":[55],"matchedGenreCodes":[]
                         }}],"discovery":[]}
                        """);
        assertUnavailable(() -> reader.read(10));
    }

    @Test
    @DisplayName("후보와 벡터의 generation ID가 다르면 추천 불가로 처리한다")
    void rejectsGenerationMismatch() {
        when(values.get("feelm:user:10:vector"))
                .thenReturn(
                        "{\"modelVersion\":\"als\",\"candidateVersion\":6,\"generationId\":\"other\"}");
        when(values.get("feelm:user:10:candidates"))
                .thenReturn(
                        """
                        {"modelVersion":"als","candidateVersion":6,"generationId":"g1",
                         "updatedAt":"2026-09-16T08:30:00Z",
                         "taste":[{"movieId":101,"score":4.32,"reasons":[]}],"discovery":[]}
                        """);

        assertUnavailable(() -> reader.read(10));
    }

    @Test
    @DisplayName("vector와 후보가 잠시 어긋나면 다시 읽어 일치하는 generation을 수용한다")
    void retriesTransientGenerationMismatch() {
        when(values.get("feelm:user:10:vector"))
                .thenReturn(
                        "{\"modelVersion\":\"als\",\"candidateVersion\":6,\"generationId\":\"old\"}",
                        "{\"modelVersion\":\"als\",\"candidateVersion\":6,\"generationId\":\"g1\"}");
        when(values.get("feelm:user:10:candidates"))
                .thenReturn(
                        """
                        {"modelVersion":"als","candidateVersion":6,"generationId":"g1",
                         "updatedAt":"2026-09-16T08:30:00Z",
                         "taste":[{"movieId":101,"score":4.32,"reasons":[]}],"discovery":[]}
                        """);

        assertThat(reader.read(10).generationId()).isEqualTo("g1");
        verify(values, times(2)).get("feelm:user:10:candidates");
    }

    @Test
    @DisplayName("v6의 근거 없음 fallback은 빈 source와 장르 배열로도 읽는다")
    void acceptsEmptyEvidenceArrays() {
        when(values.get("feelm:user:10:candidates"))
                .thenReturn(
                        """
                        {"modelVersion":"als","candidateVersion":6,"generationId":"g1",
                         "updatedAt":"2026-09-16T08:30:00Z",
                         "taste":[{"movieId":101,"score":0,"reasons":[{
                           "code":"TOP_RATED_SOURCE","sourceMovieIds":[],"matchedGenreCodes":[]
                         }]}],
                         "discovery":[{"movieId":102,"score":0,"reasons":[{
                           "code":"DISCOVERY_PERSONAL_SCORE","sourceMovieIds":[],
                           "matchedGenreCodes":[]
                         }]}]}
                        """);

        assertThat(reader.read(10).items()).hasSize(2);
    }

    @Test
    @DisplayName("v6 TOP_RATED_SOURCE의 구조화 근거를 원형대로 보존한다")
    void preservesTopRatedSourceReason() {
        when(values.get("feelm:user:10:candidates"))
                .thenReturn(
                        """
                        {"modelVersion":"als","candidateVersion":6,"generationId":"g1",
                         "updatedAt":"2026-09-16T08:30:00Z",
                         "taste":[{"movieId":101,"score":4,"reasons":[{
                           "code":"TOP_RATED_SOURCE","sourceMovieIds":[55],
                           "matchedGenreCodes":[]
                         }]}],"discovery":[]}
                        """);

        assertThat(reader.read(10).items().getFirst().reason().get("reasons").get(0).toString())
                .isEqualTo(
                        "{\"code\":\"TOP_RATED_SOURCE\",\"sourceMovieIds\":[55],"
                                + "\"matchedGenreCodes\":[]}");
    }

    @Test
    @DisplayName("GBT Candidate는 vector 없이 같은 추천 상태 세대로 읽는다")
    void readsGbtCandidatesWithoutVector() {
        when(values.get("feelm:user:10:vector")).thenReturn(null);
        when(values.get("feelm:model:current")).thenReturn(gbtCurrent("serving-r1"));
        when(values.get("feelm:user:10:recommendation-state")).thenReturn(gbtState("g1", HASH));
        when(values.get("feelm:user:10:candidates")).thenReturn(gbtCandidates("g1", HASH));

        RecommendationCandidateBatch result = reader.read(10);

        assertThat(result.modelVersion()).isEqualTo("gbt-v26");
        assertThat(result.generationId()).isEqualTo("g1");
        assertThat(result.items()).extracting(item -> item.movieId()).containsExactly(101L);
        verify(values, times(0)).get("feelm:user:10:vector");
    }

    @Test
    @DisplayName("GBT 상태와 Candidate의 입력 hash가 다르면 추천 불가로 처리한다")
    void rejectsGbtInputMismatch() {
        when(values.get("feelm:model:current")).thenReturn(gbtCurrent("serving-r1"));
        when(values.get("feelm:user:10:recommendation-state")).thenReturn(gbtState("g1", HASH));
        when(values.get("feelm:user:10:candidates"))
                .thenReturn(gbtCandidates("g1", "sha256:" + "b".repeat(64)));

        assertUnavailable(() -> reader.read(10));
    }

    @Test
    @DisplayName("GBT 메타데이터가 있는데 추천 상태가 없으면 legacy vector로 대체하지 않는다")
    void rejectsMissingGbtStateWithoutLegacyFallback() {
        when(values.get("feelm:model:current")).thenReturn(gbtCurrent("serving-r1"));
        when(values.get("feelm:user:10:recommendation-state")).thenReturn(null);
        when(values.get("feelm:user:10:candidates")).thenReturn(gbtCandidates("g1", HASH));

        assertUnavailable(() -> reader.read(10));
        verify(values, times(0)).get("feelm:user:10:vector");
    }

    @Test
    @DisplayName("GBT 상태만 게시된 부분 세대도 legacy vector로 대체하지 않는다")
    void rejectsStateWithLegacyShapedCandidates() {
        when(values.get("feelm:model:current")).thenReturn(gbtCurrent("serving-r1"));
        when(values.get("feelm:user:10:recommendation-state")).thenReturn(gbtState("g1", HASH));
        when(values.get("feelm:user:10:candidates"))
                .thenReturn(
                        """
                        {"modelVersion":"gbt-v26","candidateVersion":6,"generationId":"g1",
                         "updatedAt":"2026-09-23T00:00:00Z",
                         "taste":[{"movieId":101,"score":4.2,"reasons":[]}],"discovery":[]}
                        """);

        assertUnavailable(() -> reader.read(10));
        verify(values, times(0)).get("feelm:user:10:vector");
    }

    @Test
    @DisplayName("현재 serving revision과 다른 GBT 세대는 추천 불가로 처리한다")
    void rejectsGbtStateFromStaleServingRevision() {
        when(values.get("feelm:model:current")).thenReturn(gbtCurrent("serving-r2"));
        when(values.get("feelm:user:10:recommendation-state")).thenReturn(gbtState("g1", HASH));
        when(values.get("feelm:user:10:candidates")).thenReturn(gbtCandidates("g1", HASH));

        assertUnavailable(() -> reader.read(10));
        verify(values, times(0)).get("feelm:user:10:vector");
    }

    @Test
    @DisplayName("GBT 활성 중에는 완전한 legacy Candidate와 vector도 fallback하지 않는다")
    void rejectsLegacyCandidatesWhenGbtModelIsCurrent() {
        when(values.get("feelm:model:current")).thenReturn(gbtCurrent("serving-r1"));
        when(values.get("feelm:user:10:recommendation-state")).thenReturn(null);
        when(values.get("feelm:user:10:candidates"))
                .thenReturn(
                        """
                        {"modelVersion":"als","candidateVersion":6,"generationId":"g1",
                         "updatedAt":"2026-09-23T00:00:00Z",
                         "taste":[{"movieId":101,"score":4.2,"reasons":[]}],"discovery":[]}
                        """);

        assertUnavailable(() -> reader.read(10));
        verify(values, times(0)).get("feelm:user:10:vector");
    }

    private static final String HASH = "sha256:" + "a".repeat(64);

    private static String gbtCurrent(String servingRevision) {
        return """
                {"modelKind":"GBT","modelVersion":"gbt-v26","servingRevision":"%s"}
                """
                .formatted(servingRevision);
    }

    private static String gbtState(String generation, String inputHash) {
        return """
                {"stateVersion":1,"userId":10,"modelKind":"GBT",
                 "modelVersion":"gbt-v26","servingRevision":"serving-r1",
                 "generationId":"%s","inputSchemaVersion":"gbt-input-v1",
                 "inputHash":"%s","ratingCount":3,"dismissCount":1,
                 "supportedInputCount":4,"updatedAt":"2026-09-23T00:00:00Z"}
                """
                .formatted(generation, inputHash);
    }

    private static String gbtCandidates(String generation, String inputHash) {
        return """
                {"modelVersion":"gbt-v26","servingRevision":"serving-r1",
                 "candidateVersion":6,"generationId":"%s",
                 "inputSchemaVersion":"gbt-input-v1","inputHash":"%s",
                 "updatedAt":"2026-09-23T00:00:00Z",
                 "taste":[{"movieId":101,"score":4.2,"reasons":[]}],"discovery":[]}
                """
                .formatted(generation, inputHash);
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.RECOMMENDATION_UNAVAILABLE);
    }
}
