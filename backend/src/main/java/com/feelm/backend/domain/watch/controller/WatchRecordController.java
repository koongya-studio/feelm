package com.feelm.backend.domain.watch.controller;

import com.feelm.backend.domain.watch.dto.request.WatchConfirmRequest;
import com.feelm.backend.domain.watch.dto.request.WatchListCriteria;
import com.feelm.backend.domain.watch.dto.response.WatchConfirmResponse;
import com.feelm.backend.domain.watch.dto.response.WatchRecordItemResponse;
import com.feelm.backend.domain.watch.dto.response.WatchRecordPageResponse;
import com.feelm.backend.domain.watch.service.WatchService;
import com.feelm.backend.global.error.ErrorResponse;
import com.feelm.backend.global.response.CursorPageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.beans.PropertyEditorSupport;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

@RestController
@RequestMapping("/api/v1/watch-records")
@RequiredArgsConstructor
public class WatchRecordController {

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
            summary = "감상 기록 목록",
            description =
                    "로그인한 활성 사용자의 감상 기록을 상태별로 조회합니다. 7일 만료 경계에 도달한 LINK_CLICKED 기록은 "
                            + "같은 트랜잭션에서 EXPIRED로 반영하며, 커서는 사용자와 필터 조건에 묶입니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "감상 기록 목록 조회 성공",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = WatchRecordPageResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "VALIDATION_FAILED: 잘못된 상태·confirmDue·size·cursor, 인덱스·중첩 파라미터 표기",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "UNAUTHORIZED: 인증이 없거나 활성 사용자가 아님",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "INTERNAL_ERROR: 조회 또는 만료 반영 실패",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public CursorPageResponse<WatchRecordItemResponse> getWatchRecords(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(hidden = true) WebRequest request,
            @Parameter(
                            description =
                                    "감상 상태. status=A&status=B처럼 이름을 반복해 전달하며 중복은 제거됩니다. 인덱스·중첩 표기는 400입니다.",
                            array =
                                    @ArraySchema(
                                            schema =
                                                    @Schema(
                                                            type = "string",
                                                            allowableValues = {
                                                                "LINK_CLICKED",
                                                                "WATCHED_UNRATED",
                                                                "RATED",
                                                                "EXPIRED"
                                                            })))
                    @RequestParam(required = false, name = "status")
                    List<String> statuses,
            @Parameter(
                            description =
                                    "true이면 확인 시각(24시간)에 도달했고 만료 시각(7일) 전인 LINK_CLICKED 기록만 조회",
                            schema = @Schema(type = "boolean", defaultValue = "false"))
                    @RequestParam(required = false)
                    String confirmDue,
            @Parameter(description = "서버가 직전 응답에서 발급한 불투명 커서") @RequestParam(required = false)
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
        QueryParameterNames.rejectIndexedOrNested(request);
        return watchService.getWatchRecords(
                userId, WatchListCriteria.from(statuses, confirmDue, cursor, size));
    }

    @Operation(
            summary = "감상 확인 응답",
            description =
                    "본인의 만료 전 LINK_CLICKED 기록을 확인합니다. 24시간 이전에도 응답할 수 있으며, 봤어요는 "
                            + "WATCHED_UNRATED, 안 봤어요는 EXPIRED로 전이합니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "감상 확인 반영 성공",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = WatchConfirmResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "VALIDATION_FAILED: 경로 ID 또는 JSON boolean 본문 오류, 인덱스·중첩 파라미터 표기",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "UNAUTHORIZED: 인증이 없거나 활성 사용자가 아님",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "RESOURCE_NOT_FOUND: 기록이 없거나 다른 사용자의 기록",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "409",
                description = "INVALID_WATCH_STATUS: 이미 확인했거나 만료된 기록 또는 경쟁 전이",
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
                description = "INTERNAL_ERROR: 상태 전이 저장 또는 commit 실패",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{watchRecordId}/confirm")
    public WatchConfirmResponse confirm(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(hidden = true) WebRequest request,
            @Parameter(
                            description = "본인 감상 기록 ID. 양의 64비트 정수",
                            schema = @Schema(type = "integer", format = "int64", minimum = "1"))
                    @PathVariable
                    @Positive
                    long watchRecordId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "감상 여부. watched는 JSON boolean이어야 합니다.",
                            required = true)
                    @Valid
                    @RequestBody
                    WatchConfirmRequest body) {
        QueryParameterNames.rejectIndexedOrNested(request);
        return watchService.confirm(userId, watchRecordId, body.watched());
    }
}
