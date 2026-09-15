package com.feelm.backend.domain.auth.controller;

import com.feelm.backend.domain.auth.dto.request.OAuthCodeRequest;
import com.feelm.backend.domain.auth.dto.response.OAuthLinkResponse;
import com.feelm.backend.domain.auth.entity.OAuthProvider;
import com.feelm.backend.domain.auth.service.OAuthLinkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * API-009 소셜 계정 연결. 회원 전용이며 {@code SecurityConfig}의 공개 경로에 넣지 않는다.
 *
 * <p>경로는 사용자 리소스 아래에 있지만 유스케이스는 로그인 수단 관리이므로 {@code auth} 도메인에 둔다(컨벤션 3장).
 */
@RestController
@RequestMapping("/api/v1/users/me/oauth")
@RequiredArgsConstructor
public class OAuthLinkController {

    private final OAuthLinkService oAuthLinkService;

    @PostMapping("/{provider}")
    @ResponseStatus(HttpStatus.CREATED)
    OAuthLinkResponse link(
            @AuthenticationPrincipal Long userId,
            @PathVariable String provider,
            @RequestBody @Valid OAuthCodeRequest request) {
        return OAuthLinkResponse.from(
                oAuthLinkService.link(
                        userId,
                        OAuthProvider.from(provider),
                        request.code(),
                        request.redirectUri()));
    }
}
