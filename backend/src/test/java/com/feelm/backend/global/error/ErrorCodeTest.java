package com.feelm.backend.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

@DisplayName("ErrorCode — A-9 의미 코드 규칙과 API-000 공통 오류 코드 표")
class ErrorCodeTest {

    private static final Pattern UPPER_SNAKE = Pattern.compile("^[A-Z]+(_[A-Z]+)*$");

    @Test
    @DisplayName("모든 오류 코드 이름은 UPPER_SNAKE_CASE다 (A-9)")
    void everyCodeNameIsUpperSnakeCase() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.name()).as("오류 코드 %s", code.name()).matches(UPPER_SNAKE.pattern());
        }
    }

    @Test
    @DisplayName("API-000 공통 오류 코드 표의 코드·HTTP 상태 매핑과 일치한다 (결정 5 확장분 포함)")
    void matchesCommonErrorCodeTable() {
        assertThat(ErrorCode.VALIDATION_FAILED.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.UNAUTHORIZED.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ErrorCode.TOKEN_EXPIRED.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ErrorCode.FORBIDDEN.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ErrorCode.RESOURCE_NOT_FOUND.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.METHOD_NOT_ALLOWED.getStatus())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(ErrorCode.CONFLICT.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.UNSUPPORTED_MEDIA_TYPE.getStatus())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(ErrorCode.RATE_LIMITED.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ErrorCode.INTERNAL_ERROR.getStatus())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(ErrorCode.RECOMMENDATION_UNAVAILABLE.getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("등록된 오류 코드는 공통 11종과 도메인 코드뿐이다 — 도메인 코드는 각 도메인 업무에서 추가한다")
    void containsOnlyRegisteredCodes() {
        assertThat(ErrorCode.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder(
                        // API-000 공통 오류 코드 표 11종
                        "VALIDATION_FAILED",
                        "UNAUTHORIZED",
                        "TOKEN_EXPIRED",
                        "FORBIDDEN",
                        "RESOURCE_NOT_FOUND",
                        "METHOD_NOT_ALLOWED",
                        "CONFLICT",
                        "UNSUPPORTED_MEDIA_TYPE",
                        "RATE_LIMITED",
                        "INTERNAL_ERROR",
                        "RECOMMENDATION_UNAVAILABLE",
                        // 인증 도메인 — API-006 토큰 재발급 전용
                        "REFRESH_TOKEN_INVALID",
                        "REFRESH_TOKEN_EXPIRED",
                        // 계정 도메인 — API-001~004 (S15P21E106-149)
                        "EMAIL_DUPLICATED",
                        "NICKNAME_DUPLICATED",
                        "VERIFICATION_TOKEN_INVALID",
                        "VERIFICATION_TOKEN_EXPIRED",
                        "ALREADY_VERIFIED",
                        "INVALID_CREDENTIALS",
                        "EMAIL_NOT_VERIFIED",
                        // 계정 도메인 — API-010~012 (S15P21E106-151)
                        "RESET_TOKEN_INVALID",
                        "RESET_TOKEN_EXPIRED",
                        "PASSWORD_NOT_SET");
    }

    @Test
    @DisplayName("API-006 재발급 전용 코드는 401로 매핑된다")
    void refreshTokenCodesAreUnauthorized() {
        assertThat(ErrorCode.REFRESH_TOKEN_INVALID.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ErrorCode.REFRESH_TOKEN_EXPIRED.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("API-001~004 계정 오류 코드는 각 명세의 HTTP 상태로 매핑된다")
    void emailAccountCodesMatchSpecifiedStatuses() {
        assertThat(ErrorCode.EMAIL_DUPLICATED.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.NICKNAME_DUPLICATED.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.ALREADY_VERIFIED.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.VERIFICATION_TOKEN_INVALID.getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.VERIFICATION_TOKEN_EXPIRED.getStatus()).isEqualTo(HttpStatus.GONE);
        assertThat(ErrorCode.INVALID_CREDENTIALS.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ErrorCode.EMAIL_NOT_VERIFIED.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("API-010~012 비밀번호 오류 코드는 각 명세의 HTTP 상태로 매핑된다")
    void passwordCodesMatchSpecifiedStatuses() {
        assertThat(ErrorCode.RESET_TOKEN_INVALID.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.RESET_TOKEN_EXPIRED.getStatus()).isEqualTo(HttpStatus.GONE);
        assertThat(ErrorCode.PASSWORD_NOT_SET.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("모든 오류 코드는 사용자에게 표시 가능한 기본 메시지를 가진다")
    void everyCodeHasDefaultMessage() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.getMessage()).as("오류 코드 %s의 메시지", code.name()).isNotBlank();
        }
    }
}
