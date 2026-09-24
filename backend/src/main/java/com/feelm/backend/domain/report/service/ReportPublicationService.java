package com.feelm.backend.domain.report.service;

import com.feelm.backend.domain.report.model.ReportPeriod;
import com.feelm.backend.domain.report.repository.ReportGenerationRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReportPublicationService {

    private final ReportGenerationRepository repository;

    @Transactional(readOnly = true)
    public List<ReportPeriod> findPreparedFinalPeriods() {
        return repository.findPreparedFinalPeriods();
    }

    @Transactional(readOnly = true)
    public boolean isReady(ReportPeriod period) {
        return repository.hasReady(period);
    }

    @Transactional(readOnly = true)
    public boolean hasPreparedFinal(ReportPeriod period) {
        return repository.hasPreparedFinal(period);
    }

    @Transactional(readOnly = true)
    public boolean hasCompletedFinalRun(ReportPeriod period) {
        return repository.findCompletedPreparedCount(period) != null;
    }

    @Transactional(readOnly = true)
    public boolean completedWithoutEligibleUsers(ReportPeriod period) {
        return Integer.valueOf(0).equals(repository.findCompletedPreparedCount(period));
    }

    @Transactional
    public int publishPreparedFinal(ReportPeriod period) {
        return repository.publishPreparedFinal(period);
    }
}
