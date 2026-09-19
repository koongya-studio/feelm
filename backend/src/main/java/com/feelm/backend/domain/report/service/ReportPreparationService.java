package com.feelm.backend.domain.report.service;

import com.feelm.backend.domain.report.model.ReportContent;
import com.feelm.backend.domain.report.model.ReportPeriod;
import com.feelm.backend.domain.report.model.ReportSource;
import com.feelm.backend.domain.report.repository.ReportGenerationRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class ReportPreparationService {

    private static final int USER_BATCH_SIZE = 10;

    private final ReportGenerationRepository repository;
    private final ReportContentAssembler assembler;
    private final ObjectMapper objectMapper;

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public PreparationResult prepare(
            ReportPeriod period, Instant requestedSnapshotAt, boolean finalSnapshot) {
        return prepareLocked(period, requestedSnapshotAt, finalSnapshot, false);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public PreparationResult prepareScheduledFinal(ReportPeriod period) {
        return prepareLocked(period, period.cutoffExclusive(), true, true);
    }

    private PreparationResult prepareLocked(
            ReportPeriod period,
            Instant requestedSnapshotAt,
            boolean finalSnapshot,
            boolean enforceFinalWindow) {
        var lockAttempt = repository.tryLock(period);
        if (!lockAttempt.locked()) {
            return PreparationResult.busy();
        }
        if (finalSnapshot
                && (repository.findCompletedPreparedCount(period) != null
                        || repository.hasReady(period)
                        || repository.hasPreparedFinal(period))) {
            return new PreparationResult(true, 0, 0);
        }
        Instant snapshotAcquiredAt = lockAttempt.snapshotAt();
        if (enforceFinalWindow && !period.acceptsFinalSnapshotAt(snapshotAcquiredAt)) {
            throw new IllegalStateException(
                    "Final report snapshot is outside the 23:50–23:55 KST window: "
                            + snapshotAcquiredAt);
        }
        Instant effectiveEnd =
                requestedSnapshotAt.isBefore(period.cutoffExclusive())
                        ? requestedSnapshotAt
                        : period.cutoffExclusive();
        if (effectiveEnd.isBefore(period.startInclusive())) {
            throw new IllegalArgumentException("Snapshot precedes the report period");
        }

        List<Long> eligibleUserIds = repository.findEligibleUserIds(period, effectiveEnd);
        int deleted = repository.deleteIneligibleGenerating(period, eligibleUserIds);
        int prepared = 0;
        for (int start = 0; start < eligibleUserIds.size(); start += USER_BATCH_SIZE) {
            int end = Math.min(start + USER_BATCH_SIZE, eligibleUserIds.size());
            List<Long> batch = eligibleUserIds.subList(start, end);
            prepared +=
                    prepareBatch(period, effectiveEnd, snapshotAcquiredAt, finalSnapshot, batch);
        }
        if (finalSnapshot) {
            repository.recordCompletedFinalRun(period, snapshotAcquiredAt, prepared);
        }
        return new PreparationResult(true, prepared, deleted);
    }

    private int prepareBatch(
            ReportPeriod period,
            Instant effectiveEnd,
            Instant snapshotAcquiredAt,
            boolean finalSnapshot,
            List<Long> userIds) {
        List<ReportSource.Frame> frames = repository.findFrames(userIds, effectiveEnd);
        Map<Long, Long> ownerByFrame = new HashMap<>();
        Map<Long, List<ReportSource.Frame>> framesByUser = new HashMap<>();
        for (ReportSource.Frame frame : frames) {
            ownerByFrame.put(frame.frameId(), frame.userId());
            framesByUser.computeIfAbsent(frame.userId(), ignored -> new ArrayList<>()).add(frame);
        }

        Map<Long, List<ReportSource.Genre>> genresByUser =
                groupByOwner(
                        repository.findGenres(userIds, effectiveEnd),
                        ownerByFrame,
                        ReportSource.Genre::frameId);
        Map<Long, List<ReportSource.Country>> countriesByUser =
                groupByOwner(
                        repository.findCountries(userIds, effectiveEnd),
                        ownerByFrame,
                        ReportSource.Country::frameId);
        Map<Long, List<ReportSource.Person>> peopleByUser =
                groupByOwner(
                        repository.findPeople(userIds, effectiveEnd),
                        ownerByFrame,
                        ReportSource.Person::frameId);
        var usersWithPrevious =
                repository.findUsersWithReadyPreviousReport(userIds, period.previous());

        int prepared = 0;
        for (long userId : userIds) {
            ReportContent content =
                    assembler.assemble(
                            period,
                            effectiveEnd,
                            snapshotAcquiredAt,
                            finalSnapshot,
                            framesByUser.getOrDefault(userId, List.of()),
                            genresByUser.getOrDefault(userId, List.of()),
                            countriesByUser.getOrDefault(userId, List.of()),
                            peopleByUser.getOrDefault(userId, List.of()),
                            usersWithPrevious.contains(userId));
            repository.upsertGenerating(userId, period, serialize(content), Instant.now());
            prepared++;
        }
        return prepared;
    }

    private <T> Map<Long, List<T>> groupByOwner(
            List<T> attributes, Map<Long, Long> ownerByFrame, ToLongFunction<T> frameIdExtractor) {
        Map<Long, List<T>> grouped = new HashMap<>();
        for (T attribute : attributes) {
            Long userId = ownerByFrame.get(frameIdExtractor.applyAsLong(attribute));
            if (userId != null) {
                grouped.computeIfAbsent(userId, ignored -> new ArrayList<>()).add(attribute);
            }
        }
        return grouped;
    }

    private String serialize(ReportContent content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Report content cannot be serialized", exception);
        }
    }

    public record PreparationResult(boolean locked, int preparedCount, int deletedCount) {
        private static PreparationResult busy() {
            return new PreparationResult(false, 0, 0);
        }
    }
}
