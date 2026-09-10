package com.feelm.backend.domain.user.controller;

import com.feelm.backend.domain.user.dto.request.ProfileUpdateRequest;
import com.feelm.backend.domain.user.dto.response.UserProfileResponse;
import com.feelm.backend.domain.user.service.UserProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 프로필 조회·수정(API-014·015).
 *
 * <p>닉네임 사용 가능 확인(API-016)은 인증 없이 호출하는 공개 API라 컨트롤러를 나눠 둔다. 비밀번호 변경(API-012)이 같은 이유로 {@code auth}
 * 도메인에 따로 있는 것과 같은 기준이다.
 */
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;

    /** API-014 내 프로필 조회. */
    @GetMapping
    UserProfileResponse getProfile(@AuthenticationPrincipal Long userId) {
        return userProfileService.getProfile(userId);
    }

    /** API-015 내 프로필 수정. 전달한 필드만 변경한다. */
    @PatchMapping
    UserProfileResponse updateProfile(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid ProfileUpdateRequest request) {
        return userProfileService.updateProfile(userId, request);
    }
}
