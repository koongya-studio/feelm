package com.feelm.backend.domain.notification.service;

import com.feelm.backend.domain.notification.model.NotificationType;
import com.feelm.backend.domain.notification.repository.NotificationRepository;
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

    private static final Duration PARTY_INVITATION_RETENTION = Duration.ofDays(90);

    private final NotificationRepository repository;

    public void createPartyInvitation(long userId, long invitationId, Instant createdAt) {
        repository.createPartyInvitation(
                userId, invitationId, createdAt, createdAt.plus(PARTY_INVITATION_RETENTION));
    }

    public void upsertWatchConfirmation(
            long userId, long watchRecordId, Instant confirmDueAt, Instant expiresAt) {
        repository.upsertWatchConfirmation(userId, watchRecordId, confirmDueAt, expiresAt);
    }

    public void closePartyInvitation(long userId, long invitationId, Instant closedAt) {
        repository.close(userId, NotificationType.PARTY_INVITE, invitationId, closedAt);
    }

    public void closeWatchConfirmation(long userId, long watchRecordId, Instant closedAt) {
        repository.close(userId, NotificationType.WATCH_CONFIRM, watchRecordId, closedAt);
    }

    public void deleteByUserId(long userId) {
        repository.deleteByUserId(userId);
    }
}
