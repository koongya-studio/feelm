package com.feelm.backend.domain.notification.service;

import com.feelm.backend.domain.notification.repository.NotificationRepository;
import com.feelm.backend.domain.notification.repository.WebPushDeliveryRepository;
import com.feelm.backend.domain.notification.repository.WebPushSubscriptionRepository;
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
    private final WebPushSubscriptionRepository subscriptionRepository;
    private final WebPushDeliveryRepository deliveryRepository;
    private final Clock clock;

    @Scheduled(
            initialDelayString = "${feelm.notification.cleanup-interval:1h}",
            fixedDelayString = "${feelm.notification.cleanup-interval:1h}")
    @Transactional
    public void deleteExpired() {
        Instant now = Instant.now(clock);
        subscriptionRepository.deactivateExpired(now);
        deliveryRepository.cancelIneligible(now);
        repository.deleteExpired(now);
    }
}
