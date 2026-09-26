package com.feelm.backend.domain.social.controller;

import com.feelm.backend.domain.social.dto.request.PublicReviewCriteria;
import com.feelm.backend.domain.social.dto.response.ProfileReviewPageResponse;
import com.feelm.backend.domain.social.service.ProfileReviewService;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.security.TokenProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ProfileReviewController {
    private final ProfileReviewService service;
    private final TokenProvider tokenProvider;

    @Operation(
            summary = "사용자별 공개 한줄평 조회",
            description = "API-087. 선택 인증. 공개 프로필과 같은 접근 조건이며 PUBLIC 한줄평만 최신순으로 반환합니다.")
    @GetMapping("/api/v1/users/{publicId}/reviews")
    public ProfileReviewPageResponse getProfileReviews(
            @PathVariable String publicId,
            @RequestParam(required = false) String cursor,
            @Parameter(description = "페이지 크기(기본 10, 1~50)") @RequestParam(required = false)
                    String size,
            @Parameter(hidden = true) @RequestHeader(value = "Authorization", required = false)
                    String authorization,
            @Parameter(hidden = true) @AuthenticationPrincipal Long viewerId) {
        // 선택 인증에서도 잘못된/만료 인증을 비회원으로 강등하지 않는다.
        if (authorization != null) {
            if (!authorization.startsWith("Bearer ") || authorization.substring(7).isBlank()) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED);
            }
            viewerId = tokenProvider.parseAccessToken(authorization.substring(7).trim());
        }
        return service.getReviews(
                publicId, viewerId, PublicReviewCriteria.from(cursor, size == null ? "10" : size));
    }
}
