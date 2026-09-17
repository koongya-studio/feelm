package com.feelm.backend.domain.watch.controller;

import com.feelm.backend.domain.watch.dto.request.MyRatingCriteria;
import com.feelm.backend.domain.watch.dto.response.MyRatingPageResponse;
import com.feelm.backend.domain.watch.service.MyRatingService;
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
@RequestMapping("/api/v1/users/me/ratings")
@RequiredArgsConstructor
public class MyRatingController {

    private final MyRatingService service;

    @Operation(
            summary = "내 평가·미평가 영화 목록",
            description =
                    "ACTIVE 회원 본인의 평가를 조회합니다. 필터 생략 시 기존 평가-only 최신순이며, "
                            + "ALL은 평가와 감상 후 미평가 영화를 한 페이지로 합칩니다. "
                            + "미평가 영화는 별점순에서 항상 뒤에 옵니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "내 평가 목록 조회 성공",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = MyRatingPageResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "VALIDATION_FAILED: 잘못된 filter·sort·size·cursor",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description =
                        "UNAUTHORIZED: 인증이 없거나 유효하지 않거나 ACTIVE 회원이 아님; "
                                + "TOKEN_EXPIRED: Access Token 만료",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "INTERNAL_ERROR: 평가 목록 조회 실패",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public MyRatingPageResponse getMyRatings(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(
                            description = "생략 시 평가만; ALL, COMMENTED, UNRATED",
                            schema =
                                    @Schema(
                                            type = "string",
                                            allowableValues = {"ALL", "COMMENTED", "UNRATED"}))
                    @RequestParam(required = false)
                    String filter,
            @Parameter(
                            description = "정렬(기본 LATEST)",
                            schema =
                                    @Schema(
                                            type = "string",
                                            allowableValues = {
                                                "LATEST",
                                                "SCORE_DESC",
                                                "SCORE_ASC",
                                                "OLDEST"
                                            },
                                            defaultValue = "LATEST"))
                    @RequestParam(required = false)
                    String sort,
            @Parameter(description = "서버가 직전 응답에서 발급한 불투명 cursor") @RequestParam(required = false)
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
                    String size) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return service.getMyRatings(userId, MyRatingCriteria.from(filter, sort, cursor, size));
    }
}
