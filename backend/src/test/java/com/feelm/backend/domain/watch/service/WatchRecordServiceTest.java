package com.feelm.backend.domain.watch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.notification.service.NotificationCommandService;
import com.feelm.backend.domain.watch.dto.request.WatchListCriteria;
import com.feelm.backend.domain.watch.model.WatchStatus;
import com.feelm.backend.domain.watch.repository.WatchRepository;
import com.feelm.backend.domain.watch.repository.WatchRepository.OwnedWatchRecord;
import com.feelm.backend.domain.watch.repository.WatchRepository.WatchRecordListRow;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.infrastructure.kafka.KafkaEventPublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

@DisplayName("감상 기록 조회·확인 서비스 규칙")
class WatchRecordServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-15T01:23:45.123456Z");
    private static final long USER_ID = 10L;
    private static final long RECORD_ID = 101L;

    private WatchRepository repository;
    private KafkaEventPublisher eventPublisher;
    private NotificationCommandService notificationCommandService;
    private WatchService service;

    @BeforeEach
    void setUp() {
        repository = mock(WatchRepository.class);
        eventPublisher = mock(KafkaEventPublisher.class);
        notificationCommandService = mock(NotificationCommandService.class);
        service =
                new WatchService(
                        repository,
                        eventPublisher,
                        notificationCommandService,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.lockActiveUser(USER_ID)).thenReturn(true);
    }

    @Test
    @DisplayName("목록은 활성 사용자를 먼저 잠근 뒤 만료 반영과 조회를 수행한다")
    void listUsesActiveUserExpireAndQueryOrder() {
        WatchListCriteria criteria = new WatchListCriteria(Set.of(), false, null, 20);
        WatchRecordListRow row = row();
        when(repository.findPage(USER_ID, criteria, null, NOW)).thenReturn(List.of(row));

        var response = service.getWatchRecords(USER_ID, criteria);

        assertThat(response.items()).hasSize(1);
        InOrder order = inOrder(repository);
        order.verify(repository).lockActiveUser(USER_ID);
        order.verify(repository).expireDue(USER_ID, NOW);
        order.verify(repository).findPage(USER_ID, criteria, null, NOW);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("비활성 사용자는 목록 만료와 조회 전에 401로 거절한다")
    void inactiveUserCannotList() {
        when(repository.lockActiveUser(USER_ID)).thenReturn(false);

        assertBusinessError(
                () ->
                        service.getWatchRecords(
                                USER_ID, new WatchListCriteria(Set.of(), false, null, 20)),
                ErrorCode.UNAUTHORIZED);

        verify(repository, never()).expireDue(anyLong(), any());
        verify(repository, never()).findPage(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("confirm은 활성 사용자 다음 소유 기록을 잠그고 조건부 전이를 수행한다")
    void confirmUsesLockOrderAndConditionalTransition() {
        when(repository.findOwnedForUpdate(RECORD_ID, USER_ID))
                .thenReturn(
                        Optional.of(
                                new OwnedWatchRecord(
                                        RECORD_ID, WatchStatus.LINK_CLICKED, NOW.plusSeconds(1))));

        var response = service.confirm(USER_ID, RECORD_ID, true);

        assertThat(response.status()).isEqualTo(WatchStatus.WATCHED_UNRATED);
        assertThat(response.watchedAt()).isEqualTo(NOW);
        InOrder order = inOrder(repository);
        order.verify(repository).lockActiveUser(USER_ID);
        order.verify(repository).findOwnedForUpdate(RECORD_ID, USER_ID);
        order.verify(repository).confirm(RECORD_ID, true, NOW);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("타인·미존재 기록은 상태 전이 전에 404로 숨긴다")
    void missingOwnedRecordIsNotFound() {
        when(repository.findOwnedForUpdate(RECORD_ID, USER_ID)).thenReturn(Optional.empty());

        assertBusinessError(
                () -> service.confirm(USER_ID, RECORD_ID, true), ErrorCode.RESOURCE_NOT_FOUND);

        verify(repository, never()).confirm(anyLong(), anyBoolean(), any());
    }

    @Test
    @DisplayName("만료 경계와 완료 상태는 조건부 update 전에 409다")
    void rejectsExpiredAndCompletedRecords() {
        when(repository.findOwnedForUpdate(RECORD_ID, USER_ID))
                .thenReturn(
                        Optional.of(
                                new OwnedWatchRecord(RECORD_ID, WatchStatus.LINK_CLICKED, NOW)));
        assertBusinessError(
                () -> service.confirm(USER_ID, RECORD_ID, true), ErrorCode.INVALID_WATCH_STATUS);

        when(repository.findOwnedForUpdate(RECORD_ID, USER_ID))
                .thenReturn(
                        Optional.of(
                                new OwnedWatchRecord(
                                        RECORD_ID, WatchStatus.RATED, NOW.plusSeconds(1))));
        assertBusinessError(
                () -> service.confirm(USER_ID, RECORD_ID, false), ErrorCode.INVALID_WATCH_STATUS);

        verify(repository, never()).confirm(anyLong(), anyBoolean(), any());
    }

    private static WatchRecordListRow row() {
        return new WatchRecordListRow(
                RECORD_ID,
                20L,
                "영화",
                "https://image.test/poster.jpg",
                WatchStatus.LINK_CLICKED,
                30L,
                "NETFLIX",
                NOW,
                NOW.plusSeconds(2 * 86_400),
                NOW.plusSeconds(7 * 86_400),
                null,
                null);
    }

    private static void assertBusinessError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}
