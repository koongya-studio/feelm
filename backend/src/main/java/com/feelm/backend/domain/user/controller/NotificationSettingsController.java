package com.feelm.backend.domain.user.controller;

import com.feelm.backend.domain.user.dto.request.NotificationSettingsUpdateRequest;
import com.feelm.backend.domain.user.dto.response.NotificationSettingsResponse;
import com.feelm.backend.domain.user.service.NotificationSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 알림 수신 설정 조회·변경(API-017·018). 설정 화면이 같은 경로를 쓴다. */
@RestController
@RequestMapping("/api/v1/users/me/notification-settings")
@RequiredArgsConstructor
public class NotificationSettingsController {

    private final NotificationSettingsService notificationSettingsService;

    /** API-017 알림 설정 조회. */
    @GetMapping
    NotificationSettingsResponse getMySettings(@AuthenticationPrincipal Long userId) {
        return notificationSettingsService.getMySettings(userId);
    }

    /** API-018 알림 설정 변경. 세 항목을 전체 교체한다. */
    @PutMapping
    NotificationSettingsResponse replaceMySettings(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid NotificationSettingsUpdateRequest request) {
        return notificationSettingsService.replaceMySettings(userId, request);
    }
}
