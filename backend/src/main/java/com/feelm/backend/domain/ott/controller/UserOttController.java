package com.feelm.backend.domain.ott.controller;

import com.feelm.backend.domain.ott.dto.request.UserOttUpdateRequest;
import com.feelm.backend.domain.ott.dto.response.UserOttResponse;
import com.feelm.backend.domain.ott.service.UserOttService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 내 구독 OTT 조회·설정(API-026·027). 온보딩과 OTT 관리 화면이 같은 경로를 쓴다. */
@RestController
@RequestMapping("/api/v1/users/me/otts")
@RequiredArgsConstructor
public class UserOttController {

    private final UserOttService userOttService;

    /** API-026 내 구독 OTT 조회. */
    @GetMapping
    UserOttResponse getMyOtts(@AuthenticationPrincipal Long userId) {
        return userOttService.getMyOtts(userId);
    }

    /** API-027 내 구독 OTT 설정. 목록을 전체 교체한다. */
    @PutMapping
    UserOttResponse replaceMyOtts(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid UserOttUpdateRequest request) {
        return userOttService.replaceMyOtts(userId, request.ottIds());
    }
}
