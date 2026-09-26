package com.feelm.backend.domain.notification.controller;

import com.feelm.backend.domain.notification.service.NotificationStreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "인앱 알림")
public class NotificationStreamController {
    private final NotificationStreamService streams;

    @Operation(
            summary = "인앱 알림 변경 스트림",
            description = "API-085. notification-changed 수신 시 목록·미확인 개수를 다시 조회한다.")
    @ApiResponse(
            responseCode = "200",
            description = "알림 갱신 SSE 스트림",
            content = @Content(mediaType = "text/event-stream", schema = @Schema(type = "string")))
    @GetMapping(
            value = "/api/v1/notifications/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(hidden = true) @RequestHeader(HttpHeaders.AUTHORIZATION)
                    String authorization) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Accel-Buffering", "no")
                .body(
                        streams.subscribe(
                                userId, authorization.substring("Bearer ".length()).trim()));
    }
}
