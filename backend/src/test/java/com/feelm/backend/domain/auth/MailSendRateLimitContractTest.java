package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 인증 메일 재발송(API-003)과 비밀번호 재설정 요청(API-010)의 IP 단위·전역 발송 제한을 검증한다(S15P21E106-281).
 *
 * <p>이메일 단위 간격 제한은 {@link EmailResendContractTest}, {@link PasswordResetRequestContractTest}가 이미
 * 검증한다. 여기서는 서로 다른 이메일을 대량으로 넣는 발송 폭탄을 막는 두 축만 다루므로, 별도로 언급하지 않는 요청은 매번 새 이메일로 보낸다.
 *
 * <p>한도 값은 테스트 설정({@code src/test/resources/application.yml})에서 읽는다. 반복 횟수를 줄이려고 IP 한도는 작게, 컨텍스트를
 * 공유하는 다른 테스트가 함께 소모하는 전역 한도는 크게 두었기 때문이다. 전역 제한 검증은 그 한도까지 요청을 보내는 대신 카운터를 직접 채운다.
 */
@DisplayName("인증·재설정 메일 발송 제한 — API-003·API-010")
class MailSendRateLimitContractTest extends EmailAuthApiTestSupport {

    @Value("${feelm.mail.rate-limit.ip[0].limit}")
    private long ipLimit;

    @Value("${feelm.mail.rate-limit.ip[0].window}")
    private Duration ipWindow;

    @Value("${feelm.mail.rate-limit.global[0].limit}")
    private long globalLimit;

    @Value("${feelm.mail.rate-limit.global[0].window}")
    private Duration globalWindow;

    /** 전역 축의 두 번째 창. 목록의 창을 모두 적용하는지 확인하려고 첫 창과 따로 읽는다(결정 D-8). */
    @Value("${feelm.mail.rate-limit.global[1].window}")
    private Duration secondGlobalWindow;

    /**
     * 리미터가 남기는 로그를 모은다. 로그 문자열을 표준 출력에서 찾으면 콘솔 인코딩에 따라 한글이 깨질 수 있어 로거에 직접 붙인다.
     *
     * <p>WARN 줄 수 자체가 검증 대상이다(전역 제한 도달 시 창마다 한 번).
     */
    private ListAppender<ILoggingEvent> limiterLogs;

    @BeforeEach
    void captureLimiterLogs() {
        limiterLogs = new ListAppender<>();
        limiterLogs.start();
        limiterLogger().addAppender(limiterLogs);
    }

    @AfterEach
    void releaseLimiterLogs() {
        limiterLogger().detachAppender(limiterLogs);
        limiterLogs.stop();
    }

    /** 이 클래스가 채운 카운터를 다음 테스트가 물려받지 않게 한다. 전역 카운터는 컨텍스트를 공유하는 모든 테스트에 영향을 준다. */
    @AfterEach
    void clearLimitCounters() {
        clearMailSendRateLimits();
    }

