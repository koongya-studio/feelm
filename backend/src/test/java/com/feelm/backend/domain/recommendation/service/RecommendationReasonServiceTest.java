package com.feelm.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.recommendation.repository.RecommendationReasonGenerator;
import com.feelm.backend.domain.recommendation.repository.RecommendationReasonRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RecommendationReasonServiceTest {

    private final RecommendationReasonRepository repository =
            mock(RecommendationReasonRepository.class);
    private final RecommendationReasonGenerator generator =
            mock(RecommendationReasonGenerator.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesPopularityNoticeWithoutAiPersonalization() {
        for (boolean enabled : new boolean[] {true, false}) {
            var result =
                    service(enabled)
                            .initialReason(
                                    10,
                                    101,
                                    "TASTE",
                                    objectMapper.readTree(
                                            "{\"recommendationMode\":\"POPULARITY\",\"reasons\":[]}"));
            assertThat(result.path("summary").textValue())
                    .isEqualTo("아직 취향 정보가 없어, 인기도를 기준으로 영화를 골랐어요.");
            assertThat(result.path("recommendationMode").textValue()).isEqualTo("POPULARITY");
            assertThat(result.path("_reasonPending").asBoolean()).isFalse();
        }
        verifyNoInteractions(generator);
    }

    @Test
    void keepsLegacyGroundedReasonWhenGbtAiIsDisabled() {
        when(repository.findMovieTitle(55)).thenReturn(Optional.of("근거 영화"));
        when(repository.findGenreName("THRILLER")).thenReturn(Optional.of("스릴러"));
        RecommendationReasonService service = service(false);

        var stored =
                objectMapper.readTree(
                        """
                                {"reasons":[{"code":"SHARED_GENRE","sourceMovieIds":[55],
                                "matchedGenreCodes":["THRILLER"]}]}
                                """);
        var result = service.initialReason(10, 101, "TASTE", stored);

        assertThat(result.path("summary").textValue()).isEqualTo("좋아하신 영화와 스릴러 장르가 같아요.");
        verifyNoInteractions(generator);
    }

    @Test
    void keepsInitialAndFallbackTypesDeterministicWithoutGbtEvidence() {
        RecommendationReasonService service = service(true);

        var taste =
                service.initialReason(10, 101, "TASTE", objectMapper.readTree("{\"reasons\":[]}"));
        var fallback =
                service.initialReason(
                        10, 101, "FALLBACK", objectMapper.readTree("{\"reasons\":[]}"));

        assertThat(taste.path("summary").textValue()).isEqualTo("취향 데이터를 바탕으로 영화를 골라봤어요.");
        assertThat(fallback.path("summary").textValue()).isEqualTo("새로운 영화를 만나보세요.");
        assertThat(taste.path("factors")).isEmpty();
        assertThat(fallback.path("factors")).isEmpty();
        verifyNoInteractions(generator);
    }

    @Test
    void usesOnlyDatabaseVerifiedSpecificGbtEvidence() {
        when(repository.findGenreName("TMDB_53")).thenReturn(Optional.of("스릴러"));
        when(repository.findPositiveRatedMovieTitleBeforeInsert(10, 101, 55))
                .thenReturn(Optional.of("스파이더맨"));
        RecommendationReasonService service = service(true);

        var genre =
                service.initialReason(
                        10, 101, "TASTE", gbtV2("GENRE_PATTERN", "\"genreCode\":\"TMDB_53\""));
        var movie =
                service.initialReason(
                        10, 101, "TASTE", gbtV2("CONTENT_RELATION", "\"sourceMovieId\":55"));

        assertThat(genre.path("summary").textValue()).isEqualTo("평소 스릴러 장르를 좋아해서 골랐어요.");
        assertThat(movie.path("summary").textValue()).isEqualTo("\"스파이더맨\"을 재미있게 봐서 골랐어요.");
    }

    @Test
    void unverifiedSpecificGbtEvidenceFallsBackToGeneralGroup() {
        RecommendationReasonService service = service(true);

        var result =
                service.initialReason(
                        10, 101, "TASTE", gbtV2("CONTENT_RELATION", "\"sourceMovieId\":55"));

        assertThat(result.path("summary").textValue()).isEqualTo("선호 콘텐츠와의 연관성을 바탕으로 골랐어요.");
    }

    @Test
    void discoveryNamesVerifiedPositiveContributorAtGroupLevel() {
        when(repository.findPositiveRatedMovieTitleBeforeInsert(10, 101, 55))
                .thenReturn(Optional.of("인셉션"));
        RecommendationReasonService service = service(false);

        var result =
                service.initialReason(
                        10,
                        101,
                        "DISCOVERY",
                        discovery(
                                "{\"movieId\":55,\"relation\":\"POSITIVE_ALIGNMENT\"},"
                                        + "{\"movieId\":77,\"relation\":\"NEGATIVE_CONTRAST\"}",
                                1));

        // 기여 1위가 검증된 좋아한 영화다. 후보가 그 영화와 비슷하다고 말하지 않는다
        assertThat(result.path("summary").textValue()).isEqualTo("'인셉션'을 좋아하셨다면 도전해 볼 만해요.");
        assertThat(result.path("factors")).isEmpty();
        verifyNoInteractions(generator);
    }

    @Test
    void discoveryDoesNotNameContributorBelowTopWhenFirstIsNotVerifiedLikedMovie() {
        when(repository.findPositiveRatedMovieTitleBeforeInsert(10, 101, 55))
                .thenReturn(Optional.of("인셉션"));
        RecommendationReasonService service = service(false);

        // 1위가 싫어한 영화의 반대 방향이면 coverage 0.5가 뒤 영화의 비중을 말해 주지 않는다(리뷰 P2)
        var negativeFirst =
                service.initialReason(
                        10,
                        101,
                        "DISCOVERY",
                        discovery(
                                "{\"movieId\":77,\"relation\":\"NEGATIVE_CONTRAST\"},"
                                        + "{\"movieId\":55,\"relation\":\"POSITIVE_ALIGNMENT\"}",
                                1,
                                "0.5"));
        // 1위 좋아한 영화가 4점 이상 평가로 확인되지 않아도 2위를 대신 짚지 않는다
        var unverifiedFirst =
                service.initialReason(
                        10,
                        101,
                        "DISCOVERY",
                        discovery(
                                "{\"movieId\":88,\"relation\":\"POSITIVE_ALIGNMENT\"},"
                                        + "{\"movieId\":55,\"relation\":\"POSITIVE_ALIGNMENT\"}",
                                1,
                                "0.9"));

        assertThat(negativeFirst.path("summary").textValue()).isEqualTo("좋아하신 영화들을 보면 도전해 볼 만해요.");
        assertThat(unverifiedFirst.path("summary").textValue())
                .isEqualTo("좋아하신 영화들을 보면 도전해 볼 만해요.");
    }

    @Test
    void discoveryWithoutUsableEvidenceFallsThroughToNextReason() {
        when(repository.findMovieTitle(55)).thenReturn(Optional.of("인셉션"));
        when(repository.findGenreName("DRAMA")).thenReturn(Optional.of("드라마"));
        RecommendationReasonService service = service(false);

        // 첫 aggregate 항목은 말할 영화가 없다. 해석하지 못한 항목이므로 뒤의 유효한 항목을 쓴다
        var result =
                service.initialReason(
                        10,
                        101,
                        "DISCOVERY",
                        objectMapper.readTree(
                                """
                                {"reasons":[{"code":"DISCOVERY_AGGREGATE_AFFINITY","directionSource":"RATING",
                                 "contributorCount":1,"topContributors":[
                                 {"movieId":77,"relation":"NEGATIVE_CONTRAST"}],
                                 "topContributionCoverage":1.0,"viewedCountInGroup":0,
                                 "affinityRank":1,"popularityRankInGroup":1},
                                {"code":"DISCOVERY_PERSONAL_SCORE","sourceMovieIds":[55],
                                 "matchedGenreCodes":["DRAMA"]}]}
                                """));

        assertThat(result.path("summary").textValue()).isEqualTo("좋아하신 영화와 드라마 장르가 같아요.");
    }

    @Test
    void discoveryWithInvalidGroupFieldsFallsThroughToNextReason() {
        when(repository.findPositiveRatedMovieTitleBeforeInsert(10, 101, 55))
                .thenReturn(Optional.of("인셉션"));
        when(repository.findMovieTitle(55)).thenReturn(Optional.of("인셉션"));
        when(repository.findGenreName("DRAMA")).thenReturn(Optional.of("드라마"));
        RecommendationReasonService service = service(false);
        String next =
                """
                {"code":"DISCOVERY_PERSONAL_SCORE","sourceMovieIds":[55],"matchedGenreCodes":["DRAMA"]}""";

        // viewedCountInGroup이 선택 규칙 밖이거나 long 범위를 넘거나, coverage가 0~1 밖이면 해석하지 못한 항목이다
        for (String fields :
                java.util.List.of(
                        "\"viewedCountInGroup\":3,\"topContributionCoverage\":0.8",
                        "\"viewedCountInGroup\":18446744073709551617,\"topContributionCoverage\":0.8",
                        "\"viewedCountInGroup\":1,\"topContributionCoverage\":1.5")) {
            var result =
                    service.initialReason(
                            10,
                            101,
                            "DISCOVERY",
                            objectMapper.readTree(
                                    """
                                    {"reasons":[{"code":"DISCOVERY_AGGREGATE_AFFINITY","directionSource":"RATING",
                                     "contributorCount":1,"topContributors":[
                                     {"movieId":55,"relation":"POSITIVE_ALIGNMENT"}],%s,
                                     "affinityRank":1,"popularityRankInGroup":1},%s]}
                                    """
                                            .formatted(fields, next)));

            assertThat(result.path("summary").textValue())
                    .as(fields)
                    .isEqualTo("좋아하신 영화와 드라마 장르가 같아요.");
        }
    }

    @Test
    void discoveryFallsBackWhenContributorIsNotVerifiedPositiveRating() {
        RecommendationReasonService service = service(false);

        var unverified =
                service.initialReason(
                        10,
                        101,
                        "DISCOVERY",
                        discovery("{\"movieId\":55,\"relation\":\"POSITIVE_ALIGNMENT\"}", 0));
        var negativeOnly =
                service.initialReason(
                        10,
                        101,
                        "DISCOVERY",
                        discovery("{\"movieId\":77,\"relation\":\"NEGATIVE_CONTRAST\"}", 0));

        assertThat(unverified.path("summary").textValue()).isEqualTo("새로운 영화를 만나보세요.");
        assertThat(negativeOnly.path("summary").textValue()).isEqualTo("새로운 영화를 만나보세요.");
    }

    @Test
    void discoveryDoesNotClaimUnwatchedGroupWhenViewedCountIsOutsideSelectionRule() {
        when(repository.findPositiveRatedMovieTitleBeforeInsert(10, 101, 55))
                .thenReturn(Optional.of("인셉션"));
        RecommendationReasonService service = service(false);

        var result =
                service.initialReason(
                        10,
                        101,
                        "DISCOVERY",
                        discovery("{\"movieId\":55,\"relation\":\"POSITIVE_ALIGNMENT\"}", 3));

        assertThat(result.path("summary").textValue()).isEqualTo("새로운 영화를 만나보세요.");
    }

    @Test
    void discoveryUsesHistorySentenceWhenContributionIsSpreadBelowCoverageThreshold() {
        when(repository.findPositiveRatedMovieTitleBeforeInsert(10, 101, 55))
                .thenReturn(Optional.of("인셉션"));
        RecommendationReasonService service = service(false);
        String liked = "{\"movieId\":55,\"relation\":\"POSITIVE_ALIGNMENT\"}";

        // 기준(0.5) 이상이면 영화 한 편을 짚고, 미만이면 여러 편에 퍼진 것이라 전체 이력으로 말한다
        var atThreshold = service.initialReason(10, 101, "DISCOVERY", discovery(liked, 1, "0.5"));
        var below = service.initialReason(10, 101, "DISCOVERY", discovery(liked, 1, "0.49"));

        assertThat(atThreshold.path("summary").textValue()).isEqualTo("'인셉션'을 좋아하셨다면 도전해 볼 만해요.");
        assertThat(below.path("summary").textValue()).isEqualTo("좋아하신 영화들을 보면 도전해 볼 만해요.");
    }

    @Test
    void discoveryKeepsGeneralSentenceWithoutVerifiedContributorOrValidCoverage() {
        when(repository.findPositiveRatedMovieTitleBeforeInsert(10, 101, 55))
                .thenReturn(Optional.of("인셉션"));
        RecommendationReasonService service = service(false);

        // 좋아한 영화가 확인되지 않으면 낮은 coverage여도 "좋아하신 영화들"이라고 말하지 않는다
        var unverifiedLow =
                service.initialReason(
                        10,
                        101,
                        "DISCOVERY",
                        discovery(
                                "{\"movieId\":77,\"relation\":\"POSITIVE_ALIGNMENT\"}", 1, "0.2"));
        var missingCoverage =
                service.initialReason(
                        10,
                        101,
                        "DISCOVERY",
                        discovery("{\"movieId\":55,\"relation\":\"POSITIVE_ALIGNMENT\"}", 1, null));

        assertThat(unverifiedLow.path("summary").textValue()).isEqualTo("새로운 영화를 만나보세요.");
        assertThat(missingCoverage.path("summary").textValue()).isEqualTo("새로운 영화를 만나보세요.");
    }

    @Test
    void discoveryIgnoresContributorsBeyondContractLimit() {
        when(repository.findPositiveRatedMovieTitleBeforeInsert(10, 101, 14))
                .thenReturn(Optional.of("넷째 영화"));
        RecommendationReasonService service = service(false);

        // 저장된 과거 payload가 계약(최대 3편)을 넘어도 4번째 영화는 조회하지도, 근거로 쓰지도 않는다
        var result =
                service.initialReason(
                        10,
                        101,
                        "DISCOVERY",
                        discovery(
                                "{\"movieId\":11,\"relation\":\"POSITIVE_ALIGNMENT\"},"
                                        + "{\"movieId\":12,\"relation\":\"POSITIVE_ALIGNMENT\"},"
                                        + "{\"movieId\":13,\"relation\":\"POSITIVE_ALIGNMENT\"},"
                                        + "{\"movieId\":14,\"relation\":\"POSITIVE_ALIGNMENT\"}",
                                0,
                                "0.9"));

        assertThat(result.path("summary").textValue()).isEqualTo("새로운 영화를 만나보세요.");
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .findPositiveRatedMovieTitleBeforeInsert(10, 101, 14);
    }

    @Test
    void choosesObjectParticleFromTitleEnding() {
        assertThat(RecommendationReasonService.objectParticle("인셉션")).isEqualTo("을");
        assertThat(RecommendationReasonService.objectParticle("아바타")).isEqualTo("를");
        // 끝의 괄호 · 줄임표는 건너뛰고 그 앞 글자로 본다
        assertThat(RecommendationReasonService.objectParticle("기생충 (2019)")).isEqualTo("를");
        assertThat(RecommendationReasonService.objectParticle("반지의 제왕…")).isEqualTo("을");
        // 숫자는 읽는 소리, 영문은 흔한 끝소리 규칙을 따른다
        assertThat(RecommendationReasonService.objectParticle("007")).isEqualTo("을");
        assertThat(RecommendationReasonService.objectParticle("극한직업 2")).isEqualTo("를");
        assertThat(RecommendationReasonService.objectParticle("Up")).isEqualTo("을");
        assertThat(RecommendationReasonService.objectParticle("Joker")).isEqualTo("를");
        assertThat(RecommendationReasonService.objectParticle("!!!")).isEqualTo("을(를)");
        // 보충 평면 문자(U+20061)를 char로 잘라 영문으로 오인하지 않는다
        assertThat(
                        RecommendationReasonService.objectParticle(
                                new String(Character.toChars(0x20061))))
                .isEqualTo("을(를)");
    }

    private tools.jackson.databind.JsonNode discovery(String contributors, int viewedCountInGroup) {
        return discovery(contributors, viewedCountInGroup, "0.8");
    }

    private tools.jackson.databind.JsonNode discovery(
            String contributors, int viewedCountInGroup, String coverage) {
        String coverageField =
                coverage == null ? "" : "\"topContributionCoverage\":" + coverage + ",";
        return objectMapper.readTree(
                """
                {"reasons":[{"code":"DISCOVERY_AGGREGATE_AFFINITY","directionSource":"RATING",
                 "contributorCount":2,"topContributors":[%s],%s
                 "viewedCountInGroup":%d,"affinityRank":1,"popularityRankInGroup":3}]}
                """
                        .formatted(contributors, coverageField, viewedCountInGroup));
    }

    private tools.jackson.databind.JsonNode gbtV2(String group, String specificField) {
        return objectMapper.readTree(
                """
                {"reasons":[],"modelKind":"GBT","modelVersion":"gbt-v26",
                 "rankingVersion":"gbt-personal-raw-score-v1",
                 "explanationVersion":"gbt-feature-attribution-v2","evidence":{
                   "evidenceSchemaVersion":"gbt-evidence-v2",
                   "explanationVersion":"gbt-feature-attribution-v2","userId":10,
                   "modelVersion":"gbt-v26","rankingVersion":"gbt-personal-raw-score-v1",
                   "item":{"movieId":101,"evidence":[{"evidenceId":"sha256:%s","group":"%s",
                     "direction":"POSITIVE",%s}]}}}
                """
                        .formatted("a".repeat(64), group, specificField));
    }

    private RecommendationReasonService service(boolean enabled) {
        return new RecommendationReasonService(
                repository,
                generator,
                mock(RecommendationReasonSnapshotWriter.class),
                objectMapper,
                new SimpleMeterRegistry(),
                enabled);
    }
}
