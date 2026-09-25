package com.feelm.backend.domain.social.controller;

import com.feelm.backend.domain.social.dto.request.PublicReviewCriteria;
import com.feelm.backend.domain.social.dto.response.PublicReviewPageResponse;
import com.feelm.backend.domain.social.service.PublicReviewService;
import com.feelm.backend.global.error.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.constraints.Positive;
import java.beans.PropertyEditorSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/movies")
@RequiredArgsConstructor
public class PublicReviewController {

    private final PublicReviewService publicReviewService;

    @InitBinder
    void bindDecimalIds(WebDataBinder binder) {
        binder.registerCustomEditor(
                long.class,
                new PropertyEditorSupport() {
                    @Override
                    public void setAsText(String text) {
                        setValue(Long.parseLong(text));
                    }
                });
    }

    @Operation(
            summary = "공개 한줄평 목록 조회",
            description =
                    "로그인한 ACTIVE 회원이 영화 한줄평을 최신순으로 조회합니다. "
                            + "타인의 PUBLIC과 본인의 PUBLIC·PRIVATE 한줄평만 포함하며, "
                            + "양방향 차단 상대, 비활성 작성자와 한줄평 공개 범위(reviewVisibility)를 "
                            + "PRIVATE으로 둔 타인 작성자는 제외합니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "공개 한줄평 목록 조회 성공",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = PublicReviewPageResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "VALIDATION_FAILED: 잘못된 movieId·size·cursor",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description =
                        "UNAUTHORIZED: 인증이 없거나 유효하지 않거나 ACTIVE 회원이 아님; "
                                + "TOKEN_EXPIRED: Access Token이 만료됨",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "RESOURCE_NOT_FOUND: 존재하지 않는 영화",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "INTERNAL_ERROR: 한줄평 조회 실패",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{movieId}/reviews")
    public PublicReviewPageResponse getReviews(
            @Parameter(
                            description = "서비스 영화 ID. 양의 64비트 정수",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "integer",
                                            format = "int64",
                                            minimum = "1",
                                            example = "550"))
                    @PathVariable
                    @Positive
                    long movieId,
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
                    String size,
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return publicReviewService.getReviews(
                movieId, userId, PublicReviewCriteria.from(cursor, size));
    }
}