    @Test
    @DisplayName("같은 IP에서 한도까지는 202이고 한도를 넘으면 429 RATE_LIMITED다")
    void ipLimitRejectsRequestsOverLimit() throws Exception {
        String clientIp = nextClientIp();

        exhaustIpBudget(clientIp);

        resendFrom(clientIp, newAccount().email())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("한 IP가 막혀도 다른 IP는 202다")
    void limitAppliesPerIp() throws Exception {
        String blockedIp = nextClientIp();
        exhaustIpBudget(blockedIp);
        resendFrom(blockedIp, newAccount().email()).andExpect(status().isTooManyRequests());

        resendFrom(nextClientIp(), newAccount().email()).andExpect(status().isAccepted());
    }

    /**
     * IPv6 클라이언트는 보통 {@code /64} 이상을 배정받아, 주소 단위로 세면 주소만 바꿔 예산을 무한히 새로 얻는다. 같은 프리픽스의 주소들이 한 예산을 쓰는지
     * 본다(S15P21E106-288 결정 D-4).
     */
    @Test
    @DisplayName("같은 /64 안의 서로 다른 IPv6 주소는 IP 예산을 함께 쓴다")
    void ipLimitSharesBudgetAcrossSameIpv6Prefix() throws Exception {
        for (long sent = 0; sent < ipLimit; sent++) {
            resendFrom("2001:db8:1::" + (sent + 1), newAccount().email())
                    .andExpect(status().isAccepted());
        }

        resendFrom("2001:db8:1::ffff", newAccount().email())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    /** 묶는 단위가 지나치게 넓으면 무관한 회선이 함께 걸린다. 프리픽스가 다르면 예산도 분리되는지 본다. */
    @Test
    @DisplayName("/64가 다른 IPv6 주소는 서로의 IP 예산을 소모하지 않는다")
    void ipLimitSeparatesDifferentIpv6Prefixes() throws Exception {
        for (long sent = 0; sent < ipLimit; sent++) {
            resendFrom("2001:db8:2::" + (sent + 1), newAccount().email())
                    .andExpect(status().isAccepted());
        }
        resendFrom("2001:db8:2::ffff", newAccount().email())
                .andExpect(status().isTooManyRequests());

        resendFrom("2001:db8:3::1", newAccount().email()).andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("IP 카운터를 API-003과 API-010이 공유한다")
    void ipCounterIsSharedBetweenResendAndPasswordReset() throws Exception {
        String clientIp = nextClientIp();

        exhaustIpBudget(clientIp);

        requestPasswordResetFrom(clientIp, newAccount().email())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("전역 한도에 도달하면 새 IP·새 이메일도 두 엔드포인트 모두 429다")
    void globalLimitRejectsEvenFreshIpAndEmail() throws Exception {
        fillGlobalCounter();

        resendFrom(nextClientIp(), newAccount().email())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        requestPasswordResetFrom(nextClientIp(), newAccount().email())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    /**
     * 거절되는 모든 요청에 WARN을 남기면 공격이 이어지는 동안 같은 줄이 초당 요청 수만큼 쌓여, 정작 로그가 필요한 순간에 다른 기록을 밀어낸다. 한도를 처음 넘는
     * 요청에서만 남기는지 본다.
     */
    @Test
    @DisplayName("전역 제한 WARN은 창마다 한 번만 남는다")
    void globalLimitLogsWarnOncePerWindow() throws Exception {
        fillGlobalCounter();

        for (int attempt = 0; attempt < 3; attempt++) {
            resendFrom(nextClientIp(), newAccount().email())
                    .andExpect(status().isTooManyRequests());
        }

        assertThat(warnMessages()).as("전역 제한 WARN 줄").hasSize(1);
    }

    @Test
    @DisplayName("한도 안의 요청은 WARN을 남기지 않는다")
    void requestWithinLimitLogsNothing() throws Exception {
        resendFrom(nextClientIp(), newAccount().email()).andExpect(status().isAccepted());

        assertThat(warnMessages()).as("전역 제한 WARN 줄").isEmpty();
    }

    @Test
    @DisplayName("제한 초과 응답은 가입 이메일과 미가입 이메일에서 상태·본문이 같다")
    void rateLimitedResponseDoesNotRevealAccountExistence() throws Exception {
        TestAccount registered = newAccount();
        signupPending(registered);
        String unknownEmail = newAccount().email();

        String resendIp = nextClientIp();
        exhaustIpBudget(resendIp);
        MvcResult registeredResend = resendFrom(resendIp, registered.email()).andReturn();
        MvcResult unknownResend = resendFrom(resendIp, unknownEmail).andReturn();

        String resetIp = nextClientIp();
        exhaustIpBudget(resetIp);
        MvcResult registeredReset =
                requestPasswordResetFrom(resetIp, registered.email()).andReturn();
        MvcResult unknownReset = requestPasswordResetFrom(resetIp, unknownEmail).andReturn();

        assertThat(statusOf(registeredResend)).as("제한을 넘은 재발송 요청").isEqualTo(429);
        assertThat(statusOf(unknownResend)).isEqualTo(statusOf(registeredResend));
        assertThat(bodyOf(unknownResend)).isEqualTo(bodyOf(registeredResend));

        assertThat(statusOf(registeredReset)).as("제한을 넘은 재설정 요청").isEqualTo(429);
        assertThat(statusOf(unknownReset)).isEqualTo(statusOf(registeredReset));
        assertThat(bodyOf(unknownReset)).isEqualTo(bodyOf(registeredReset));
    }

    @Test
    @DisplayName("이메일 간격에서 막힌 요청은 IP 예산을 소모하지 않는다")
    void requestRejectedByEmailIntervalKeepsIpBudget() throws Exception {
        String clientIp = nextClientIp();
        String repeatedEmail = newAccount().email();
        resendFrom(clientIp, repeatedEmail).andExpect(status().isAccepted());

        resendFrom(clientIp, repeatedEmail)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        for (long remaining = ipLimit - 1; remaining > 0; remaining--) {
            resendFrom(clientIp, newAccount().email()).andExpect(status().isAccepted());
        }
        resendFrom(clientIp, newAccount().email()).andExpect(status().isTooManyRequests());
    }

    /**
     * 이메일 간격 슬롯은 요청자의 예산이 아니라 그 이메일 주인의 것이다. IP·전역에서 거절될 요청이 슬롯을 심으면, 예산을 이미 넘긴 요청자가 임의의 이메일에 대해
     * 발송을 계속 지연시킬 수 있고 공유 IP를 쓰는 정상 사용자도 자기 요청에 다시 걸린다. 거절된 요청이 슬롯을 남기지 않는지 본다.
     */
    @Test
    @DisplayName("IP 예산에서 막힌 요청은 그 이메일의 간격 슬롯을 소모하지 않는다")
    void requestRejectedByIpBudgetKeepsEmailInterval() throws Exception {
        String blockedIp = nextClientIp();
        exhaustIpBudget(blockedIp);
        String targetEmail = newAccount().email();

        resendFrom(blockedIp, targetEmail).andExpect(status().isTooManyRequests());

        resendFrom(nextClientIp(), targetEmail).andExpect(status().isAccepted());
    }

    /**
     * 두 전역 창의 카운터를 모두 확인한다. 첫 창만 보면 목록의 첫 항목만 평가하는 구현도 이 클래스를 모두 통과한다. 전역 제한 검증은 첫 창의 카운터를 직접 채워
     * 거절시키므로, 두 번째 창이 실제로 올라가는지는 여기서만 드러난다.
     */
    @Test
    @DisplayName("제한 카운터에는 창 길이 이내의 TTL이 걸린다")
    void limitCountersExpireWithinWindow() throws Exception {
        String clientIp = nextClientIp();

        resendFrom(clientIp, newAccount().email()).andExpect(status().isAccepted());

        assertThat(redisTemplate.getExpire(mailIpLimitKey(clientIp, ipWindow)))
                .as("IP 카운터 TTL(초)")
                .isBetween(1L, ipWindow.toSeconds());
        assertThat(redisTemplate.getExpire(mailGlobalLimitKey(globalWindow)))
                .as("첫 전역 창(%s) 카운터 TTL(초)".formatted(globalWindow))
                .isBetween(1L, globalWindow.toSeconds());
        assertThat(redisTemplate.getExpire(mailGlobalLimitKey(secondGlobalWindow)))
                .as("두 번째 전역 창(%s) 카운터 TTL(초)".formatted(secondGlobalWindow))
                .isBetween(1L, secondGlobalWindow.toSeconds());
    }

    /**
     * 고정 창(결정 D-5)이라 창 중간의 요청은 만료를 다시 걸지 않는다. 매번 만료를 거는 구현으로 퇴행하면 슬라이딩 창이 되어, 요청이 계속되는 동안 카운터가 영영
     * 만료되지 않고 그 IP가 무기한 429가 된다.
     *
     * <p>두 번째 TTL이 첫 번째보다 "크지 않다"까지만 본다. 두 요청이 같은 밀리초 안에 끝나면 값이 같을 수 있지만, 정상 구현에서 TTL이 늘어나는 일은 없다.
     * 퇴행한 구현은 TTL이 창 길이로 되돌아가 첫 측정값보다 커진다.
     */
    @Test
    @DisplayName("창 중간의 요청은 카운터 TTL을 연장하지 않는다")
    void laterRequestDoesNotExtendCounterTtl() throws Exception {
        String clientIp = nextClientIp();
        String counterKey = mailIpLimitKey(clientIp, ipWindow);

        resendFrom(clientIp, newAccount().email()).andExpect(status().isAccepted());
        Long ttlAfterFirstRequest = redisTemplate.getExpire(counterKey, TimeUnit.MILLISECONDS);

        resendFrom(clientIp, newAccount().email()).andExpect(status().isAccepted());

        assertThat(redisTemplate.getExpire(counterKey, TimeUnit.MILLISECONDS))
                .as("두 번째 요청 뒤 IP 카운터 TTL(ms)")
                .isLessThanOrEqualTo(ttlAfterFirstRequest);
    }

    /** 한 IP의 예산을 한도까지 소모한다. 한도 안의 요청은 계정이 없어도 202다(계정 존재 여부를 노출하지 않는다). */
    private void exhaustIpBudget(String clientIp) throws Exception {
        for (long sent = 0; sent < ipLimit; sent++) {
            resendFrom(clientIp, newAccount().email()).andExpect(status().isAccepted());
        }
    }

    /**
     * 전역 카운터를 한도까지 직접 채운다. 한도가 커서 요청으로 채우면 테스트가 지나치게 느려진다.
     *
     * <p>키 형식을 테스트가 아는 결합은 {@link #clearPasswordResetInterval}과 같은 이유로 감수한다.
     */
    private void fillGlobalCounter() {
        redisTemplate
                .opsForValue()
                .set(mailGlobalLimitKey(globalWindow), Long.toString(globalLimit), globalWindow);
    }

    /** 리미터가 이 테스트 동안 남긴 WARN 메시지. 전역 제한 도달 외에 WARN을 남기는 경로는 없다. */
    private List<String> warnMessages() {
        return limiterLogs.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private Logger limiterLogger() {
        return (Logger) LoggerFactory.getLogger(AuthRateLimiter.class);
    }

    private int statusOf(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }
}
