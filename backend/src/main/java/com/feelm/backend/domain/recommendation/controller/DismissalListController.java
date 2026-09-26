package com.feelm.backend.domain.recommendation.controller;

import com.feelm.backend.domain.recommendation.dto.response.DismissalListResponse;
import com.feelm.backend.domain.recommendation.service.DismissalListService;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.error.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me/dismissals")
@RequiredArgsConstructor
public class DismissalListController {
    private final DismissalListService service;

    @Operation(
            summary = "내 관심 없음 영화 목록",
            description = "ACTIVE 회원 본인의 관심 없음 영화를 표시 시각·ID 내림차순으로 조회합니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "본인 관심 없음 목록",
                content = @Content(schema = @Schema(implementation = DismissalListResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "VALIDATION_FAILED: 잘못된 size 또는 cursor",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "UNAUTHORIZED: 인증 없음 또는 비활성 회원; TOKEN_EXPIRED: 토큰 만료",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "INTERNAL_ERROR: 조회 실패",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public DismissalListResponse getList(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "같은 사용자의 직전 응답 cursor") @RequestParam(required = false)
                    String cursor,
            @Parameter(
                            description = "페이지 크기",
                            schema =
                                    @Schema(
                                            type = "integer",
                                            minimum = "1",
                                            maximum = "50",
                                            defaultValue = "20"))
                    @RequestParam(required = false)
                    String size) {
        if (userId == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return service.getList(userId, cursor, size);
    }
}
