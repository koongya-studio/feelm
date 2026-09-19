package com.feelm.backend.domain.watch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.watch.repository.RatingCommandRepository;
import com.feelm.backend.domain.watch.repository.RatingCommandRepository.LockedMovie;
import com.feelm.backend.domain.watch.service.OnboardingRatingWriter.RatingChoice;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

@DisplayName("온보딩 평가 저장 순서")
class OnboardingRatingWriterTest {

    private static final long USER_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-09-19T01:02:03.123456Z");

    @Test
    @DisplayName("영화를 ID 오름차순으로 모두 잠근 뒤 기존 평가를 제외하고 생성한다")
    void locksMoviesInAscendingOrderBeforeCreatingRatings() {
        RatingCommandRepository repository = mock(RatingCommandRepository.class);
        RatingStateWriter stateWriter = mock(RatingStateWriter.class);
        OnboardingRatingWriter writer =
                new OnboardingRatingWriter(
                        repository, stateWriter, Clock.fixed(NOW, ZoneOffset.UTC));
        LockedMovie lower = new LockedMovie(10L, 1L, "FLAVOR_A");
        LockedMovie higher = new LockedMovie(20L, 2L, "FLAVOR_B");
        when(repository.lockMovie(10L)).thenReturn(Optional.of(lower));
        when(repository.lockMovie(20L)).thenReturn(Optional.of(higher));
        when(repository.ratingExists(USER_ID, 10L)).thenReturn(true);
        when(repository.ratingExists(USER_ID, 20L)).thenReturn(false);

        int created =
                writer.createMissing(
                        USER_ID,
                        List.of(
                                new RatingChoice(20L, new BigDecimal("2.0")),
                                new RatingChoice(10L, new BigDecimal("4.0"))));

        assertThat(created).isEqualTo(1);
        InOrder order = inOrder(repository, stateWriter);
        order.verify(repository).lockMovie(10L);
        order.verify(repository).lockMovie(20L);
        order.verify(repository).ratingExists(USER_ID, 10L);
        order.verify(repository).ratingExists(USER_ID, 20L);
        order.verify(stateWriter)
                .create(USER_ID, higher, new BigDecimal("2.0"), null, null, false, NOW);
        verify(stateWriter, never())
                .create(USER_ID, lower, new BigDecimal("4.0"), null, null, false, NOW);
    }
}
