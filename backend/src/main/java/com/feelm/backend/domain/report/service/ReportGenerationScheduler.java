package com.feelm.backend.domain.report.service;

import com.feelm.backend.domain.report.model.ReportHalf;
import com.feelm.backend.domain.report.model.ReportPeriod;
import com.feelm.backend.domain.report.service.ReportGenerationCoordinator.CatchUpResult;
import com.feelm.backend.domain.report.service.ReportGenerationCoordinator.PublicationResult;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "feelm.report.generation", name = "enabled", havingValue = "true")
public class ReportGenerationScheduler {

    private final ReportGenerationCoordinator coordinator;

    @Scheduled(cron = "0 20 0,6,12,18 * * *", zone = "Asia/Seoul")
    public void refreshPreview() {
        var result = coordinator.refreshPreview(Instant.now());
        log.info(
                "Report preview refresh completed: locked={}, preparedCount={}, deletedCount={}",
                result.locked(),
                result.preparedCount(),
                result.deletedCount());
    }

    @Scheduled(cron = "0 50-54 23 30 6 *", zone = "Asia/Seoul")
    public void finalizeFirstHalf() {
        int year = ZonedDateTime.now(ReportPeriod.KOREA).getYear();
        prepareFinal(new ReportPeriod(year, ReportHalf.H1));
    }

    @Scheduled(cron = "0 50-54 23 31 12 *", zone = "Asia/Seoul")
    public void finalizeSecondHalf() {
        int year = ZonedDateTime.now(ReportPeriod.KOREA).getYear();
        prepareFinal(new ReportPeriod(year, ReportHalf.H2));
    }

    @Scheduled(cron = "0 0-29 0 1 1,7 *", zone = "Asia/Seoul")
    public void retryRecentCutoff() {
        ZonedDateTime now = ZonedDateTime.now(ReportPeriod.KOREA);
        ReportPeriod period =
                now.getMonthValue() == 7
                        ? new ReportPeriod(now.getYear(), ReportHalf.H1)
                        : new ReportPeriod(now.getYear() - 1, ReportHalf.H2);
        PublicationResult result = publishPrepared(period);
        if (now.getMinute() == 29 && result == PublicationResult.NOT_PREPARED) {
            log.error(
                    "Report was not prepared by the publication deadline: year={}, half={}",
                    period.year(),
                    period.half());
        }
    }

    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Seoul")
    public void recoverPreparedPublication() {
        for (ReportPeriod period : coordinator.findPreparedPeriodsReadyToPublish(Instant.now())) {
            publishPrepared(period);
        }
    }

    // 기동 직후 동기로 돌리면 readiness가 늦어 배포 health check가 실패할 수 있어 1분 뒤로 미룬다.
    @Scheduled(initialDelay = 1, fixedDelay = 60, timeUnit = TimeUnit.MINUTES)
    public void catchUpMissedFinal() {
        CatchUpResult result = coordinator.catchUpMissedFinal(Instant.now());
        log.info("Missed report catch-up completed: result={}", result);
    }

    private void prepareFinal(ReportPeriod period) {
        var result = coordinator.prepareFinal(period);
        log.info(
                "Report final snapshot preparation completed: year={}, half={}, locked={}, preparedCount={}, deletedCount={}",
                period.year(),
                period.half(),
                result.locked(),
                result.preparedCount(),
                result.deletedCount());
    }

    private PublicationResult publishPrepared(ReportPeriod period) {
        PublicationResult result = coordinator.publishPrepared(period);
        log.info(
                "Prepared report publication attempt completed: year={}, half={}, result={}",
                period.year(),
                period.half(),
                result);
        return result;
    }
}
