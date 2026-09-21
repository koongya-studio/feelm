package com.feelm.backend.domain.user.controller;

import com.feelm.backend.domain.user.dto.request.AccountWithdrawalRequest;
import com.feelm.backend.domain.user.service.UserWithdrawalService;
import com.feelm.backend.global.security.RefreshTokenCookieFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API-013 회원 탈퇴.
 *
 * <p>조회·수정({@link UserProfileController})과 같은 경로지만 컨트롤러를 나눠 둔다. 탈퇴는 Refresh 쿠키까지 다루므로 응답을 {@code
 * ResponseEntity}로 만들어야 하고, 프로필 쪽의 단순한 본문 반환과 섞지 않는다.
 */
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserWithdrawalController {

    private final UserWithdrawalService userWithdrawalService;
    private final RefreshTokenCookieFactory cookieFactory;

    /**
     * 계정을 탈퇴 처리하고 Refresh 쿠키를 지운다.
     *
     * <p>쿠키는 이 요청에 실려 오지 않는다. {@code Path}가 {@code /api/v1/auth}라 인증 경로의 요청에만 붙기 때문이다. 그래도 만료 쿠키를
     * 내려보내는 것은 {@code Set-Cookie}가 이름과 {@code Path}로 지우기 때문이며, 로그아웃(API-005)과 같은 처리다.
     */
    @DeleteMapping
    ResponseEntity<Void> withdraw(
            @AuthenticationPrincipal Long userId, @RequestBody AccountWithdrawalRequest request) {
        userWithdrawalService.withdraw(userId, request);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookieFactory.expire().toString())
                .build();
    }
}
