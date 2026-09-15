package com.feelm.backend.domain.watch.service;

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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WatchService {

    private final WatchRepository repository;
    private final KafkaEventPublisher eventPublisher;
    private final Clock clock;

    @Transactional
    public OttClickResult clickOtt(long userId, long movieId, long ottId) {
        if (!repository.lockActiveUser(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!repository.movieOffersOtt(movieId, ottId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        Instant now = Instant.now(clock);
        WatchRecordSnapshot current = repository.findActiveForUpdate(userId, movieId).orElse(null);
        boolean created;
        WatchRecordSnapshot record;
        if (current == null) {
            created = true;
            record = repository.createClick(userId, movieId, ottId, now);
        } else if (current.status() == WatchStatus.LINK_CLICKED) {
            if (isExpired(current, now)) {
                repository.expireClick(current.watchRecordId(), now);
                record = repository.createClick(userId, movieId, ottId, now);
                created = true;
            } else {
                record = repository.refreshClick(current.watchRecordId(), ottId, now);
                created = false;
            }
        } else {
            record = current;
            created = false;
        }

        eventPublisher.publish(
                KafkaEventType.OTT_CLICK,
                userId,
                new OttClickEventPayload(movieId, ottId, record.watchRecordId()));
        return new OttClickResult(created, OttClickResponse.from(record));
    }

    private static boolean isExpired(WatchRecordSnapshot record, Instant now) {
        return record.expiresAt() != null && !record.expiresAt().isAfter(now);
    }

    public record OttClickResult(boolean created, OttClickResponse response) {}
}
