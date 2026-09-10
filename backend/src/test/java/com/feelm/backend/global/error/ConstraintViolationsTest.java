package com.feelm.backend.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 유일 제약 위반이 계약상의 오류 코드로 옮겨지는지 확인한다.
 *
 * <p>닉네임 유일성은 {@code lower(replace(nickname, ' ', ''))} 함수 기반 인덱스라 JPA가 만드는 H2 테스트 스키마에는 없다. 그래서
 * 프로필 수정(API-015)에서 사전 검사와 갱신 사이의 경합을 계약 테스트로 재현할 수 없고, 그때 500이 아닌 409가 나가는지를 이 단위 테스트가 대신 고정한다(플랜
 * 3장 대체 검증).
 */
@DisplayName("유일 제약 위반 → 오류 코드 변환")
class ConstraintViolationsTest {

    @Test
    @DisplayName("닉네임 정규화 유일 인덱스 위반은 NICKNAME_DUPLICATED다")
    void normalizedNicknameIndexBecomesNicknameDuplicated() {
        RuntimeException translated =
                ConstraintViolations.accountDuplicationOf(
                        violationOf("uix_users_nickname_normalized"));

        assertThat(translated)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.NICKNAME_DUPLICATED);
    }

    @Test
    @DisplayName("이메일 유일 제약 위반은 EMAIL_DUPLICATED다")
    void emailConstraintBecomesEmailDuplicated() {
        RuntimeException translated =
                ConstraintViolations.accountDuplicationOf(violationOf("uk_users_email"));

        assertThat(translated)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_DUPLICATED);
    }

    @Test
    @DisplayName("알 수 없는 제약은 원래 예외를 그대로 돌려준다")
    void unknownConstraintKeepsOriginalException() {
        DataIntegrityViolationException original = violationOf("fk_something_else");

        assertThat(ConstraintViolations.accountDuplicationOf(original)).isSameAs(original);
    }

    private DataIntegrityViolationException violationOf(String constraintName) {
        return new DataIntegrityViolationException(
                "constraint violation",
                new ConstraintViolationException("constraint violation", null, constraintName));
    }
}
