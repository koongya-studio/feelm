package com.feelm.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.recommendation.dto.response.SimilarMovieItemResponse;
import com.feelm.backend.domain.recommendation.repository.SimilarMovieRepository;
import com.feelm.backend.domain.recommendation.repository.SimilarMovieRepository.BaseMovieEmbedding;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SimilarMovieServiceTest {

    @Mock private SimilarMovieRepository repository;
    @InjectMocks private SimilarMovieService service;

    @Test
    void usesDefaultSizeTen() {
        BaseMovieEmbedding base = new BaseMovieEmbedding(100, "[1,0]", "model-v1");
        SimilarMovieItemResponse item =
                new SimilarMovieItemResponse(101, "후보", null, BigDecimal.ZERO, 1.0);
        when(repository.findBaseMovieEmbedding(100)).thenReturn(Optional.of(base));
        when(repository.findSimilarMovies(100, "[1,0]", "model-v1", 10)).thenReturn(List.of(item));

        assertThat(service.getSimilarMovies(100, null).items()).containsExactly(item);
        verify(repository).findSimilarMovies(100, "[1,0]", "model-v1", 10);
    }

    @Test
    void rejectsInvalidSizeBeforeQuerying() {
        assertThatThrownBy(() -> service.getSimilarMovies(100, 21))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
        verifyNoInteractions(repository);
    }

    @Test
    void distinguishesMissingMovieFromMissingEmbedding() {
        when(repository.findBaseMovieEmbedding(404)).thenReturn(Optional.empty());
        when(repository.findBaseMovieEmbedding(100))
                .thenReturn(Optional.of(new BaseMovieEmbedding(100, null, null)));

        assertThatThrownBy(() -> service.getSimilarMovies(404, 10))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> service.getSimilarMovies(100, 10))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.RECOMMENDATION_UNAVAILABLE));
    }
}
