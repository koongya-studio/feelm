package com.feelm.backend.domain.notification.service;

import com.feelm.backend.domain.notification.cursor.NotificationCursor;
import com.feelm.backend.domain.notification.dto.response.NotificationItemResponse;
import com.feelm.backend.domain.notification.dto.response.NotificationUnreadCountResponse;
import com.feelm.backend.domain.notification.repository.NotificationRepository;
import com.feelm.backend.domain.notification.repository.NotificationRepository.NotificationRow;
import com.feelm.backend.domain.notification.repository.WebPushDeliveryRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.response.CursorPageResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;

    private final NotificationRepository repository;
    private final WebPushDeliveryRepository deliveryRepository;
    private final Clock clock;

    public CursorPageResponse<NotificationItemResponse> list(
            long userId, String encodedCursor, Integer requestedSize) {
        requireActiveUser(userId);
        int size = requestedSize == null ? DEFAULT_SIZE : requestedSize;
        if (size < 1 || size > MAX_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        NotificationCursor cursor = NotificationCursor.decode(encodedCursor);
        List<NotificationRow> results = repository.findPage(userId, cursor, now(), size + 1);
        boolean hasNext = results.size() > size;
        List<NotificationRow> page = hasNext ? results.subList(0, size) : results;
        String nextCursor = null;
        if (hasNext) {
            NotificationRow last = page.getLast();
            nextCursor = new NotificationCursor(last.notifiedAt(), last.notificationId()).encode();
        }
        return new CursorPageResponse<>(
                page.stream().map(NotificationItemResponse::from).toList(), nextCursor, hasNext);
    }

    public NotificationUnreadCountResponse unreadCount(long userId) {
        requireActiveUser(userId);
        return new NotificationUnreadCountResponse(repository.countUnread(userId, now()));
    }

    @Transactional
    public void markRead(long userId, long notificationId) {
        requireActiveUser(userId);
        Instant now = now();
        if (!repository.markRead(userId, notificationId, now)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        deliveryRepository.cancelForNotification(notificationId, now, "NOTIFICATION_READ");
    }

    @Transactional
    public void markAllRead(long userId) {
        requireActiveUser(userId);
        Instant now = now();
        repository.markAllRead(userId, now);
        deliveryRepository.cancelReadForUser(userId, now);
    }

    @Transactional
    public void dismiss(long userId, long notificationId) {
        requireActiveUser(userId);
        Instant now = now();
        if (!repository.dismiss(userId, notificationId, now)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        deliveryRepository.cancelForNotification(notificationId, now, "NOTIFICATION_DISMISSED");
    }

    private void requireActiveUser(long userId) {
        if (!repository.isActiveUser(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
