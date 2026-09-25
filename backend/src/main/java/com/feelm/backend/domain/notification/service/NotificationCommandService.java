package com.feelm.backend.domain.notification.service;

import com.feelm.backend.domain.notification.model.NotificationType;
import com.feelm.backend.domain.notification.repository.NotificationRepository;
import com.feelm.backend.domain.notification.repository.WebPushDeliveryRepository;
import com.feelm.backend.domain.notification.repository.WebPushSubscriptionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class NotificationCommandService {

    private static final Duration PARTY_NOTIFICATION_RETENTION = Duration.ofDays(90);

    private final NotificationRepository repository;
    private final WebPushDeliveryRepository deliveryRepository;
    private final WebPushSubscriptionRepository subscriptionRepository;
    private final Clock clock;

    /** 파티 초대 알림을 만들고 발송을 건다. 파티 초대 알림을 끈 사용자에게는 둘 다 하지 않는다(S15P21E106-857). */
    public void createPartyInvitation(long userId, long invitationId, Instant createdAt) {
        repository
                .createPartyInvitation(
                        userId,
                        invitationId,
                        createdAt,
                        createdAt.plus(PARTY_NOTIFICATION_RETENTION))
                .ifPresent(deliveryRepository::enqueue);
    }

    public void createPartyInvitationAccepted(
            long ownerId,
            long invitationId,
            long partyId,
            String inviteeNickname,
            Instant acceptedAt) {
        long notificationId =
                repository.createPartyInvitationAccepted(
                        ownerId,
                        invitationId,
                        partyId,
                        inviteeNickname,
                        acceptedAt,
                        acceptedAt.plus(PARTY_NOTIFICATION_RETENTION));
        deliveryRepository.enqueue(notificationId);
    }

    public void upsertWatchConfirmation(
            long userId, long watchRecordId, Instant confirmDueAt, Instant expiresAt) {
        repository.upsertWatchConfirmation(userId, watchRecordId, confirmDueAt, expiresAt);
    }

    public void closePartyInvitation(long userId, long invitationId, Instant closedAt) {
        repository
                .close(userId, NotificationType.PARTY_INVITE, invitationId, closedAt)
                .ifPresent(
                        notificationId ->
                                deliveryRepository.cancelForNotification(
                                        notificationId, closedAt, "NOTIFICATION_CLOSED"));
    }

    public void closeWatchConfirmation(long userId, long watchRecordId, Instant closedAt) {
        repository
                .close(userId, NotificationType.WATCH_CONFIRM, watchRecordId, closedAt)
                .ifPresent(
                        notificationId ->
                                deliveryRepository.cancelForNotification(
                                        notificationId, closedAt, "NOTIFICATION_CLOSED"));
    }

    public void deleteByUserId(long userId) {
        Instant now = Instant.now(clock);
        deliveryRepository.cancelForUser(userId, now, "ACCOUNT_WITHDRAWN");
        subscriptionRepository.deactivateByUserId(userId, now);
        repository.deleteByUserId(userId);
    }
}
