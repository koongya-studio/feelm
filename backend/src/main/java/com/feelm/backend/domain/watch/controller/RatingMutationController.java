package com.feelm.backend.domain.watch.controller;

import com.feelm.backend.domain.watch.dto.request.RatingUpdateRequest;
import com.feelm.backend.domain.watch.dto.response.RatingCreateResponse;
import com.feelm.backend.domain.watch.service.RatingMutationService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ratings")
@RequiredArgsConstructor
public class RatingMutationController {

    private final RatingMutationService service;

    @Operation(
            summary = "평가 수정",
            description =
                    "본인 평가의 별점, 한줄평, 공개 범위와 스포일러 여부를 전체 교체합니다. 기존 프레임은 유지하고 영화 평점 집계와 RATING UPDATE 이벤트를 같은 트랜잭션에 기록합니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "수정된 평가",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = RatingCreateResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "VALIDATION_FAILED: 경로 ID 또는 요청 본문 오류",
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
                description = "RESOURCE_NOT_FOUND: 평가가 없거나 다른 사용자의 평가",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "415",
                description = "UNSUPPORTED_MEDIA_TYPE: Content-Type 누락 또는 비지원",
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
    @PutMapping("/{ratingId}")
    public RatingCreateResponse update(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(
                            description = "본인 평가 ID. 양의 64비트 정수",
                            schema = @Schema(type = "integer", format = "int64", minimum = "1"))
                    @PathVariable
                    @Positive
                    long ratingId,
            @Valid @RequestBody RatingUpdateRequest request) {
        return service.update(userId, ratingId, request);
    }

    @Operation(
            summary = "평가 삭제",
            description =
                    "본인 평가와 연결 프레임을 삭제하고 감상 기록은 보존합니다. 영화 평점 집계와 RATING DELETE 이벤트를 같은 트랜잭션에 기록합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "평가 삭제", content = @Content),
        @ApiResponse(
                responseCode = "400",
                description = "VALIDATION_FAILED: 경로 ID 오류",
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
                description = "RESOURCE_NOT_FOUND: 평가가 없거나 다른 사용자의 평가",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "INTERNAL_ERROR: 평가 또는 연관 데이터 삭제 실패",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{ratingId}")
    public ResponseEntity<Void> delete(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(
                            description = "본인 평가 ID. 양의 64비트 정수",
                            schema = @Schema(type = "integer", format = "int64", minimum = "1"))
                    @PathVariable
                    @Positive
                    long ratingId) {
        service.delete(userId, ratingId);
        return ResponseEntity.noContent().build();
    }
}
