package com.feelm.backend.domain.report.controller;

import static com.feelm.backend.domain.report.service.ReportImageStorage.MAX_PNG_BYTES;

import com.feelm.backend.domain.report.dto.response.ReportImageResponse;
import com.feelm.backend.domain.report.service.ReportImageDownloadLimiter;
import com.feelm.backend.domain.report.service.ReportImageService;
import com.feelm.backend.domain.report.service.ReportImageUploadLimiter;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReportImageController {

    private final ReportImageService service;
    private final ReportImageUploadLimiter uploadLimiter;
    private final ReportImageDownloadLimiter downloadLimiter;

    @PostMapping(value = "/reports/{reportId}/image", consumes = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<ReportImageResponse> create(
            @AuthenticationPrincipal Long userId,
            @PathVariable @Positive long reportId,
            HttpServletRequest request)
            throws IOException {
        if (request.getContentLengthLong() > MAX_PNG_BYTES) {
            throw new BusinessException(ErrorCode.REPORT_IMAGE_TOO_LARGE);
        }
        return uploadLimiter.withPermit(
                () -> {
                    ReportImageResponse existing = service.reuseExistingOrReject(userId, reportId);
                    if (existing != null) {
                        return ResponseEntity.status(201).body(existing);
                    }
                    byte[] png = request.getInputStream().readNBytes(MAX_PNG_BYTES + 1);
                    return ResponseEntity.status(201).body(service.create(userId, reportId, png));
                });
    }

    @GetMapping("/report-images/{imageId}/download")
    public void download(
            @PathVariable @Positive long imageId,
            @RequestParam(required = false) String expires,
            @RequestParam(required = false) String signature,
            HttpServletResponse response)
            throws IOException {
        downloadLimiter.withPermit(
                () -> {
                    byte[] png = service.download(imageId, expires, signature);
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.setContentType(MediaType.IMAGE_PNG_VALUE);
                    response.setContentLengthLong(png.length);
                    response.setHeader(
                            HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"feelm-report-" + imageId + ".png\"");
                    response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
                    response.setHeader("X-Content-Type-Options", "nosniff");
                    response.getOutputStream().write(png);
                    response.flushBuffer();
                    return null;
                });
    }
}
