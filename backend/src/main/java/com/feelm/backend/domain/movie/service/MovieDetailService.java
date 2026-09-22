package com.feelm.backend.domain.movie.service;

import com.feelm.backend.domain.movie.dto.response.MovieDetailResponse;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class MovieDetailService {

    private static final String VIEW_OUTBOX_METRIC = "feelm.movie.view.outbox";

    private final MovieDetailReader movieDetailReader;
    private final MovieViewEventWriter viewEventWriter;
    private final MeterRegistry meterRegistry;

    public MovieDetailResponse getDetail(long movieId, Long recommendationItemId, Long userId) {
        MovieDetailResponse response =
                movieDetailReader.read(movieId, recommendationItemId, userId);
        recordView(userId, movieId);
        return response;
    }

    private void recordView(Long userId, long movieId) {
        if (userId == null) {
            return;
        }
        try {
            if (viewEventWriter.record(userId, movieId)) {
                meterRegistry.counter(VIEW_OUTBOX_METRIC, "outcome", "recorded").increment();
            }
        } catch (RuntimeException failure) {
            meterRegistry.counter(VIEW_OUTBOX_METRIC, "outcome", "failed").increment();
            log.warn(
                    "VIEW outbox record failed: userId={}, movieId={}, cause={}",
                    userId,
                    movieId,
                    failure.getClass().getSimpleName());
        }
    }
}
