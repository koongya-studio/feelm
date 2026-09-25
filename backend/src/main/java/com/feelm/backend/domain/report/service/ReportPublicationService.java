package com.feelm.backend.domain.report.service;

import com.feelm.backend.domain.notification.service.NotificationCommandService;
import com.feelm.backend.domain.report.model.ReportHalf;
import com.feelm.backend.domain.report.model.ReportPeriod;
import com.feelm.backend.domain.report.repository.ReportGenerationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReportPublicationService {

    private final ReportGenerationRepository repository;
    private final NotificationCommandService notificationCommandService;
    private final Clock clock;

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

    /**
     * 준비를 마친 반기 리포트를 공개하고, 같은 트랜잭션에서 공개한 리포트의 발급 알림을 만든다(S15P21E106-858).
     *
     * <p>공개와 알림이 함께 커밋되거나 함께 되돌아간다. 알림 쓰기가 실패하면 그 반기 공개도 되돌아가고 매시 정각 재시도가 다시 공개한다. 이미 공개한 리포트는 다시
     * 공개되지 않으므로 재실행해도 알림이 늘지 않는다.
     *
     * <p>알림은 첫 발급(API-080)을 기록할 수 있는 다음 반기 공개 시각까지만 둔다.
     */
    @Transactional
    public int publishPreparedFinal(ReportPeriod period) {
        List<Long> published = repository.publishPreparedFinal(period);
        notificationCommandService.createReportIssued(
                published, periodLabel(period), Instant.now(clock), period.nextPublicationAt());
        return published.size();
    }

    private static String periodLabel(ReportPeriod period) {
        return period.year() + "년 " + (period.half() == ReportHalf.H1 ? "상반기" : "하반기");
    }
}
