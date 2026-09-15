package com.feelm.backend.domain.watch.controller;

import com.feelm.backend.domain.watch.dto.request.OttClickRequest;
import com.feelm.backend.domain.watch.dto.response.OttClickResponse;
import com.feelm.backend.domain.watch.service.WatchService;
import com.feelm.backend.domain.watch.service.WatchService.OttClickResult;
import com.feelm.backend.global.error.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.beans.PropertyEditorSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/movies")
@RequiredArgsConstructor
public class OttClickController {

    private final WatchService watchService;

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
            summary = "OTT 링크 클릭 기록",
            description =
                    "로그인한 활성 사용자의 보러가기 클릭을 기록합니다. 활성 감상 기록이 LINK_CLICKED이면 같은 행의 OTT와 시각을 갱신하고, "
                            + "EXPIRED 이력만 있으면 새 LINK_CLICKED 기록을 만듭니다. WATCHED_UNRATED·RATED 상태는 유지하며, "
                            + "성공한 실제 호출마다 추천 행동 신호인 OTT_CLICK 아웃박스 이벤트를 한 건 저장합니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "활성 기록이 없어 새 LINK_CLICKED 감상 기록 생성",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = OttClickResponse.class))),
        @ApiResponse(
                responseCode = "200",
                description = "LINK_CLICKED 재클릭 갱신 또는 기존 WATCHED_UNRATED·RATED 상태 유지",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = OttClickResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "VALIDATION_FAILED: movieId 또는 ottId가 양의 64비트 정수가 아님",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "UNAUTHORIZED: Access Token이 없거나 사용자 계정이 활성 상태가 아님",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "RESOURCE_NOT_FOUND: 영화·OTT가 없거나 영화에 OTT가 연결되지 않음",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "INTERNAL_ERROR: 감상 기록 또는 아웃박스 저장 실패",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{movieId}/ott-clicks")
    public ResponseEntity<OttClickResponse> click(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "클릭한 영화 연결 OTT",
                            required = true)
                    @Valid
                    @RequestBody
                    OttClickRequest request) {
        OttClickResult result = watchService.clickOtt(userId, movieId, request.ottId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.response());
    }
}
