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
        var result = service.initialReason(10, "TASTE", stored);

        assertThat(result.path("summary").textValue()).isEqualTo("좋아하신 영화와 스릴러 장르가 같아요.");
        verifyNoInteractions(generator);
    }

    @Test
    void keepsInitialAndFallbackTypesDeterministicWithoutGbtEvidence() {
        RecommendationReasonService service = service(true);

        var taste = service.initialReason(10, "TASTE", objectMapper.readTree("{\"reasons\":[]}"));
        var fallback =
                service.initialReason(10, "FALLBACK", objectMapper.readTree("{\"reasons\":[]}"));

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
                        10, "TASTE", gbtV2("GENRE_PATTERN", "\"genreCode\":\"TMDB_53\""));
        var movie =
                service.initialReason(
                        10, "TASTE", gbtV2("CONTENT_RELATION", "\"sourceMovieId\":55"));

        assertThat(genre.path("summary").textValue()).isEqualTo("평소 스릴러 장르를 좋아해서 골랐어요.");
        assertThat(movie.path("summary").textValue()).isEqualTo("\"스파이더맨\"을 재미있게 봐서 골랐어요.");
    }

    @Test
    void unverifiedSpecificGbtEvidenceFallsBackToGeneralGroup() {
        RecommendationReasonService service = service(true);

        var result =
                service.initialReason(
                        10, "TASTE", gbtV2("CONTENT_RELATION", "\"sourceMovieId\":55"));

        assertThat(result.path("summary").textValue()).isEqualTo("선호 콘텐츠와의 연관성을 바탕으로 골랐어요.");
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
