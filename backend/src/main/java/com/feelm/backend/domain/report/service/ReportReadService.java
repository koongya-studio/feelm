package com.feelm.backend.domain.report.service;

import com.feelm.backend.domain.report.dto.ReportDetailResponse;
import com.feelm.backend.domain.report.dto.ReportIssueResponse;
import com.feelm.backend.domain.report.dto.ReportListResponse;
import com.feelm.backend.domain.report.model.ReportContent;
import com.feelm.backend.domain.report.model.ReportHalf;
import com.feelm.backend.domain.report.model.ReportPeriod;
import com.feelm.backend.domain.report.repository.ReportReadRepository;
import com.feelm.backend.domain.report.repository.ReportReadRepository.ReportRow;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportReadService {

    private final ReportReadRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportListResponse list(long userId) {
        Instant now = clock.instant();
        ReportPeriod latest = ReportPeriod.latestReleasedAt(now);
        List<ReportRow> reports = repository.findAllOwned(userId);
        ReportRow current =
                reports.stream()
                        .filter(row -> row.period().equals(latest))
                        .findFirst()
                        .orElse(null);
        List<ReportListResponse.Item> items =
                reports.stream()
                        .filter(row -> "READY".equals(row.status()))
                        .filter(
                                row ->
                                        row.issuedAt() != null
                                                || !now.isBefore(row.period().nextPublicationAt()))
                        .map(this::toItem)
                        .toList();
        boolean preparing =
                current == null
                        ? !repository.hasCompletedFinalRun(latest)
                        : !"READY".equals(current.status());
        ReportListResponse.Current currentResponse =
                current == null
                        ? null
                        : new ReportListResponse.Current(
                                current.id(),
                                current.period(),
                                current.status(),
                                current.issuedAt());
        return new ReportListResponse(items, currentResponse, preparing);
    }

    public ReportDetailResponse detail(long userId, long reportId) {
        ReportRow row = owned(userId, reportId);
        // Publication is checked first so an unpublished report stays hidden as 404 instead of
        // revealing that it is still generating.
        if (clock.instant().isBefore(row.period().publicationAt())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        requireReady(row);
        ReportContent content = parseContent(row.content());
        return new ReportDetailResponse(
                row.id(),
                row.period(),
                content.summary(),
                content.topGenres(),
                content.topActors(),
                content.topDirectors(),
                content.characterCard(),
                content.flavorChanges(),
                row.createdAt(),
                content.fourCut(),
                content.genreShares(),
                content.countryShares(),
                content.eraShares(),
                content.keywords(),
                content.comparisons(),
                content.highlights());
    }

    @Transactional
    public ReportIssueResponse issue(long userId, long reportId) {
        ReportRow row = owned(userId, reportId);
        requireReady(row);
        if (row.issuedAt() != null) {
            return new ReportIssueResponse(row.id(), row.issuedAt());
        }

        Instant now = clock.instant();
        if (now.isBefore(row.period().publicationAt())
                || !now.isBefore(row.period().nextPublicationAt())) {
            ReportRow refreshed = owned(userId, reportId);
            if (refreshed.issuedAt() != null) {
                return new ReportIssueResponse(refreshed.id(), refreshed.issuedAt());
            }
            throw new BusinessException(ErrorCode.CONFLICT);
        }

        repository.issueIfUnissued(userId, reportId, now);
        // A concurrent request may have won the conditional update. Always return the stored first
        // time.
        ReportRow issued = owned(userId, reportId);
        if (issued.issuedAt() == null) {
            throw new IllegalStateException("Report issue was not persisted");
        }
        return new ReportIssueResponse(issued.id(), issued.issuedAt());
    }

    private ReportRow owned(long userId, long reportId) {
        return repository
                .findOwned(userId, reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private void requireReady(ReportRow row) {
        if (!"READY".equals(row.status())) {
            throw new BusinessException(ErrorCode.REPORT_GENERATING);
        }
    }

    private ReportListResponse.Item toItem(ReportRow row) {
        String halfName = row.period().half() == ReportHalf.H1 ? "상반기" : "하반기";
        return new ReportListResponse.Item(
                row.id(),
                row.period(),
                row.status(),
                row.period().year() + " " + halfName + " 취향 리포트",
                row.createdAt());
    }

    private ReportContent parseContent(String json) {
        try {
            return objectMapper.readValue(json, ReportContent.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored report content is invalid", exception);
        }
    }
}
