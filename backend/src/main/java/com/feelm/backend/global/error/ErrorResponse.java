package com.feelm.backend.global.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

public record ErrorResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message,
        @JsonInclude(JsonInclude.Include.NON_NULL) Object details) {

    /**
     * 오류 응답 본문을 만드는 단일 지점이다. Security 필터처럼 {@code @RestControllerAdvice}가 처리하지 못하는 위치에서도 같은 형식을 쓰도록
     * 공개한다.
     */
    public static ErrorResponse from(ErrorCode errorCode, Object details) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), details);
    }

    public static ErrorResponse from(ErrorCode errorCode) {
        return from(errorCode, null);
    }
}
