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
import java.util.List;
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
    @DisplayName("GBT Candidate와 결속된 evidence v1을 항목 내부 스냅샷으로 보존한다")
    void readsBoundGbtEvidenceSnapshot() {
        when(values.get("feelm:model:current")).thenReturn(gbtCurrent("serving-r1"));
        when(values.get("feelm:user:10:recommendation-state")).thenReturn(gbtState("g1", HASH));
        when(values.get("feelm:user:10:candidates")).thenReturn(gbtCandidates("g1", HASH));
        when(values.get("feelm:user:10:gbt-evidence:g1")).thenReturn(gbtEvidence("g1", HASH));

        var reason = reader.read(10).items().getFirst().reason();

        assertThat(reason.path("modelKind").textValue()).isEqualTo("GBT");
        assertThat(reason.path("modelVersion").textValue()).isEqualTo("gbt-v26");
        assertThat(reason.path("rankingVersion").textValue())
                .isEqualTo("gbt-personal-raw-score-v1");
        assertThat(reason.path("explanationVersion").textValue())
                .isEqualTo("gbt-feature-attribution-v1");
        assertThat(reason.path("evidence").path("userId").longValue()).isEqualTo(10);
        assertThat(reason.path("evidence").path("generationId").textValue()).isEqualTo("g1");
        assertThat(reason.path("evidence").path("servingRevision").textValue())
                .isEqualTo("serving-r1");
        assertThat(reason.path("evidence").path("featureVersion").textValue())
                .isEqualTo("v26-max70-kobis-flag-71-v1");
        assertThat(reason.path("evidence").path("inputVersion").textValue()).isEqualTo(HASH);
        assertThat(reason.path("evidence").path("candidateVersion").longValue()).isEqualTo(6);
        assertThat(reason.path("evidence").path("item").path("movieId").longValue()).isEqualTo(101);
        assertThat(reason.path("evidence").path("item").path("score").decimalValue())
                .isEqualByComparingTo("4.2");
        assertThat(reason.path("evidence").path("item").path("coverage").decimalValue())
                .isEqualByComparingTo("0.7");
        assertThat(
                        reason.path("evidence")
                                .path("item")
                                .path("evidence")
                                .get(0)
                                .path("group")
                                .textValue())
                .isEqualTo("GENRE_PATTERN");
        assertThat(
                        reason.path("evidence")
                                .path("item")
                                .path("evidence")
                                .get(0)
                                .path("contribution")
                                .decimalValue())
                .isEqualByComparingTo("0.4");
    }

    @Test
    @DisplayName("GBT evidence v2의 허용된 구체 장르와 원천 영화 필드를 보존한다")
    void readsBoundGbtEvidenceV2SpecificSources() {
        when(values.get("feelm:model:current")).thenReturn(gbtCurrent("serving-r1"));
        when(values.get("feelm:user:10:recommendation-state")).thenReturn(gbtState("g1", HASH));
        when(values.get("feelm:user:10:candidates")).thenReturn(gbtCandidates("g1", HASH));
        when(values.get("feelm:user:10:gbt-evidence:g1")).thenReturn(gbtEvidenceV2("g1", HASH));

        var reason = reader.read(10).items().getFirst().reason();
        var entries = reason.path("evidence").path("item").path("evidence");

        assertThat(reason.path("explanationVersion").textValue())
                .isEqualTo("gbt-feature-attribution-v2");
        assertThat(entries.get(0).path("genreCode").textValue()).isEqualTo("TMDB_53");
        assertThat(entries.get(1).path("sourceMovieId").longValue()).isEqualTo(55);
    }

    @Test
    @DisplayName("GBT evidence v2의 허용되지 않은 구체 출처는 Candidate를 유지하고 버린다")
    void rejectsInvalidGbtEvidenceV2SpecificSources() {
        when(values.get("feelm:model:current")).thenReturn(gbtCurrent("serving-r1"));
        when(values.get("feelm:user:10:recommendation-state")).thenReturn(gbtState("g1", HASH));
        when(values.get("feelm:user:10:candidates")).thenReturn(gbtCandidates("g1", HASH));
        String valid = gbtEvidenceV2("g1", HASH);
        for (String malformed :
                List.of(
                        valid.replace("TMDB_53", "THRILLER"),
                        valid.replace("TMDB_53", "TMDB_28"),
                        valid.replace("\"sourceMovieId\":55", "\"sourceMovieId\":0"),
                        valid.replace("\"contribution\":0.4", "\"contribution\":0.2"),
                        valid.replace(
                                "\"group\":\"CONTENT_RELATION\"", "\"group\":\"RATING_PATTERN\""),
                        valid.replace(
                                "\"genreCode\":\"TMDB_53\"",
                                "\"genreCode\":\"TMDB_53\",\"unexpected\":true"))) {
            when(values.get("feelm:user:10:gbt-evidence:g1")).thenReturn(malformed);
            assertEvidenceIgnored();
        }
    }

    @Test
    @DisplayName("evidence가 없거나 버전이 다르면 Candidate는 유지하고 GBT 설명 근거만 비운다")
    void ignoresMissingOrVersionMismatchedEvidence() {
        when(values.get("feelm:model:current")).thenReturn(gbtCurrent("serving-r1"));
        when(values.get("feelm:user:10:recommendation-state")).thenReturn(gbtState("g1", HASH));
        when(values.get("feelm:user:10:candidates")).thenReturn(gbtCandidates("g1", HASH));
        when(values.get("feelm:user:10:gbt-evidence:g1")).thenReturn(null);
        assertEvidenceIgnored();

        for (String mismatched :
                List.of(
                        gbtEvidence("g1", HASH)
                                .replace("gbt-feature-attribution-v1", "unknown-version"),
                        gbtEvidence("g1", HASH)
                                .replace("v26-max70-kobis-flag-71-v1", "unknown-feature"),
                        gbtEvidence("g1", HASH)
                                .replace("gbt-personal-raw-score-v1", "unknown-ranking"),
                        gbtEvidence("g1", HASH).replace("gbt-v26", "gbt-stale"),
                        gbtEvidence("g1", HASH).replace("serving-r1", "serving-stale"),
                        gbtEvidence("g1", HASH).replace(HASH, "sha256:" + "b".repeat(64)),
                        gbtEvidence("g1", HASH)
                                .replace("\"candidateVersion\":6", "\"candidateVersion\":5"),
                        gbtEvidence("g1", HASH)
                                .replace("\"generationId\":\"g1\"", "\"generationId\":\"g2\""))) {
            when(values.get("feelm:user:10:gbt-evidence:g1")).thenReturn(mismatched);
            assertEvidenceIgnored();
        }
    }

    @Test
    @DisplayName("허용되지 않은 그룹이나 score 불일치 evidence는 설명에 사용하지 않는다")
    void rejectsMalformedEvidenceItemsWithoutUsingThem() {
        when(values.get("feelm:model:current")).thenReturn(gbtCurrent("serving-r1"));
        when(values.get("feelm:user:10:recommendation-state")).thenReturn(gbtState("g1", HASH));
        when(values.get("feelm:user:10:candidates")).thenReturn(gbtCandidates("g1", HASH));
        String valid = gbtEvidence("g1", HASH);
        for (String malformed :
                List.of(
                        valid.replace(
                                "{\"evidenceSchemaVersion\"",
                                "{\"extra\":true,\"evidenceSchemaVersion\""),
                        valid.replace(
                                "{\"movieId\":101,\"score\"",
                                "{\"extra\":true,\"movieId\":101,\"score\""),
                        valid.replace("\"evidenceId\"", "\"extra\":true,\"evidenceId\""),
                        valid.replace("GENRE_PATTERN", "UNKNOWN_GROUP"),
                        valid.replace("\"direction\":\"POSITIVE\"", "\"direction\":\"NEGATIVE\""),
                        valid.replace("sha256:" + "c".repeat(64), "not-a-digest"),
                        valid.replace("\"movieId\":101", "\"movieId\":102"),
                        valid.replace("\"score\":4.2", "\"score\":4.1"),
                        valid.replace("\"contribution\":0.4", "\"contribution\":0"),
                        valid.replace("\"coverage\":0.7}]", "\"coverage\":0.6}]"))) {
            assertThat(malformed).isNotEqualTo(valid);
            when(values.get("feelm:user:10:gbt-evidence:g1")).thenReturn(malformed);
            assertEvidenceIgnored();
        }
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

    private static String gbtEvidence(String generation, String inputHash) {
        return """
                {"evidenceSchemaVersion":"gbt-evidence-v1",
                 "explanationVersion":"gbt-feature-attribution-v1","userId":10,
                 "generationId":"%s","modelVersion":"gbt-v26",
                 "servingRevision":"serving-r1",
                 "featureVersion":"v26-max70-kobis-flag-71-v1",
                 "rankingVersion":"gbt-personal-raw-score-v1","inputVersion":"%s",
                 "candidateVersion":6,"items":[{"movieId":101,"score":4.2,
                   "coverage":0.7,"evidence":[{
                     "evidenceId":"sha256:%s","group":"GENRE_PATTERN",
                     "direction":"POSITIVE","contribution":0.4,"coverage":0.7}]}]}
                """
                .formatted(generation, inputHash, "c".repeat(64));
    }

    private static String gbtEvidenceV2(String generation, String inputHash) {
        return """
                {"evidenceSchemaVersion":"gbt-evidence-v2",
                 "explanationVersion":"gbt-feature-attribution-v2","userId":10,
                 "generationId":"%s","modelVersion":"gbt-v26",
                 "servingRevision":"serving-r1",
                 "featureVersion":"v26-max70-kobis-flag-71-v1",
                 "rankingVersion":"gbt-personal-raw-score-v1","inputVersion":"%s",
                 "candidateVersion":6,"items":[{"movieId":101,"score":4.2,
                   "coverage":0.7,"evidence":[{
                     "evidenceId":"sha256:%s","group":"GENRE_PATTERN",
                     "direction":"POSITIVE","contribution":0.4,"coverage":0.4,
                     "genreCode":"TMDB_53"},{
                     "evidenceId":"sha256:%s","group":"CONTENT_RELATION",
                     "direction":"POSITIVE","contribution":0.3,"coverage":0.3,
                     "sourceMovieId":55}]}]}
                """
                .formatted(
                        generation,
                        inputHash,
                        "82211a19e9405804bf5d90912454215795e3a88dca410ed9b7bebf7f6c35cf84",
                        "562d59adbc18f27a129439452741343e6a918f3a8fb279cf077f7c8050cfcf06");
    }

    private void assertEvidenceIgnored() {
        assertThat(reader.read(10).items().getFirst().reason().has("evidence"))
                .as("malformed evidence must not be attached")
                .isFalse();
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.RECOMMENDATION_UNAVAILABLE);
    }
}
