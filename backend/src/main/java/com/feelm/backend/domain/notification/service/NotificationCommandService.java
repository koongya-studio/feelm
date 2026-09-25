package com.feelm.backend.domain.notification.service;

import com.feelm.backend.domain.notification.model.NotificationType;
import com.feelm.backend.domain.notification.repository.NotificationRepository;
import com.feelm.backend.domain.notification.repository.WebPushDeliveryRepository;
import com.feelm.backend.domain.notification.repository.WebPushSubscriptionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class NotificationCommandService {

    private static final Duration PARTY_NOTIFICATION_RETENTION = Duration.ofDays(90);
    private static final Duration REPORT_NOTIFICATION_RETENTION = Duration.ofDays(90);

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

    /**
     * 공개한 반기 리포트의 발급 알림을 만든다(S15P21E106-858). 리포트 공개와 같은 트랜잭션에서 부른다.
     *
     * <p>앱 안 알림 목록으로만 알린다. Web Push 발송 대상은 파티 두 종류뿐이라 발송을 걸지 않는다.
     *
     * <p>보관은 90일이되 첫 발급을 기록할 수 있는 마지막 시각(다음 반기 공개 시각)을 넘기지 않는다. 그 뒤에는 알림이 여는 3.3이 첫 발급(API-080)을
     * {@code 409}로 거절해 막다른 화면이 되기 때문이다. 이미 그 시각이 지났으면 알림을 만들지 않는다.
     *
     * @param periodLabel 알림 문구에 넣을 반기 이름. 예: {@code 2026년 상반기}
     * @param issuableUntil 첫 발급을 기록할 수 있는 마지막 시각(배타)
     */
    public void createReportIssued(
            List<Long> reportIds, String periodLabel, Instant issuedAt, Instant issuableUntil) {
        Instant expiresAt = issuedAt.plus(REPORT_NOTIFICATION_RETENTION);
        if (issuableUntil.isBefore(expiresAt)) {
            expiresAt = issuableUntil;
        }
        if (!expiresAt.isAfter(issuedAt)) {
            return;
        }
        repository.createReportIssued(
                reportIds, periodLabel + " 취향 리포트가 도착했어요.", issuedAt, expiresAt);
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
