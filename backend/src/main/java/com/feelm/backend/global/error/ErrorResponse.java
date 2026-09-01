package com.feelm.backend.global.error;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ErrorResponse(
        String code, String message, @JsonInclude(JsonInclude.Include.NON_NULL) Object details) {

    static ErrorResponse from(ErrorCode errorCode, Object details) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), details);
    }
}
