package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/**
 * 가입 인증번호 발송(API-078)의 요청 제한을 검증한다.
 *
 * <p>두 축의 <b>적용 시점</b>이 이 경로의 계약이다. IP 축은 중복 검사보다 먼저 시도 시점에 세고({@code 409}도 예산을 소모한다), 이메일 간격 슬롯은
 * 처리를 마친 뒤에만 심는다. 이 경로는 계정 존재를 숨기지 않아 이메일 열거 수단이 되며, 그 속도를 묶는 것이 IP 축이다.
 */
@DisplayName("인증번호 발송 요청 제한 — API-078")
class EmailVerificationCodeRateLimitContractTest extends EmailAuthApiTestSupport {

    @Value("${feelm.mail.rate-limit.ip[0].limit}")
    private long ipLimit;

    @Value("${feelm.mail.rate-limit.ip[0].window}")
    private Duration ipWindow;

    @Value("${feelm.mail.send-budget.windows[0].limit}")
    private long sendBudgetLimit;

    @Value("${feelm.mail.send-budget.windows[0].window}")
    private Duration sendBudgetWindow;

    /** 이 클래스가 채운 카운터를 다음 테스트가 물려받지 않게 한다. */
    @AfterEach
    void clearLimitCounters() {
        clearMailSendRateLimits();
    }

    /** 세 경로가 같은 SMTP 쿼터를 쓰므로 한 경로만 막으면 총량을 묶을 수 없다(결정 D-2). */
    @Test
    @DisplayName("IP 카운터를 재발송·재설정과 공유한다")
    void sharesMailIpBudget() throws Exception {
        String clientIp = nextClientIp();

        for (long sent = 0; sent < ipLimit; sent++) {
            requestPasswordResetFrom(clientIp, newAccount().email())
                    .andExpect(status().isAccepted());
        }

        sendVerificationCodeFrom(clientIp, newAccount().email())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    /**
     * 중복 검사를 먼저 하면 {@code 409}를 받는 요청이 예산을 쓰지 않아, 이 경로를 통한 이메일 열거를 예산으로 묶을 수 없다. {@code 409}로 끝난
     * 요청도 IP 카운터를 올리는지 본다.
     */
    @Test
    @DisplayName("이미 가입된 이메일로 받은 409도 IP 예산을 소모한다")
    void appliesIpAxisBeforeDuplicateCheck() throws Exception {
        String clientIp = nextClientIp();
        TestAccount registered = newAccount();
        signupActive(registered);
        clearVerificationCodeInterval(registered.email());
        clearMailSendRateLimits();

        for (long sent = 0; sent < ipLimit; sent++) {
            sendVerificationCodeFrom(clientIp, registered.email()).andExpect(status().isConflict());
        }

        sendVerificationCodeFrom(clientIp, newAccount().email())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    /**
     * 간격 슬롯은 요청자가 아니라 그 이메일 주인의 것이다. 거절될 요청이 슬롯을 심으면 예산을 넘긴 쪽이 임의의 이메일에 대해 발송을 분당 한 건씩 지연시킬 수 있다.
     * {@code 409}로 끝난 요청이 슬롯을 남기지 않는지 본다.
     */
    @Test
    @DisplayName("409로 끝난 요청은 그 이메일의 간격 슬롯을 심지 않는다")
    void doesNotPlantIntervalOnDuplicate() throws Exception {
        TestAccount registered = newAccount();
        signupActive(registered);
        clearVerificationCodeInterval(registered.email());

        sendVerificationCode(registered.email()).andExpect(status().isConflict());

        assertThat(
                        redisTemplate.hasKey(
                                "auth:email-code-interval:" + Sha256Hasher.hex(registered.email())))
                .as("409 뒤의 간격 슬롯")
                .isFalse();
    }

    @Test
    @DisplayName("같은 이메일로 간격 안에 다시 요청하면 429 RATE_LIMITED다")
    void limitsSameEmailInterval() throws Exception {
        String email = newAccount().email();

        sendVerificationCode(email).andExpect(status().isAccepted());

        sendVerificationCode(email)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    /** 간격 키는 경로별로 분리한다. 가입 시도가 같은 이메일의 계정 복구 경로까지 잠그면 안 된다. */
    @Test
    @DisplayName("인증번호 발송은 재설정 요청의 간격 슬롯과 분리된다")
    void separatesEmailIntervalFromPasswordReset() throws Exception {
        String email = newAccount().email();

        sendVerificationCode(email).andExpect(status().isAccepted());

        requestPasswordReset(email).andExpect(status().isAccepted());
    }

    /**
     * 발급은 이전 번호를 무효화하는데 발송 여부는 발송 스레드가 정한다. 예산이 마른 동안 발급까지 하면 사용자가 가진 살아 있는 번호만 사라진다. 응답은 그대로
     * {@code 202}이고 번호는 발급되지 않는지 본다.
     */
    @Test
    @DisplayName("전역 발송 상한이 소진되면 새 번호를 발급하지 않고 응답은 202다")
    void skipsIssueWhenSendBudgetExhausted() throws Exception {
        String email = newAccount().email();
        redisTemplate
                .opsForValue()
                .set(
                        mailSentKey(sendBudgetWindow),
                        Long.toString(sendBudgetLimit),
                        sendBudgetWindow);

        sendVerificationCode(email).andExpect(status().isAccepted());

        assertThat(verificationCodes()).isEmpty();
        assertThat(redisTemplate.hasKey(verificationCodeKey(email))).as("인증번호 키").isFalse();
    }

    @Test
    @DisplayName("제한 카운터에는 창 길이 이내의 TTL이 걸린다")
    void limitCountersExpireWithinWindow() throws Exception {
        String clientIp = nextClientIp();

        sendVerificationCodeFrom(clientIp, newAccount().email()).andExpect(status().isAccepted());

        assertThat(redisTemplate.getExpire(mailIpLimitKey(clientIp, ipWindow)))
                .as("IP 카운터 TTL(초)")
                .isBetween(1L, ipWindow.toSeconds());
    }
}
