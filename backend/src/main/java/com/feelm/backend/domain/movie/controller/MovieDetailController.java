package com.feelm.backend.domain.movie.controller;

import com.feelm.backend.domain.movie.dto.response.MovieDetailResponse;
import com.feelm.backend.domain.movie.service.MovieDetailService;
import com.feelm.backend.global.error.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
public class MovieDetailController {

    private final MovieDetailService movieDetailService;

    @InitBinder
    void bindDecimalIds(WebDataBinder binder) {
        // 기본 Long 변환의 공백 제거·16진수 허용과 빈 query 값의 null 변환을 막는다.
        for (Class<?> type : new Class<?>[] {Long.class, long.class}) {
            binder.registerCustomEditor(
                    type,
                    new PropertyEditorSupport() {
                        @Override
                        public void setAsText(String text) {
                            setValue(Long.parseLong(text));
                        }
                    });
        }
    }

    @Operation(
            summary = "영화 상세 조회",
            description =
                    "영화 메타데이터, 출연진·감독, 전체 평점 집계와 OTT 제공처를 조회합니다. "
                            + "선택 인증 API로 비회원도 조회할 수 있으며 myRating, myWatchStatus, recommendation은 null이고 "
                            + "OTT subscribed는 false입니다. 유효한 Access Token이 있으면 본인 평가·감상 상태·구독을 포함합니다. "
                            + "recommendationItemId는 조회 영화와 일치하고 본인 개인 추천 또는 현재 참여 중인 파티 추천일 때만 "
                            + "추천 이유를 반환하며, 권한이 없으면 recommendation=null과 200을 반환합니다. "
                            + "유효하지 않은 토큰·Refresh Token·다른 인증 스킴은 비회원으로 처리합니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "영화 상세 조회 성공",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = MovieDetailResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "VALIDATION_FAILED: ID 형식 오류 또는 양의 64비트 정수 범위 초과",
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
                description = "INTERNAL_ERROR: 내부 데이터 계약 위반 등 서버 오류",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        """
                                                        {"code":"INTERNAL_ERROR","message":"서버 내부 오류가 발생했습니다."}
                                                        """)))
    })
    @GetMapping("/{movieId}")
    MovieDetailResponse getDetail(
            @Parameter(
                            description = "서비스 영화 ID. 양의 64비트 정수(1~9223372036854775807)",
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
            @Parameter(
                            description =
                                    "추천 카드의 항목 ID. 양의 64비트 정수(1~9223372036854775807), 생략 가능. 영화 일치·열람 권한 확인 후 추천 이유 반환",
                            required = false,
                            schema =
                                    @Schema(
                                            type = "integer",
                                            format = "int64",
                                            minimum = "1",
                                            example = "3301"))
                    @RequestParam(required = false)
                    @Positive
                    Long recommendationItemId,
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return movieDetailService.getDetail(movieId, recommendationItemId, userId);
    }
}
