package com.feelm.backend.domain.notification.push;

public record WebPushSendResult(Kind kind, Integer httpStatus, String failureCode) {

    public static WebPushSendResult sent(int status) {
        return new WebPushSendResult(Kind.SENT, status, null);
    }

    public static WebPushSendResult expired(int status) {
        return new WebPushSendResult(Kind.EXPIRED, status, "SUBSCRIPTION_EXPIRED");
    }

    public static WebPushSendResult retryable(Integer status, String code) {
        return new WebPushSendResult(Kind.RETRYABLE_FAILURE, status, code);
    }

    public static WebPushSendResult permanent(Integer status, String code) {
        return new WebPushSendResult(Kind.PERMANENT_FAILURE, status, code);
    }

    public enum Kind {
        SENT,
        EXPIRED,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }
}
