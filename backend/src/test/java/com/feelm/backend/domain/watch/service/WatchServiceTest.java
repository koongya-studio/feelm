package com.feelm.backend.domain.watch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.watch.dto.response.OttClickResponse;
import com.feelm.backend.domain.watch.event.OttClickEventPayload;
import com.feelm.backend.domain.watch.model.WatchStatus;
import com.feelm.backend.domain.watch.repository.WatchRepository;
import com.feelm.backend.domain.watch.repository.WatchRepository.WatchRecordSnapshot;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.infrastructure.kafka.KafkaEventPublisher;
import com.feelm.backend.infrastructure.kafka.KafkaEventType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;

@DisplayName("감상 서비스 규칙")
class WatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-08T05:30:00Z");
    private static final long USER_ID = 10L;
    private static final long MOVIE_ID = 20L;
    private static final long OTT_ID = 30L;

    private WatchRepository repository;
    private KafkaEventPublisher eventPublisher;
    private WatchService service;

    @BeforeEach
    void setUp() {
        repository = mock(WatchRepository.class);
        eventPublisher = mock(KafkaEventPublisher.class);
        service = new WatchService(repository, eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.lockActiveUser(USER_ID)).thenReturn(true);
        when(repository.movieOffersOtt(MOVIE_ID, OTT_ID)).thenReturn(true);
    }

    @Test
    @DisplayName("최초 클릭은 기록을 만든 뒤 같은 트랜잭션 경계에서 이벤트를 발행한다")
    void firstClickCreatesRecordBeforePublishingEvent() {
        WatchRecordSnapshot created = record(101L, WatchStatus.LINK_CLICKED, NOW);
        when(repository.findActiveForUpdate(USER_ID, MOVIE_ID)).thenReturn(Optional.empty());
        when(repository.createClick(USER_ID, MOVIE_ID, OTT_ID, NOW)).thenReturn(created);

        WatchService.OttClickResult result = service.clickOtt(USER_ID, MOVIE_ID, OTT_ID);

        assertThat(result.created()).isTrue();
        assertThat(result.response()).isEqualTo(OttClickResponse.from(created));
        InOrder order = inOrder(repository, eventPublisher);
        order.verify(repository).lockActiveUser(USER_ID);
        order.verify(repository).movieOffersOtt(MOVIE_ID, OTT_ID);
        order.verify(repository).findActiveForUpdate(USER_ID, MOVIE_ID);
        order.verify(repository).createClick(USER_ID, MOVIE_ID, OTT_ID, NOW);
        order.verify(eventPublisher)
                .publish(
                        KafkaEventType.OTT_CLICK,
                        USER_ID,
                        new OttClickEventPayload(MOVIE_ID, OTT_ID, 101L));
    }

    @Test
    @DisplayName("만료 전 재클릭은 같은 기록과 요청 OTT로 갱신하고 이벤트를 발행한다")
    void repeatedClickRefreshesSameRecordAndPublishesEvent() {
        WatchRecordSnapshot current = record(102L, WatchStatus.LINK_CLICKED, NOW.plusSeconds(1));
        WatchRecordSnapshot refreshed = record(102L, WatchStatus.LINK_CLICKED, NOW);
        when(repository.findActiveForUpdate(USER_ID, MOVIE_ID)).thenReturn(Optional.of(current));
        when(repository.refreshClick(102L, OTT_ID, NOW)).thenReturn(refreshed);

        WatchService.OttClickResult result = service.clickOtt(USER_ID, MOVIE_ID, OTT_ID);

        assertThat(result.created()).isFalse();
        assertThat(result.response().watchRecordId()).isEqualTo(102L);
        verify(repository).refreshClick(102L, OTT_ID, NOW);
        verify(repository, never()).createClick(anyLong(), anyLong(), anyLong(), any());
        verify(repository, never()).expireClick(anyLong(), any());
        verify(eventPublisher)
                .publish(
                        KafkaEventType.OTT_CLICK,
                        USER_ID,
                        new OttClickEventPayload(MOVIE_ID, OTT_ID, 102L));
    }

    @Test
    @DisplayName("만료 경계의 대상 기록만 EXPIRED로 바꾼 뒤 새 기록과 이벤트를 만든다")
    void expiredClickCreatesNewRecordAfterExpiringOnlyTarget() {
        WatchRecordSnapshot expired = record(103L, WatchStatus.LINK_CLICKED, NOW);
        WatchRecordSnapshot replacement = record(104L, WatchStatus.LINK_CLICKED, NOW);
        when(repository.findActiveForUpdate(USER_ID, MOVIE_ID)).thenReturn(Optional.of(expired));
        when(repository.createClick(USER_ID, MOVIE_ID, OTT_ID, NOW)).thenReturn(replacement);

        WatchService.OttClickResult result = service.clickOtt(USER_ID, MOVIE_ID, OTT_ID);

        assertThat(result.created()).isTrue();
        assertThat(result.response().watchRecordId()).isEqualTo(104L);
        InOrder order = inOrder(repository, eventPublisher);
        order.verify(repository).expireClick(103L, NOW);
        order.verify(repository).createClick(USER_ID, MOVIE_ID, OTT_ID, NOW);
        order.verify(eventPublisher)
                .publish(
                        KafkaEventType.OTT_CLICK,
                        USER_ID,
                        new OttClickEventPayload(MOVIE_ID, OTT_ID, 104L));
        verify(repository, never()).refreshClick(anyLong(), anyLong(), any());
    }

    @ParameterizedTest
    @EnumSource(
            value = WatchStatus.class,
            names = {"WATCHED_UNRATED", "RATED"})
    @DisplayName("완료 상태는 수정하지 않고 성공한 클릭 이벤트를 발행한다")
    void completedStatusIsPreservedAndClickIsPublished(WatchStatus status) {
        WatchRecordSnapshot completed = record(105L, status, null);
        when(repository.findActiveForUpdate(USER_ID, MOVIE_ID)).thenReturn(Optional.of(completed));

        WatchService.OttClickResult result = service.clickOtt(USER_ID, MOVIE_ID, OTT_ID);

        assertThat(result.created()).isFalse();
        assertThat(result.response().status()).isEqualTo(status);
        verify(repository, never()).createClick(anyLong(), anyLong(), anyLong(), any());
        verify(repository, never()).refreshClick(anyLong(), anyLong(), any());
        verify(repository, never()).expireClick(anyLong(), any());
        verify(eventPublisher)
                .publish(
                        KafkaEventType.OTT_CLICK,
                        USER_ID,
                        new OttClickEventPayload(MOVIE_ID, OTT_ID, 105L));
    }

    @Test
    @DisplayName("비활성 사용자와 미연결 영화·OTT 조합은 쓰기와 이벤트 발행 전에 거절한다")
    void invalidPrincipalOrMovieOttPairDoesNotPublish() {
        when(repository.lockActiveUser(USER_ID)).thenReturn(false);

        assertBusinessError(
                () -> service.clickOtt(USER_ID, MOVIE_ID, OTT_ID), ErrorCode.UNAUTHORIZED);
        verify(repository, never()).movieOffersOtt(anyLong(), anyLong());
        verifyNoInteractions(eventPublisher);

        when(repository.lockActiveUser(USER_ID)).thenReturn(true);
        when(repository.movieOffersOtt(MOVIE_ID, OTT_ID)).thenReturn(false);

        assertBusinessError(
                () -> service.clickOtt(USER_ID, MOVIE_ID, OTT_ID), ErrorCode.RESOURCE_NOT_FOUND);
        verify(repository, never()).createClick(anyLong(), anyLong(), anyLong(), any());
        verifyNoInteractions(eventPublisher);
    }

    private static void assertBusinessError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private static WatchRecordSnapshot record(long id, WatchStatus status, Instant expiresAt) {
        return new WatchRecordSnapshot(
                id,
                MOVIE_ID,
                status,
                OTT_ID,
                status == WatchStatus.RATED ? null : NOW,
                status == WatchStatus.RATED ? null : NOW.plusSeconds(2 * 86_400),
                expiresAt);
    }
}
