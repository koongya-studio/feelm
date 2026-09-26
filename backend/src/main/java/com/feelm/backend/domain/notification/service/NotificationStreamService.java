package com.feelm.backend.domain.notification.service;

import com.feelm.backend.domain.notification.repository.NotificationRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.security.TokenProvider;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** SSE는 갱신 신호만 보내고 알림 내용과 읽음 상태는 REST 정본에서 조회한다. */
@Service
@RequiredArgsConstructor
public class NotificationStreamService {
    private final NotificationRepository repository;
    private final TokenProvider tokens;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Connection> connections = new ConcurrentHashMap<>();
    private final ExecutorService sender = Executors.newVirtualThreadPerTaskExecutor();

    public SseEmitter subscribe(long userId, String token) {
        if (tokens.parseAccessToken(token) != userId || !repository.isActiveUser(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        // 무기한 연결 대신 주기적으로 인증된 새 연결을 만든다.
        SseEmitter emitter = createEmitter();
        UUID id = UUID.randomUUID();
        Connection connection = new Connection(userId, token, emitter);
        connections.put(id, connection);
        Runnable remove = () -> connections.remove(id, connection);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        send(id, connection, true);
        return emitter;
    }

    SseEmitter createEmitter() {
        return new SseEmitter(300_000L);
    }

    @TransactionalEventListener
    public void changed(NotificationChangedEvent event) {
        connections.forEach(
                (id, connection) -> {
                    if (connection.userId == event.userId()) send(id, connection, true);
                });
    }

    /** 예약 알림 도래·만료 및 전달 누락을 DB 정본과 대조하고 heartbeat를 보낸다. */
    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    public void reconcile() {
        connections.forEach((id, connection) -> send(id, connection, false));
    }

    private void send(UUID id, Connection connection, boolean changed) {
        if (changed) connection.dirty.set(true);
        if (!connection.sending.compareAndSet(false, true)) return;
        sender.execute(
                () -> {
                    try {
                        do {
                            boolean dirty = connection.dirty.getAndSet(false);
                            if (!connections.containsKey(id)) return;
                            if (tokens.parseAccessToken(connection.token) != connection.userId
                                    || !repository.isActiveUser(connection.userId)) {
                                connections.remove(id, connection);
                                connection.emitter.complete();
                                return;
                            }
                            String version =
                                    repository.streamVersion(connection.userId, clock.instant());
                            if (dirty || !Objects.equals(version, connection.version)) {
                                connection.emitter.send(
                                        SseEmitter.event().name("notification-changed").data("{}"));
                                connection.version = version;
                            } else {
                                connection.emitter.send(SseEmitter.event().comment("heartbeat"));
                            }
                        } while (connection.dirty.get());
                    } catch (BusinessException exception) {
                        connections.remove(id, connection);
                        connection.emitter.complete();
                    } catch (IOException | RuntimeException exception) {
                        connections.remove(id, connection);
                        // IOException은 컨테이너가 처리한다. DB/런타임 오류도 연결을 정리해 재연결한다.
                        connection.emitter.complete();
                    } finally {
                        connection.sending.set(false);
                        if (connection.dirty.get() && connections.containsKey(id))
                            send(id, connection, false);
                    }
                });
    }

    @PreDestroy
    public void close() {
        connections.values().forEach(connection -> connection.emitter.complete());
        connections.clear();
        sender.shutdownNow();
    }

    private static final class Connection {
        final long userId;
        final String token;
        final SseEmitter emitter;
        final AtomicBoolean sending = new AtomicBoolean();
        final AtomicBoolean dirty = new AtomicBoolean();
        String version;

        Connection(long userId, String token, SseEmitter emitter) {
            this.userId = userId;
            this.token = token;
            this.emitter = emitter;
        }
    }
}
