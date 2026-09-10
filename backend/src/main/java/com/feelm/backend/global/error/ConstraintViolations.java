package com.feelm.backend.global.error;

import java.util.Locale;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 유일 제약 위반을 계약상의 오류 코드로 옮긴다.
 *
 * <p>계정 도메인(가입·프로필 수정)과 소셜 연결이 함께 쓰므로 특정 도메인이 아니라 {@code global/error}에 둔다 (S15P21E106-152 결정 D-5).
 *
 * <p>중복 검사와 삽입 사이에 다른 요청이 끼어들면 사전 검사를 통과한 삽입도 실패한다. 그때 500이 아니라 사전 검사와 같은 응답을 주기 위해 제약 이름으로 원인을
 * 판별한다. 메시지를 문자열로 뒤지지 않고 Hibernate가 예외에 담아 주는 제약 이름을 쓴다.
 */
public final class ConstraintViolations {

    private ConstraintViolations() {}

    /**
     * 계정 유일 제약(이메일·닉네임) 위반을 옮긴다.
     *
     * @return 판별한 {@link BusinessException}, 알 수 없는 제약이면 원래 예외
     */
    public static RuntimeException accountDuplicationOf(DataIntegrityViolationException exception) {
        String constraint = nameOf(exception);
        if (constraint.contains("email")) {
            return new BusinessException(ErrorCode.EMAIL_DUPLICATED);
        }
        if (constraint.contains("nickname")) {
            return new BusinessException(ErrorCode.NICKNAME_DUPLICATED);
        }
        return exception;
    }

    /**
     * 소셜 연결 유일 제약 위반을 옮긴다(API-008·009).
     *
     * <p>{@code uk_user_auth_providers_provider_provider_user_id}는 소셜 계정이 이미 다른 사용자에 연결된 경우이고,
     * {@code uk_user_auth_providers_user_id_provider}는 같은 제공자를 두 번 연결한 경우다. 이름의 {@code
     * provider_user_id} 포함 여부로 둘을 구분한다.
     *
     * <p>{@code OAuthLinkService#rejectAlreadyLinked}의 사전 검사와 같은 우선순위로, {@code
     * user_id_provider}(PROVIDER_ALREADY_LINKED)를 먼저 본다.
     */
    public static RuntimeException linkConflictOf(DataIntegrityViolationException exception) {
        String constraint = nameOf(exception);
        if (constraint.contains("user_id_provider")) {
            return new BusinessException(ErrorCode.PROVIDER_ALREADY_LINKED);
        }
        if (constraint.contains("provider_user_id")) {
            return new BusinessException(ErrorCode.OAUTH_ACCOUNT_ALREADY_LINKED);
        }
        return exception;
    }

    /** 위반된 제약 이름. 이름을 알 수 없으면 빈 문자열이며 호출자가 원래 예외를 그대로 던진다. */
    private static String nameOf(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation
                    && violation.getConstraintName() != null) {
                return violation.getConstraintName().toLowerCase(Locale.ROOT);
            }
            cause = cause.getCause();
        }
        return "";
    }
}
