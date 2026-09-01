package com.feelm.backend.global.error;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Object details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public BusinessException(ErrorCode errorCode, Object details) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.details = details;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Object getDetails() {
        return details;
    }
}
