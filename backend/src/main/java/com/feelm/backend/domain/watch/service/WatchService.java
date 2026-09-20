package com.feelm.backend.domain.watch.service;

import com.feelm.backend.domain.notification.service.NotificationCommandService;
import com.feelm.backend.domain.watch.cursor.WatchRecordCursor;
import com.feelm.backend.domain.watch.dto.request.WatchListCriteria;
import com.feelm.backend.domain.watch.dto.response.OttClickResponse;
import com.feelm.backend.domain.watch.dto.response.WatchConfirmResponse;
import com.feelm.backend.domain.watch.dto.response.WatchRecordItemResponse;
import com.feelm.backend.domain.watch.event.OttClickEventPayload;
import com.feelm.backend.domain.watch.model.WatchStatus;
import com.feelm.backend.domain.watch.repository.WatchRepository;
import com.feelm.backend.domain.watch.repository.WatchRepository.OwnedWatchRecord;
import com.feelm.backend.domain.watch.repository.WatchRepository.WatchRecordListRow;
import com.feelm.backend.domain.watch.repository.WatchRepository.WatchRecordSnapshot;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.response.CursorPageResponse;
import com.feelm.backend.infrastructure.kafka.KafkaEventPublisher;
import com.feelm.backend.infrastructure.kafka.KafkaEventType;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WatchService {

    private final WatchRepository repository;
    private final KafkaEventPublisher eventPublisher;
    private final NotificationCommandService notificationCommandService;
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
        if (record.status() == WatchStatus.LINK_CLICKED) {
            notificationCommandService.upsertWatchConfirmation(
                    userId, record.watchRecordId(), record.confirmDueAt(), record.expiresAt());
        }
        return new OttClickResult(created, OttClickResponse.from(record));
    }

    @Transactional
    public CursorPageResponse<WatchRecordItemResponse> getWatchRecords(
            long userId, WatchListCriteria criteria) {
        requireActiveUser(userId);
        Instant now = nowMicros();
        repository.expireDue(userId, now);
        WatchRecordCursor cursor = WatchRecordCursor.decode(criteria.cursor(), userId, criteria);
        List<WatchRecordListRow> results = repository.findPage(userId, criteria, cursor, now);
        boolean hasNext = results.size() > criteria.size();
        List<WatchRecordListRow> page = hasNext ? results.subList(0, criteria.size()) : results;
        String nextCursor = null;
        if (hasNext) {
            WatchRecordListRow last = page.getLast();
            nextCursor =
                    new WatchRecordCursor(last.clickedAt(), last.watchRecordId())
                            .encode(userId, criteria);
        }
        return new CursorPageResponse<>(
                page.stream().map(WatchRecordItemResponse::from).toList(), nextCursor, hasNext);
    }

    @Transactional
    public WatchConfirmResponse confirm(long userId, long watchRecordId, boolean watched) {
        requireActiveUser(userId);
        Instant now = nowMicros();
        OwnedWatchRecord record =
                repository
                        .findOwnedForUpdate(watchRecordId, userId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        boolean clickedInTime =
                record.status() == WatchStatus.LINK_CLICKED
                        && record.expiresAt() != null
                        && record.expiresAt().isAfter(now);
        boolean cancelUnrated = !watched && record.status() == WatchStatus.WATCHED_UNRATED;
        if (!clickedInTime && !cancelUnrated) {
            throw new BusinessException(ErrorCode.INVALID_WATCH_STATUS);
        }
        repository.confirm(watchRecordId, watched, now);
        notificationCommandService.closeWatchConfirmation(userId, watchRecordId, now);
        return new WatchConfirmResponse(
                watchRecordId,
                watched ? WatchStatus.WATCHED_UNRATED : WatchStatus.EXPIRED,
                watched ? now : null);
    }

    private void requireActiveUser(long userId) {
        if (!repository.lockActiveUser(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    private Instant nowMicros() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static boolean isExpired(WatchRecordSnapshot record, Instant now) {
        return record.expiresAt() != null && !record.expiresAt().isAfter(now);
    }

    public record OttClickResult(boolean created, OttClickResponse response) {}
}
