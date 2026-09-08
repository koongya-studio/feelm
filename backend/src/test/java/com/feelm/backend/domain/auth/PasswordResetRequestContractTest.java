package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;

/**
 * API-010 비밀번호 재설정 요청의 계약을 검증한다.
 *
 * <p>계정 존재 여부와 상태를 노출하지 않도록 응답은 항상 202이므로, 실제로 발송했는지는 발송 스파이가 기록한 토큰 개수로 구분한다. 간격 제한(1분)이 이메일 단위라
 * 테스트마다 새 계정을 쓰고, 같은 이메일로 두 번 요청해야 하는 경우에만 제한 키를 지운다.
 */
@DisplayName("비밀번호 재설정 요청 — API-010")
class PasswordResetRequestContractTest extends EmailAuthApiTestSupport {

    @Test
    @DisplayName("활성 계정이 요청하면 202와 함께 재설정 메일을 한 번 보낸다")
    void activeAccountRequestSendsResetMail(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        long userId = signupActive(account, output);

        requestPasswordReset(account.email())
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        assertThat(passwordResetTokens()).hasSize(1);
        assertThat(unusedPasswordResetTokenCount(userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("가입하지 않은 이메일도 202로 응답하고 메일은 보내지 않는다")
    void unknownEmailReturnsAcceptedWithoutMail() throws Exception {
        requestPasswordReset(newAccount().email()).andExpect(status().isAccepted());

        assertThat(passwordResetTokens()).isEmpty();
    }

    @Test
    @DisplayName("미인증 계정은 202로 응답하되 메일을 보내지 않는다")
    void pendingVerificationAccountReturnsAcceptedWithoutMail() throws Exception {
        TestAccount account = newAccount();
        signupPending(account);

        requestPasswordReset(account.email()).andExpect(status().isAccepted());

        assertThat(passwordResetTokens()).isEmpty();
    }

    @Test
    @DisplayName("탈퇴 계정은 202로 응답하되 메일을 보내지 않는다")
    void withdrawnAccountReturnsAcceptedWithoutMail(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        withdrawUser(signupActive(account, output));

        requestPasswordReset(account.email()).andExpect(status().isAccepted());

        assertThat(passwordResetTokens()).isEmpty();
    }

    @Test
    @DisplayName("비밀번호가 없는 계정은 202로 응답하되 메일을 보내지 않는다")
    void passwordlessAccountReturnsAcceptedWithoutMail(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        clearPassword(signupActive(account, output));

        requestPasswordReset(account.email()).andExpect(status().isAccepted());

        assertThat(passwordResetTokens()).isEmpty();
    }

    @Test
    @DisplayName("1분 안에 다시 요청하면 429 RATE_LIMITED다")
    void secondRequestWithinIntervalReturnsRateLimited(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupActive(account, output);
        requestPasswordReset(account.email()).andExpect(status().isAccepted());

        requestPasswordReset(account.email())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").isNotEmpty());

        assertThat(passwordResetTokens()).as("제한된 요청은 발송하지 않는다").hasSize(1);
    }

    @Test
    @DisplayName("가입하지 않은 이메일도 같은 간격 제한을 받아 계정 존재 여부가 드러나지 않는다")
    void unknownEmailIsRateLimitedTheSameWay() throws Exception {
        String email = newAccount().email();
        requestPasswordReset(email).andExpect(status().isAccepted());

        requestPasswordReset(email)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @ParameterizedTest(name = "본문 {0}")
    @ValueSource(strings = {"{}", "{\"email\":\"\"}", "{\"email\":\"not-an-email\"}"})
    @DisplayName("이메일이 없거나 형식이 어긋나면 400 VALIDATION_FAILED다")
    void malformedEmailReturnsValidationFailed(String body) throws Exception {
        postJson(RESET_REQUEST_PATH, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /**
     * 형식은 유효하고 길이만 255자를 넘는 이메일. 길이를 도메인에 싣는다. 로컬 파트를 늘리면 64자 제한 때문에 {@code @Email}이 먼저 거절해
     * {@code @Size}를 거치지 않는다. 라벨은 63자 이하로 끊는다.
     */
    private static String tooLongEmail() {
        String label = "a".repeat(63);
        String domain = String.join(".", label, label, label, "a".repeat(58), "com");
        return "a@" + domain;
    }

    @Test
    @DisplayName("형식이 맞아도 255자를 넘는 이메일은 400 VALIDATION_FAILED다")
    void tooLongEmailReturnsValidationFailed() throws Exception {
        String email = tooLongEmail();
        assertThat(email).hasSize(256);

        requestPasswordReset(email)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.errors[0].field").value("email"))
                .andExpect(jsonPath("$.details.errors[0].reason").value("이메일은 255자 이하여야 합니다."));
    }

    @Test
    @DisplayName("다시 요청하면 이전 토큰은 무효화되고 새 토큰만 쓸 수 있다")
    void newRequestInvalidatesPreviousToken(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupActive(account, output);
        requestPasswordReset(account.email()).andExpect(status().isAccepted());
        clearPasswordResetInterval(account.email());
        requestPasswordReset(account.email()).andExpect(status().isAccepted());

        List<String> tokens = passwordResetTokens();
        assertThat(tokens).hasSize(2);

        resetPassword(tokens.getFirst(), "N3wP@ssw0rd")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_TOKEN_INVALID"));
        resetPassword(tokens.getLast(), "N3wP@ssw0rd").andExpect(status().isNoContent());
    }
}
