package com.feelm.backend.domain.notification.controller;

import com.feelm.backend.domain.notification.dto.response.NotificationItemResponse;
import com.feelm.backend.domain.notification.dto.response.NotificationPageResponse;
import com.feelm.backend.domain.notification.dto.response.NotificationUnreadCountResponse;
import com.feelm.backend.domain.notification.service.NotificationService;
import com.feelm.backend.global.response.CursorPageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Notifications", description = "인앱 알림")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "인앱 알림 목록", description = "현재 사용자에게 노출 중인 알림을 최신순으로 조회한다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "인앱 알림 목록 조회 성공",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = NotificationPageResponse.class)))
    })
    @GetMapping
    public CursorPageResponse<NotificationItemResponse> list(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "서버가 직전 응답에서 발급한 불투명 커서") @RequestParam(required = false)
                    String cursor,
            @Parameter(
                            description = "페이지 크기(기본 20, 1~50)",
                            schema =
                                    @Schema(
                                            type = "integer",
                                            minimum = "1",
                                            maximum = "50",
                                            defaultValue = "20"))
                    @RequestParam(required = false)
                    Integer size) {
        return notificationService.list(userId, cursor, size);
    }

    @Operation(summary = "미확인 알림 개수")
    @GetMapping("/unread-count")
    public NotificationUnreadCountResponse unreadCount(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return notificationService.unreadCount(userId);
    }

    @Operation(summary = "알림 읽음 처리")
    @ApiResponse(responseCode = "204", description = "읽음 처리 성공")
    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<Void> markRead(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable long notificationId) {
        notificationService.markRead(userId, notificationId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "전체 알림 읽음 처리")
    @ApiResponse(responseCode = "204", description = "전체 읽음 처리 성공")
    @PatchMapping("/read-all")
    public ResponseEntity<Void> markAllRead(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        notificationService.markAllRead(userId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "알림 숨김", description = "현재 사용자의 알림을 목록과 미확인 개수에서 제외한다.")
    @ApiResponse(responseCode = "204", description = "알림 숨김 성공")
    @DeleteMapping("/{notificationId}")
    public ResponseEntity<Void> dismiss(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable long notificationId) {
        notificationService.dismiss(userId, notificationId);
        return ResponseEntity.noContent().build();
    }
}
