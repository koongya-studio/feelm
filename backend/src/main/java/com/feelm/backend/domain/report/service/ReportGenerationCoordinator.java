package com.feelm.backend.domain.report.service;

import com.feelm.backend.domain.report.model.ReportHalf;
import com.feelm.backend.domain.report.model.ReportPeriod;
import com.feelm.backend.domain.report.service.ReportPreparationService.PreparationResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReportGenerationCoordinator {

    private static final ReportPeriod CATCH_UP_PERIOD = new ReportPeriod(2026, ReportHalf.H1);
    private static final Duration CATCH_UP_DELAY = Duration.ofMinutes(30);

    private final ReportPreparationService preparationService;
    private final ReportPublicationService publicationService;

    public List<ReportPeriod> findPreparedPeriodsReadyToPublish(Instant now) {
        return publicationService.findPreparedFinalPeriods().stream()
                .filter(period -> !now.isBefore(period.cutoffExclusive().plusSeconds(10 * 60)))
                .filter(publicationService::hasPreparedFinal)
                .toList();
    }

    public PreparationResult refreshPreview(Instant now) {
        ReportPeriod period = ReportPeriod.containing(now);
        return preparationService.prepare(period, now, false);
    }

    public PreparationResult prepareFinal(ReportPeriod period) {
        return preparationService.prepareScheduledFinal(period);
    }

    /*
     * 리포트 기능 배포 전에 마감되어 마감 배치가 한 번도 돌지 않은 2026 H1만 현재 데이터로 한 번 늦게
     * 집계한다(API-059 처리 규칙의 늦은 보충 예외, 업무 -839). 이후 반기의 마감 실패는 기존 규칙대로
     * `준비 중`과 운영 알림으로 남긴다. 정상 공개 재시도가 끝나는 공개 30분 뒤부터만 돈다. 완료 기록이
     * 남으면 대상 0명이어도 다시 돌지 않고, 동시 실행은 준비 단계의 advisory lock과 완료 기록 확인이 막는다.
     */
    public CatchUpResult catchUpMissedFinal(Instant now) {
        ReportPeriod period = ReportPeriod.latestReleasedAt(now);
        if (!period.equals(CATCH_UP_PERIOD)) {
            return CatchUpResult.NOT_TARGET;
        }
        if (now.isBefore(period.publicationAt().plus(CATCH_UP_DELAY))) {
            return CatchUpResult.NOT_DUE;
        }
        if (publicationService.hasCompletedFinalRun(period) || publicationService.isReady(period)) {
            return CatchUpResult.NOT_MISSED;
        }
        preparationService.prepare(period, now, true);
        return switch (publishPrepared(period)) {
            case READY -> CatchUpResult.READY;
            case ALREADY_READY -> CatchUpResult.ALREADY_READY;
            case NO_ELIGIBLE_USERS -> CatchUpResult.NO_ELIGIBLE_USERS;
            case NOT_PREPARED -> CatchUpResult.NOT_PREPARED;
        };
    }

    public PublicationResult publishPrepared(ReportPeriod period) {
        if (publicationService.isReady(period)) {
            return PublicationResult.ALREADY_READY;
        }
        if (!publicationService.hasPreparedFinal(period)) {
            return publicationService.completedWithoutEligibleUsers(period)
                    ? PublicationResult.NO_ELIGIBLE_USERS
                    : PublicationResult.NOT_PREPARED;
        }

        int published = publicationService.publishPreparedFinal(period);
        if (published > 0) {
            return PublicationResult.READY;
        }
        return publicationService.isReady(period)
                ? PublicationResult.ALREADY_READY
                : publicationService.completedWithoutEligibleUsers(period)
                        ? PublicationResult.NO_ELIGIBLE_USERS
                        : PublicationResult.NOT_PREPARED;
    }

    public enum PublicationResult {
        READY,
        ALREADY_READY,
        NO_ELIGIBLE_USERS,
        NOT_PREPARED
    }

    public enum CatchUpResult {
        NOT_TARGET,
        NOT_DUE,
        NOT_MISSED,
        READY,
        ALREADY_READY,
        NO_ELIGIBLE_USERS,
        NOT_PREPARED
    }
}
