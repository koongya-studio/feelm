package com.feelm.backend.domain.watch.controller;

import com.feelm.backend.domain.watch.dto.request.RatingCreateRequest;
import com.feelm.backend.domain.watch.dto.response.RatingCreateResponse;
import com.feelm.backend.domain.watch.service.RatingCreateService;
import com.feelm.backend.global.error.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/movies")
@RequiredArgsConstructor
public class RatingCreateController {

    private final RatingCreateService service;

    @Operation(
            summary = "평가 생성",
            description =
                    "영화에 0.5점 단위 평가와 선택적 한줄평을 저장합니다. 평점, 감상 상태, 프레임과 추천 갱신 이벤트는 같은 트랜잭션으로 처리됩니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "평가 생성",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = RatingCreateResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "VALIDATION_FAILED: 요청 값이 평가 계약을 위반함",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "UNAUTHORIZED: 인증 정보가 없거나 활성 사용자가 아님",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "RESOURCE_NOT_FOUND: 영화가 존재하지 않음",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "409",
                description = "RATING_ALREADY_EXISTS: 같은 영화에 이미 평가가 존재함",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "INTERNAL_ERROR: 평가 또는 연관 데이터 저장 실패",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{movieId}/ratings")
    public ResponseEntity<RatingCreateResponse> create(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(
                            description = "평가할 영화 ID. 양의 64비트 정수",
                            schema = @Schema(type = "integer", format = "int64", minimum = "1"))
                    @PathVariable
                    @Positive
                    long movieId,
            @Valid @RequestBody RatingCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(userId, movieId, request));
    }
}
