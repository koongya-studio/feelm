package com.feelm.backend.domain.report.service;

import com.feelm.backend.domain.report.model.ReportPeriod;
import com.feelm.backend.domain.report.service.ReportPreparationService.PreparationResult;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReportGenerationCoordinator {

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
}
