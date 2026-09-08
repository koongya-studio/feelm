package com.feelm.backend.domain.auth.controller;

import com.feelm.backend.domain.auth.dto.request.PasswordChangeRequest;
import com.feelm.backend.domain.auth.service.PasswordChangeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비밀번호 변경 엔드포인트(API-012).
 *
 * <p>경로는 {@code /users/me} 아래이지만 자격 증명을 다루므로 구현은 {@code auth} 도메인에 둔다(컨벤션 3장). 프로필 조회·수정(API-014·
 * 015)과는 인증 정책과 실패 경로가 달라 컨트롤러도 나눠 둔다.
 */
@RestController
@RequestMapping("/api/v1/users/me/password")
@RequiredArgsConstructor
public class PasswordChangeController {

    private final PasswordChangeService passwordChangeService;

    /** API-012 비밀번호 변경. */
    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid PasswordChangeRequest request) {
        passwordChangeService.change(userId, request.currentPassword(), request.newPassword());
    }
}
