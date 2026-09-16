package com.feelm.backend.global.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "인증 토큰이 만료되었습니다."),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증 정보입니다. 다시 로그인해 주세요."),
    REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다. 다시 로그인해 주세요."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "이메일 인증이 필요합니다. 인증 메일을 확인해 주세요."),
    EMAIL_DUPLICATED(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    NICKNAME_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
    ALREADY_VERIFIED(HttpStatus.CONFLICT, "이미 이메일 인증이 완료된 계정입니다."),
    VERIFICATION_TOKEN_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 인증 링크입니다. 인증 메일을 다시 요청해 주세요."),
    VERIFICATION_TOKEN_EXPIRED(HttpStatus.GONE, "인증 링크가 만료되었습니다. 인증 메일을 다시 요청해 주세요."),
    UNSUPPORTED_PROVIDER(HttpStatus.BAD_REQUEST, "지원하지 않는 소셜 로그인 제공자입니다."),
    OAUTH_CODE_INVALID(HttpStatus.BAD_REQUEST, "소셜 로그인 인증에 실패했습니다. 다시 시도해 주세요."),
    OAUTH_EMAIL_REQUIRED(HttpStatus.BAD_REQUEST, "소셜 계정의 이메일 제공에 동의해야 가입할 수 있습니다."),
    OAUTH_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "소셜 로그인 제공자와 통신하지 못했습니다. 잠시 후 다시 시도해 주세요."),
    SIGNUP_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "가입 정보가 만료되었습니다. 소셜 로그인을 다시 시도해 주세요."),
    OAUTH_ACCOUNT_ALREADY_LINKED(HttpStatus.CONFLICT, "이미 다른 계정에 연결된 소셜 계정입니다."),
    PROVIDER_ALREADY_LINKED(HttpStatus.CONFLICT, "이미 연결된 소셜 로그인 제공자입니다."),
    RESET_TOKEN_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 비밀번호 재설정 링크입니다. 재설정을 다시 요청해 주세요."),
    RESET_TOKEN_EXPIRED(HttpStatus.GONE, "비밀번호 재설정 링크가 만료되었습니다. 재설정을 다시 요청해 주세요."),
    PASSWORD_NOT_SET(HttpStatus.CONFLICT, "비밀번호가 설정되지 않은 계정입니다. 소셜 로그인을 사용해 주세요."),
    ONBOARDING_ALREADY_COMPLETED(HttpStatus.CONFLICT, "이미 초기 취향 설정을 마쳤습니다."),
    CANNOT_BLOCK_SELF(HttpStatus.BAD_REQUEST, "자기 자신은 차단할 수 없습니다."),
    ALREADY_BLOCKED(HttpStatus.CONFLICT, "이미 차단한 사용자입니다."),
    ALREADY_DISMISSED(HttpStatus.CONFLICT, "이미 관심없음 처리한 영화입니다."),
    PARTY_CLOSED(HttpStatus.CONFLICT, "이미 종료된 파티입니다."),
    PARTY_FULL(HttpStatus.CONFLICT, "파티 정원이 가득 찼습니다."),
    ACTIVE_PARTY_LIMIT_REACHED(HttpStatus.CONFLICT, "참여 가능한 활성 파티 수를 초과했습니다."),
    ALREADY_MEMBER(HttpStatus.CONFLICT, "이미 파티에 참여 중인 사용자입니다."),
    INVITATION_PENDING(HttpStatus.CONFLICT, "이미 대기 중인 초대가 있습니다."),
    INVITATION_NOT_PENDING(HttpStatus.CONFLICT, "이미 처리된 초대입니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않은 HTTP 메서드입니다."),
    CONFLICT(HttpStatus.CONFLICT, "요청이 현재 리소스 상태와 충돌합니다."),
    INVALID_WATCH_STATUS(HttpStatus.CONFLICT, "현재 감상 기록 상태에서는 요청을 처리할 수 없습니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청 횟수 제한을 초과했습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    RECOMMENDATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "추천 서비스를 일시적으로 사용할 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
