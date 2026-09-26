package com.feelm.backend.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import com.feelm.backend.domain.notification.repository.NotificationRepository;
import com.feelm.backend.domain.notification.repository.WebPushDeliveryRepository;
import com.feelm.backend.domain.notification.repository.WebPushSubscriptionRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.security.TokenProvider;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class NotificationStreamServiceTest {
    final NotificationRepository repository = mock(NotificationRepository.class);
    final TokenProvider tokens = mock(TokenProvider.class);
    final BlockingQueue<SseEmitter> emitters = new LinkedBlockingQueue<>();
    final NotificationStreamService streams =
            new NotificationStreamService(repository, tokens, Clock.systemUTC()) {
                @Override
                SseEmitter createEmitter() {
                    return emitters.remove();
                }
            };

    @BeforeEach
    void setup() {
        when(repository.isActiveUser(anyLong())).thenReturn(true);
        when(repository.streamVersion(anyLong(), any())).thenReturn("v1");
        when(tokens.parseAccessToken("a")).thenReturn(10L);
        when(tokens.parseAccessToken("b")).thenReturn(20L);
    }

    @AfterEach
    void cleanup() {
        streams.close();
    }

    SseEmitter connect(long userId, String token) throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        emitters.add(emitter);
        streams.subscribe(userId, token);
        verify(emitter, timeout(2000)).send(any(SseEmitter.SseEventBuilder.class));
        return emitter;
    }

    @Test
    void userIsolationAndMultipleSessions() throws Exception {
        SseEmitter a1 = connect(10, "a");
        SseEmitter a2 = connect(10, "a");
        SseEmitter b = connect(20, "b");
        streams.changed(new NotificationChangedEvent(10));
        verify(a1, timeout(2000).times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(a2, timeout(2000).times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(b, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void authenticationAndActiveAccount() {
        assertThatThrownBy(() -> streams.subscribe(20, "a")).isInstanceOf(BusinessException.class);
        when(repository.isActiveUser(10)).thenReturn(false);
        assertThatThrownBy(() -> streams.subscribe(10, "a")).isInstanceOf(BusinessException.class);
    }

    @Test
    void expiredTokenClosesConnectionBeforeSending() throws Exception {
        SseEmitter emitter = connect(10, "a");
        when(tokens.parseAccessToken("a"))
                .thenThrow(new BusinessException(ErrorCode.TOKEN_EXPIRED));
        streams.reconcile();
        verify(emitter, timeout(2000)).complete();
        streams.changed(new NotificationChangedEvent(10));
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void connectionCleanupDoesNotRemoveOtherSession() throws Exception {
        SseEmitter a1 = connect(10, "a");
        SseEmitter a2 = connect(10, "a");
        var callback = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(a1).onCompletion(callback.capture());
        callback.getValue().run();
        streams.changed(new NotificationChangedEvent(10));
        verify(a2, timeout(2000).times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(a1, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void sendFailureRemovesConnection() throws Exception {
        SseEmitter emitter = connect(10, "a");
        doThrow(new java.io.IOException("disconnected"))
                .when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));
        streams.changed(new NotificationChangedEvent(10));
        verify(emitter, timeout(2000)).complete();
        streams.changed(new NotificationChangedEvent(10));
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void scheduledVisibilityAndHeartbeat() throws Exception {
        SseEmitter emitter = connect(10, "a");
        streams.reconcile();
        verify(emitter, timeout(2000).times(2)).send(any(SseEmitter.SseEventBuilder.class));
        when(repository.streamVersion(anyLong(), any())).thenReturn("v2");
        streams.reconcile();
        verify(emitter, timeout(2000).times(3)).send(any(SseEmitter.SseEventBuilder.class));
        var frames = org.mockito.ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(3)).send(frames.capture());
        assertThat(frame(frames.getAllValues().get(0)))
                .contains("event:notification-changed", "data:{}");
        assertThat(frame(frames.getAllValues().get(1)))
                .contains(":heartbeat")
                .doesNotContain("notification-changed");
        assertThat(frame(frames.getAllValues().get(2))).contains("event:notification-changed");
    }

    private String frame(SseEmitter.SseEventBuilder builder) {
        return builder.build().stream()
                .map(value -> value.getData().toString())
                .collect(java.util.stream.Collectors.joining());
    }

    @Test
    void stateChangesThroughRealServicesAfterCommit() throws Exception {
        SseEmitter emitter = connect(10, "a");
        SseEmitter other = connect(20, "b");
        var delivery = mock(WebPushDeliveryRepository.class);
        when(repository.markRead(anyLong(), anyLong(), any())).thenReturn(true);
        when(repository.dismiss(anyLong(), anyLong(), any())).thenReturn(true);
        when(repository.createPartyInvitation(anyLong(), anyLong(), any(), any()))
                .thenReturn(Optional.of(7L));
        when(repository.close(anyLong(), any(), anyLong(), any())).thenReturn(Optional.of(7L));
        when(repository.createPartyInvitationAccepted(
                        anyLong(), anyLong(), anyLong(), anyString(), any(), any()))
                .thenReturn(7L);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(TransactionConfig.class);
            context.registerBean(NotificationStreamService.class, () -> streams);
            context.refresh();
            var queries = new NotificationService(repository, delivery, Clock.systemUTC(), context);
            var commands =
                    new NotificationCommandService(
                            repository,
                            delivery,
                            mock(WebPushSubscriptionRepository.class),
                            Clock.systemUTC(),
                            context);
            var transaction = new TransactionTemplate(context.getBean(TestTransactions.class));
            Instant now = Instant.now();
            List<Runnable> actions =
                    List.of(
                            () -> queries.markRead(10, 7),
                            () -> queries.markAllRead(10),
                            () -> queries.dismiss(10, 7),
                            () -> commands.createPartyInvitation(10, 7, now),
                            () -> commands.createPartyInvitationAccepted(10, 7, 1, "nickname", now),
                            () -> commands.upsertWatchConfirmation(10, 7, now, now.plusSeconds(60)),
                            () -> commands.closePartyInvitation(10, 7, now),
                            () -> commands.closeWatchConfirmation(10, 7, now),
                            () -> commands.deleteByUserId(10));
            int expected = 1;
            for (Runnable action : actions) {
                transaction.executeWithoutResult(status -> action.run());
                verify(emitter, timeout(2000).times(++expected))
                        .send(any(SseEmitter.SseEventBuilder.class));
                verify(other, times(1)).send(any(SseEmitter.SseEventBuilder.class));
            }
            transaction.executeWithoutResult(
                    status -> {
                        queries.markRead(10, 7);
                        status.setRollbackOnly();
                    });
            verify(emitter, times(expected)).send(any(SseEmitter.SseEventBuilder.class));
        }
    }

    @Test
    void afterCommitOnlyAndRollbackDoesNotSend() throws Exception {
        SseEmitter emitter = connect(10, "a");
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(TransactionConfig.class);
            context.registerBean(NotificationStreamService.class, () -> streams);
            context.refresh();
            TransactionTemplate transaction =
                    new TransactionTemplate(context.getBean(TestTransactions.class));
            transaction.executeWithoutResult(
                    status -> {
                        context.publishEvent(new NotificationChangedEvent(10));
                        try {
                            verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
                        } catch (java.io.IOException e) {
                            throw new RuntimeException(e);
                        }
                        status.setRollbackOnly();
                    });
            verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
            transaction.executeWithoutResult(
                    status -> {
                        context.publishEvent(new NotificationChangedEvent(10));
                        try {
                            verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
                        } catch (java.io.IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
            verify(emitter, timeout(2000).times(2)).send(any(SseEmitter.SseEventBuilder.class));
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class TransactionConfig {
        @Bean
        TestTransactions transactions() {
            return new TestTransactions();
        }
    }

    static class TestTransactions extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
