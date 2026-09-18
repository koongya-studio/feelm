package com.feelm.backend.domain.notification.controller;

import com.feelm.backend.domain.notification.dto.request.WebPushSubscriptionRequest;
import com.feelm.backend.domain.notification.dto.response.WebPushConfigResponse;
import com.feelm.backend.domain.notification.dto.response.WebPushSubscriptionResponse;
import com.feelm.backend.domain.notification.service.WebPushSubscriptionService;
import com.feelm.backend.global.error.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Notifications", description = "인앱 알림 및 브라우저 푸시")
@RestController
@RequestMapping("/api/v1/notifications/push")
@RequiredArgsConstructor
public class WebPushController {

    private final WebPushSubscriptionService service;

    @Operation(summary = "브라우저 푸시 설정 조회")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "공개 Web Push 설정 조회 성공",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = WebPushConfigResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "인증되지 않았거나 비활성 회원",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/config")
    public WebPushConfigResponse config(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return service.config(userId);
    }

    @Operation(summary = "브라우저 푸시 구독 등록 또는 갱신")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "구독 등록 또는 갱신 성공",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema =
                                        @Schema(
                                                implementation =
                                                        WebPushSubscriptionResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "구독 정보 검증 실패",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "인증되지 않았거나 비활성 회원",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "503",
                description = "Web Push 비활성화",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/subscriptions")
    public WebPushSubscriptionResponse register(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Valid @RequestBody WebPushSubscriptionRequest request) {
        return service.register(userId, request);
    }

    @Operation(summary = "브라우저 푸시 구독 해제")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "구독 해제 성공"),
        @ApiResponse(
                responseCode = "401",
                description = "인증되지 않았거나 비활성 회원",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "현재 사용자 소유 구독 없음",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/subscriptions/{subscriptionId}")
    public ResponseEntity<Void> unregister(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable long subscriptionId) {
        service.unregister(userId, subscriptionId);
        return ResponseEntity.noContent().build();
    }
}
