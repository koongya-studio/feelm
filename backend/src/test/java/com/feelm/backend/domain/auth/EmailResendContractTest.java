package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;

/**
 * API-003 인증 메일 재발송의 계약을 검증한다.
 *
 * <p>계정 존재 여부를 노출하지 않도록 응답은 항상 202이므로, 실제로 발송했는지는 발송 로그의 인증 링크 개수로 구분한다. 재발송 간격 제한은 결정 4의 1분이며
 * Redis 카운터를 쓰기 때문에 테스트마다 다른 이메일을 사용한다.
 */
@DisplayName("인증 메일 재발송 — API-003")
class EmailResendContractTest extends EmailAuthApiTestSupport {

    @Test
    @DisplayName("미인증 계정에 재발송하면 202와 함께 인증 메일을 한 번 더 보낸다")
    void resendToPendingAccountSendsMailAgain(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupPending(account);

        resend(account.email()).andExpect(status().isAccepted()).andExpect(content().string(""));

        assertThat(verificationTokens(output)).as("가입 1건 + 재발송 1건").hasSize(2);
    }

    @Test
    @DisplayName("재발송하면 이전 토큰은 무효화되고 새 토큰만 인증에 쓸 수 있다")
    void resendInvalidatesPreviousToken(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        long userId = signupPending(account);
        resend(account.email()).andExpect(status().isAccepted());

        List<String> tokens = verificationTokens(output);
        assertThat(tokens).hasSize(2);

        verifyEmail(tokens.getFirst())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFICATION_TOKEN_INVALID"));
        verifyEmail(tokens.getLast())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("가입하지 않은 이메일도 202로 응답하고 메일은 보내지 않는다")
    void unknownEmailReturnsAcceptedWithoutMail(CapturedOutput output) throws Exception {
        resend(newAccount().email()).andExpect(status().isAccepted());

        assertThat(verificationTokens(output)).isEmpty();
    }

    @Test
    @DisplayName("이미 인증된 계정도 202로 응답하고 메일은 보내지 않는다")
    void activeAccountReturnsAcceptedWithoutMail(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupActive(account, output);
        int sentBeforeResend = verificationTokens(output).size();

        resend(account.email()).andExpect(status().isAccepted());

        assertThat(verificationTokens(output)).hasSize(sentBeforeResend);
    }

    @Test
    @DisplayName("미인증 상태로 24시간이 지난 계정은 재가입 대상이므로 202로 응답하되 메일은 보내지 않는다")
    void stalePendingAccountReturnsAcceptedWithoutMail(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        ageUser(signupPending(account), Duration.ofHours(25));

        resend(account.email()).andExpect(status().isAccepted());

        assertThat(verificationTokens(output)).as("가입 시 발송한 1건만 남는다").hasSize(1);
    }

    @Test
    @DisplayName("1분 안에 다시 재발송을 요청하면 429 RATE_LIMITED다")
    void resendWithinRateLimitWindowReturnsRateLimited(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupPending(account);
        resend(account.email()).andExpect(status().isAccepted());

        resend(account.email())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").isNotEmpty());

        assertThat(verificationTokens(output)).as("제한된 요청은 발송하지 않는다").hasSize(2);
    }

    @ParameterizedTest(name = "본문 {0}")
    @ValueSource(strings = {"{}", "{\"email\":\"\"}", "{\"email\":\"not-an-email\"}"})
    @DisplayName("이메일이 없거나 형식이 어긋나면 400 VALIDATION_FAILED다")
    void malformedEmailReturnsValidationFailed(String body) throws Exception {
        postJson(RESEND_PATH, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
