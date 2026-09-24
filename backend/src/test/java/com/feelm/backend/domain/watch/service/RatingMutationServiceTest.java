package com.feelm.backend.domain.watch.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.taste.service.TasteAnalysisWriter;
import com.feelm.backend.domain.watch.dto.request.RatingUpdateRequest;
import com.feelm.backend.domain.watch.model.RatingVisibility;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository.ExistingFrame;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository.LockedMovie;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository.LockedRating;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository.OwnedRating;
import com.feelm.backend.infrastructure.kafka.KafkaEventPublisher;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 평가 수정·삭제가 취향 분석 캐시를 다시 계산하는지 본다(S15P21E106-775).
 *
 * <p>계약(API-040)이 "평가 추가·수정·삭제 트랜잭션에서 캐시를 갱신한다"고 정했다. 재계산 결과 자체는 {@code
 * TasteAnalysisWriterPostgresTest}가 보고, 여기서는 세 경로 중 둘이 실제로 호출하는지를 고정한다.
 */
@DisplayName("평가 수정·삭제의 취향 분석 재계산")
class RatingMutationServiceTest {

    private static final long USER_ID = 3L;
    private static final long MOVIE_ID = 5L;
    private static final long RATING_ID = 13L;
    private static final Instant NOW = Instant.parse("2026-09-24T01:00:00Z");

    private RatingCommandRepository repository;
    private TasteAnalysisWriter tasteAnalysisWriter;
    private KafkaEventPublisher eventPublisher;
    private RatingMutationService service;

    @BeforeEach
    void setUp() {
        repository = mock(RatingCommandRepository.class);
        tasteAnalysisWriter = mock(TasteAnalysisWriter.class);
        eventPublisher = mock(KafkaEventPublisher.class);
        service =
                new RatingMutationService(
                        repository,
                        tasteAnalysisWriter,
                        eventPublisher,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.lockActiveUser(USER_ID)).thenReturn(true);
        when(repository.findOwnedRating(USER_ID, RATING_ID))
                .thenReturn(Optional.of(new OwnedRating(MOVIE_ID, null)));
        when(repository.lockMovie(MOVIE_ID))
                .thenReturn(Optional.of(new LockedMovie(MOVIE_ID, null, null)));
        when(repository.lockOwnedRating(USER_ID, RATING_ID))
                .thenReturn(
                        Optional.of(
                                new LockedRating(
                                        RATING_ID,
                                        MOVIE_ID,
                                        null,
                                        new BigDecimal("4.0"),
                                        "예전 한줄평",
                                        RatingVisibility.PUBLIC.name(),
                                        false,
                                        NOW)));
        when(repository.findFrame(RATING_ID, USER_ID, MOVIE_ID))
                .thenReturn(Optional.of(new ExistingFrame(17L, NOW)));
    }

    @Test
    @DisplayName("T2 값이 바뀐 수정은 같은 시각으로 캐시를 다시 계산한다")
    void recomputesOnChangedUpdate() {
        service.update(USER_ID, RATING_ID, request(new BigDecimal("5.0"), "바뀐 한줄평"));

        verify(tasteAnalysisWriter).refresh(USER_ID, NOW);
    }

    @Test
    @DisplayName("T6 값이 그대로면 캐시를 다시 계산하지 않는다")
    void skipsRecomputeWhenNothingChanged() {
        service.update(USER_ID, RATING_ID, request(new BigDecimal("4.0"), "예전 한줄평"));

        verify(tasteAnalysisWriter, never()).refresh(anyLong(), any());
    }

    @Test
    @DisplayName("삭제도 캐시를 다시 계산한다")
    void recomputesOnDelete() {
        service.delete(USER_ID, RATING_ID);

        verify(tasteAnalysisWriter).refresh(USER_ID, NOW);
    }

    private static RatingUpdateRequest request(BigDecimal score, String comment) {
        return new RatingUpdateRequest(score, comment, RatingVisibility.PUBLIC, false);
    }
}
