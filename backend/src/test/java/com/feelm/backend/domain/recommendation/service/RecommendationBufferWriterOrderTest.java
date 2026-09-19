package com.feelm.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.recommendation.model.RecommendationCandidateBatch;
import com.feelm.backend.domain.recommendation.repository.RecommendationBufferRepository;
import com.feelm.backend.domain.recommendation.repository.RecommendationCandidateReader;
import com.feelm.backend.global.error.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class RecommendationBufferWriterOrderTest {
    private final RecommendationBufferRepository repository =
            mock(RecommendationBufferRepository.class);
    private final RecommendationCandidateReader candidateReader =
            mock(RecommendationCandidateReader.class);
    private final RecommendationReasonService reasonService =
            mock(RecommendationReasonService.class);
    private final RecommendationBufferWriter writer =
            new RecommendationBufferWriter(
                    repository, candidateReader, reasonService, Clock.systemUTC());

    @Test
    void locksUserBeforeReadingRedisAndReusesOwnGeneration() {
        RecommendationCandidateBatch batch =
                new RecommendationCandidateBatch(
                        "model",
                        6L,
                        "generation",
                        Instant.parse("2026-09-18T00:00:00Z"),
                        List.of());
        when(repository.lockActiveUserIfExists(1024L)).thenReturn(true);
        when(candidateReader.read(1024L)).thenReturn(batch);
        when(repository.findExistingSet(1024L, "generation")).thenReturn(OptionalLong.of(77L));

        assertThat(writer.writeCurrent(1024L).recommendationSetId()).isEqualTo(77L);

        var order = inOrder(repository, candidateReader);
        order.verify(repository).lockActiveUserIfExists(1024L);
        order.verify(candidateReader).read(1024L);
        order.verify(repository).findExistingSet(1024L, "generation");
    }

    @Test
    void inactiveUserNeverReadsRedis() {
        when(repository.lockActiveUserIfExists(1024L)).thenReturn(false);

        assertThatThrownBy(() -> writer.writeCurrent(1024L)).isInstanceOf(BusinessException.class);

        verify(candidateReader, never()).read(1024L);
    }

    @Test
    void distinguishesNoEligibleMoviesFromMissingRedis() {
        RecommendationCandidateBatch batch =
                new RecommendationCandidateBatch(
                        "model",
                        6L,
                        "empty-generation",
                        Instant.parse("2026-09-18T00:00:00Z"),
                        List.of());
        when(repository.lockActiveUserIfExists(1024L)).thenReturn(true);
        when(candidateReader.read(1024L)).thenReturn(batch);

        assertThatThrownBy(() -> writer.writeCurrent(1024L))
                .isInstanceOf(NoEligibleRecommendationCandidatesException.class);
    }
}
