package com.feelm.backend.domain.notification.service;

import com.feelm.backend.domain.notification.repository.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class NotificationRetentionCleaner {

    private final NotificationRepository repository;
    private final Clock clock;

    @Scheduled(
            initialDelayString = "${feelm.notification.cleanup-interval:1h}",
            fixedDelayString = "${feelm.notification.cleanup-interval:1h}")
    @Transactional
    public void deleteExpired() {
        repository.deleteExpired(Instant.now(clock));
    }
}
