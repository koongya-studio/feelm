package com.feelm.backend.domain.report.controller;

import com.feelm.backend.domain.report.dto.ReportDetailResponse;
import com.feelm.backend.domain.report.dto.ReportIssueResponse;
import com.feelm.backend.domain.report.dto.ReportListResponse;
import com.feelm.backend.domain.report.service.ReportReadService;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReportReadController {

    private final ReportReadService service;

    @GetMapping("/users/me/reports")
    public ReportListResponse list(@AuthenticationPrincipal Long userId) {
        return service.list(userId);
    }

    @GetMapping("/reports/{reportId}")
    public ReportDetailResponse detail(
            @AuthenticationPrincipal Long userId, @PathVariable @Positive long reportId) {
        return service.detail(userId, reportId);
    }

    @PostMapping("/reports/{reportId}/issue")
    public ReportIssueResponse issue(
            @AuthenticationPrincipal Long userId, @PathVariable @Positive long reportId) {
        return service.issue(userId, reportId);
    }
}
