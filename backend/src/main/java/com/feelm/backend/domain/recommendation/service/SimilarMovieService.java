package com.feelm.backend.domain.recommendation.service;

import com.feelm.backend.domain.recommendation.dto.response.SimilarMoviesResponse;
import com.feelm.backend.domain.recommendation.repository.SimilarMovieRepository;
import com.feelm.backend.domain.recommendation.repository.SimilarMovieRepository.BaseMovieEmbedding;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SimilarMovieService {

    private static final int DEFAULT_SIZE = 10;
    private static final int MAX_SIZE = 20;

    private final SimilarMovieRepository repository;

    public SimilarMoviesResponse getSimilarMovies(long movieId, Integer requestedSize) {
        int size = requestedSize == null ? DEFAULT_SIZE : requestedSize;
        if (size < 1 || size > MAX_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        BaseMovieEmbedding base =
                repository
                        .findBaseMovieEmbedding(movieId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!base.isAvailable()) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_UNAVAILABLE);
        }

        return new SimilarMoviesResponse(
                base.movieId(),
                repository.findSimilarMovies(
                        base.movieId(), base.embedding(), base.modelVersion(), size));
    }
}
