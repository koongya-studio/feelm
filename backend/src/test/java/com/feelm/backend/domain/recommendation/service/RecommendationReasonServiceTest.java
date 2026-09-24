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
        var result = service.initialReason("TASTE", stored);

        assertThat(result.path("summary").textValue()).isEqualTo("좋아하신 영화와 스릴러 장르가 같아요.");
        verifyNoInteractions(generator);
    }

    @Test
    void keepsInitialAndFallbackTypesDeterministicWithoutGbtEvidence() {
        RecommendationReasonService service = service(true);

        var taste = service.initialReason("TASTE", objectMapper.readTree("{\"reasons\":[]}"));
        var fallback = service.initialReason("FALLBACK", objectMapper.readTree("{\"reasons\":[]}"));

        assertThat(taste.path("summary").textValue()).isEqualTo("취향 데이터를 바탕으로 영화를 골라봤어요.");
        assertThat(fallback.path("summary").textValue()).isEqualTo("새로운 영화를 만나보세요.");
        assertThat(taste.path("factors")).isEmpty();
        assertThat(fallback.path("factors")).isEmpty();
        verifyNoInteractions(generator);
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
