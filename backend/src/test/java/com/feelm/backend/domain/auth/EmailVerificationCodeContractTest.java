package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;

/**
 * 가입 인증번호 발송·확인 계약 테스트(API-078·076).
 *
 * <p>번호 원문은 Redis에 해시로만 남으므로 발송 지점을 대체하는 스파이 빈에서 얻는다({@code RecordingAuthMailSender}). 요청 제한 두 축은
 * {@link EmailVerificationCodeRateLimitContractTest}가 본다.
 */
@DisplayName("이메일 인증번호 — API-078·076")
class EmailVerificationCodeContractTest extends EmailAuthApiTestSupport {

    @Test
    @DisplayName("가입되지 않은 이메일에 6자리 숫자 인증번호를 보낸다")
    void sendsSixDigitCode() throws Exception {
        String email = newAccount().email();

        sendVerificationCode(email).andExpect(status().isAccepted());

        List<String> codes = verificationCodes();
        assertThat(codes).hasSize(1);
        assertThat(codes.getLast()).matches("^[0-9]{6}$");
    }

    @Test
    @DisplayName("발송된 번호를 확인하면 200과 이메일 인증 토큰을 돌려준다")
    void verifiesSentCode() throws Exception {
        String email = newAccount().email();
        String code = requestVerificationCode(email);

        verifyVerificationCode(email, code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerificationToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(600));
    }

    @Test
    @DisplayName("이미 가입된 이메일은 409 EMAIL_DUPLICATED이고 번호를 보내지 않는다")
    void rejectsDuplicatedEmail() throws Exception {
        TestAccount account = newAccount();
        signupPending(account);
        // 가입 자체가 인증번호를 한 번 보냈으므로 기록과 간격 슬롯을 비우고 시작한다.
        clearVerificationCodeInterval(account.email());
        verificationCodeMails().clear();

        sendVerificationCode(account.email())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_DUPLICATED"));

        assertThat(verificationCodes()).isEmpty();
    }

    /** 저장소를 읽어도 번호를 알 수 없어야 한다. 원문이 있는 곳은 발송한 메일 본문뿐이다(API-078 처리 규칙). */
    @Test
    @DisplayName("번호 원문은 저장소와 로그 어디에도 남지 않는다")
    void doesNotStoreRawCode(CapturedOutput output) throws Exception {
        String email = newAccount().email();
        String code = requestVerificationCode(email);

        Map<Object, Object> stored = redisTemplate.opsForHash().entries(verificationCodeKey(email));
        assertThat(stored.values()).doesNotContain(code);
        assertThat(stored.get("hash")).isEqualTo(Sha256Hasher.hex(code));
        assertThat(output.getAll()).doesNotContain(code);
    }

    @Test
    @DisplayName("번호가 다르면 400 VERIFICATION_CODE_MISMATCH다")
    void rejectsMismatchedCode() throws Exception {
        String email = newAccount().email();
        String code = requestVerificationCode(email);

        verifyVerificationCode(email, otherCode(code))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFICATION_CODE_MISMATCH"));
    }

    @Test
    @DisplayName("발급된 번호가 없으면 410 VERIFICATION_CODE_EXPIRED다")
    void rejectsExpiredCode() throws Exception {
        verifyVerificationCode(newAccount().email(), "123456")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("VERIFICATION_CODE_EXPIRED"));
    }

    /**
     * 6자리는 10^6짜리 무차별 대입 표면이라 시도 상한이 이 경로의 핵심 방어다. 상한을 넘긴 요청은 대조하지 않고 그 시점에 번호를 폐기하며, 그 뒤 같은 번호는
     * 만료가 아니라 소진으로 구분해 안내한다(API-079 처리 규칙).
     */
    @Test
    @DisplayName("시도 5회를 넘기면 429이고 그 뒤 같은 번호는 400 VERIFICATION_CODE_EXHAUSTED다")
    void discardsCodeAfterAttemptLimit() throws Exception {
        String email = newAccount().email();
        String code = requestVerificationCode(email);
        String wrong = otherCode(code);

        for (int attempt = 0; attempt < EmailVerificationCodeStore.ATTEMPT_LIMIT; attempt++) {
            verifyVerificationCode(email, wrong)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VERIFICATION_CODE_MISMATCH"));
        }

        verifyVerificationCode(email, wrong)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        verifyVerificationCode(email, code)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFICATION_CODE_EXHAUSTED"));
    }

    @Test
    @DisplayName("다시 발송하면 이전 번호로는 인증할 수 없다")
    void invalidatesPreviousCodeOnResend() throws Exception {
        String email = newAccount().email();
        String first = requestVerificationCode(email);
        clearVerificationCodeInterval(email);
        String second = requestVerificationCode(email);
        assertThat(second).as("두 번째 발송의 번호").isNotEqualTo(first);

        verifyVerificationCode(email, first)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFICATION_CODE_MISMATCH"));

        verifyVerificationCode(email, second).andExpect(status().isOk());
    }

    @Test
    @DisplayName("확인에 성공한 번호로는 토큰을 두 번 받을 수 없다")
    void rejectsSecondVerifyWithSameCode() throws Exception {
        String email = newAccount().email();
        String code = requestVerificationCode(email);
        verifyVerificationCode(email, code).andExpect(status().isOk());

        verifyVerificationCode(email, code)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("VERIFICATION_CODE_EXPIRED"));
    }

    @Test
    @DisplayName("6자리 숫자가 아닌 번호는 대조하지 않고 400 VALIDATION_FAILED다")
    void rejectsMalformedCode() throws Exception {
        String email = newAccount().email();
        requestVerificationCode(email);

        verifyVerificationCode(email, "12345")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyVerificationCode(email, "12345a")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** 탈퇴 익명화가 예약한 도메인을 선점하면 그 사용자의 탈퇴가 영구히 실패한다(D-9). 가입과 같은 이유로 이 경로에서도 막는다. */
    @Test
    @DisplayName("탈퇴 익명화가 예약한 도메인은 400 VALIDATION_FAILED이고 번호를 보내지 않는다")
    void rejectsReservedEmailDomain() throws Exception {
        sendVerificationCode("withdrawn-1@feelm.invalid")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(verificationCodes()).isEmpty();
    }

    @Test
    @DisplayName("이메일 형식이 아니면 400 VALIDATION_FAILED이고 번호를 보내지 않는다")
    void rejectsMalformedEmail() throws Exception {
        postJson(VERIFICATION_CODE_PATH, "{\"email\":\"not-an-email\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        postJson(VERIFICATION_CODE_PATH, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(verificationCodes()).isEmpty();
    }

    /** 저장·대조·제한이 모두 같은 값을 써야 한다. 대문자로 발송하고 소문자로 확인해도 같은 번호다(API-078 Request 규칙). */
    @Test
    @DisplayName("이메일은 소문자로 정규화되어 대소문자가 달라도 같은 번호로 확인된다")
    void normalizesEmailCase() throws Exception {
        String email = newAccount().email();
        String code = requestVerificationCode(email.toUpperCase());

        verifyVerificationCode(email, code).andExpect(status().isOk());
    }

    /** 발송된 번호와 다른 6자리 숫자. 마지막 자리만 바꿔 형식 검증에 걸리지 않게 한다. */
    private String otherCode(String code) {
        char last = code.charAt(5);
        char replacement = last == '0' ? '1' : '0';
        return code.substring(0, 5) + replacement;
    }
}
