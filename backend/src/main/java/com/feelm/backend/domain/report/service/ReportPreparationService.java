package com.feelm.backend.domain.report.service;

import com.feelm.backend.domain.report.model.CharacterAxisMedians;
import com.feelm.backend.domain.report.model.CharacterAxisMetrics;
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

        /*
         * 캐릭터 카드 판정이 대상자 전체의 중앙값 기준 상대 판정이라 조립보다 먼저 지표를 한 번 훑는다
         * (업무 -779). 같은 반기의 모든 리포트가 같은 스냅샷을 쓰고, 리포트가 생성 시점 스냅샷이므로
         * 조회할 때 다시 계산하지 않는다(API-060 처리 규칙).
         *
         * 이 패스는 프레임만 읽는다 — 네 축 지표와 동값 판정용 표준편차가 전부 프레임에서 나온다.
         * 프레임을 패스 사이에 들고 있지 않고 2패스에서 다시 조회한다. 대상자 수에 비례해 메모리가
         * 커지는 것을 막는 쪽을 골랐다(플랜 7장).
         */
        CharacterAxisMedians medians = collectAxisMedians(period, effectiveEnd, eligibleUserIds);

        int prepared = 0;
        for (int start = 0; start < eligibleUserIds.size(); start += USER_BATCH_SIZE) {
            int end = Math.min(start + USER_BATCH_SIZE, eligibleUserIds.size());
            List<Long> batch = eligibleUserIds.subList(start, end);
            prepared +=
                    prepareBatch(
                            period,
                            effectiveEnd,
                            snapshotAcquiredAt,
                            finalSnapshot,
                            batch,
                            medians);
        }
        if (finalSnapshot) {
            repository.recordCompletedFinalRun(period, snapshotAcquiredAt, prepared);
        }
        return new PreparationResult(true, prepared, deleted);
    }

    /** 대상자 전체의 축 지표를 훑어 중앙값 스냅샷을 만든다. 프레임만 읽는다 */
    private CharacterAxisMedians collectAxisMedians(
            ReportPeriod period, Instant effectiveEnd, List<Long> eligibleUserIds) {
        List<CharacterAxisMetrics> population = new ArrayList<>(eligibleUserIds.size());
        for (int start = 0; start < eligibleUserIds.size(); start += USER_BATCH_SIZE) {
            int end = Math.min(start + USER_BATCH_SIZE, eligibleUserIds.size());
            List<Long> batch = eligibleUserIds.subList(start, end);
            Map<Long, List<ReportSource.Frame>> framesByUser = new HashMap<>();
            for (ReportSource.Frame frame : repository.findFrames(batch, effectiveEnd)) {
                framesByUser
                        .computeIfAbsent(frame.userId(), ignored -> new ArrayList<>())
                        .add(frame);
            }
            for (long userId : batch) {
                population.add(
                        CharacterCardSelector.metrics(
                                framesByUser.getOrDefault(userId, List.of()),
                                period.startInclusive(),
                                effectiveEnd));
            }
        }
        return CharacterCardSelector.medians(population);
    }

    private int prepareBatch(
            ReportPeriod period,
            Instant effectiveEnd,
            Instant snapshotAcquiredAt,
            boolean finalSnapshot,
            List<Long> userIds,
            CharacterAxisMedians medians) {
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
                            usersWithPrevious.contains(userId),
                            medians);
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
