package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 이메일 로그인(API-004)의 IP 단위 시도 제한을 검증한다(S15P21E106-312).
 *
 * <p>이메일 단위 연속 실패 제한은 {@link LoginContractTest}가 이미 검증한다. 여기서는 서로 다른 이메일로 흩뿌리는 시도를 막는 축과, 그 축이 이메일
 * 축과 독립인지만 다룬다.
 *
 * <p>한도 값은 테스트 설정({@code src/test/resources/application.yml})에서 읽는다. 반복 횟수를 줄이려고 운영 기본값(10분 60회,
 * 1시간 300회)보다 작게 두었기 때문이다. 상수로 적으면 설정을 바꿀 때 테스트가 조용히 다른 것을 검증하게 된다.
 *
 * <p>제한 자체를 검증하므로 요청 IP를 {@link #nextClientIp()}로 하나 받아 고정해서 쓴다. 그 밖의 요청은 공통 골격이 요청마다 다른 IP에서 보내므로
 * 컨텍스트를 공유하는 다른 테스트의 예산을 소모하지 않는다.
 */
@DisplayName("로그인 IP 단위 시도 제한 — API-004")
class LoginIpRateLimitContractTest extends EmailAuthApiTestSupport {

    @Value("${feelm.auth.login.ip[0].limit}")
    private long ipLimit;

    @Value("${feelm.auth.login.ip[0].window}")
    private Duration ipWindow;

    /** 목록의 창을 모두 적용하는지 확인하려고 두 번째 창을 첫 창과 따로 읽는다(완료 조건 3). */
    @Value("${feelm.auth.login.ip[1].limit}")
    private long secondIpLimit;

    @Value("${feelm.auth.login.ip[1].window}")
    private Duration secondIpWindow;

    @Value("${feelm.auth.login.attempt-limit}")
    private long emailAttemptLimit;

    @Test
    @DisplayName("한 IP에서 서로 다른 이메일로 한도까지 시도하면 그 다음 요청은 429 RATE_LIMITED다")
    void ipLimitRejectsAttemptsOverLimit() throws Exception {
        String clientIp = nextClientIp();

        exhaustIpBudget(clientIp);

        loginFrom(clientIp, newAccount().email(), VALID_PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    /**
     * 완료 조건 1의 "비밀번호를 대조하지 않고 끝난다"를 본다. 대조가 일어났다면 비밀번호가 맞는 요청은 200과 Refresh 쿠키를 받는다. 대조를 건너뛴 429는 그
     * 계정의 실패 카운터도 올리지 않으므로, 같은 계정이 다른 IP에서 그대로 로그인되는 것까지 함께 확인한다.
     */
    @Test
    @DisplayName("IP 한도를 넘기면 비밀번호가 맞는 요청도 대조 없이 429이고 그 계정은 잠기지 않는다")
    void blockedIpSkipsPasswordComparisonAndKeepsEmailCounter(CapturedOutput output)
            throws Exception {
        TestAccount account = newAccount();
        signupActive(account, output);
        String clientIp = nextClientIp();
        exhaustIpBudget(clientIp);

        loginFrom(clientIp, account.email(), account.password())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(cookie().doesNotExist(COOKIE_NAME));

        // 카운터 키 자체가 없어야 한다. "다른 IP에서 200"만으로는 카운터가 1이어도 통과하므로 약한 단정이다(리뷰 A-4).
        assertThat(redisTemplate.hasKey(loginFailureKey(account.email())))
                .as("IP에서 거절된 요청이 만든 이메일 카운터")
                .isFalse();
        login(account.email(), account.password()).andExpect(status().isOk());
    }

    /**
     * D-7(성공에도 IP 카운터를 지우지 않는다)을 고정한다. 이 테스트가 없으면 {@code clearLoginAttempts}에 IP 키 삭제를 더하는 퇴행이 다른
     * 테스트를 모두 통과하고, 그러면 유효한 계정 하나를 가진 공격자가 성공 로그인으로 창마다 예산을 되돌려 spraying 한도가 사라진다(리뷰 B-6).
     */
    @Test
    @DisplayName("로그인 성공은 그 IP의 예산을 되돌리지 않는다")
    void successfulLoginDoesNotRefundIpBudget(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupActive(account, output);
        String clientIp = nextClientIp();

        loginFrom(clientIp, account.email(), account.password()).andExpect(status().isOk());
        for (long attempted = 1; attempted < ipLimit; attempted++) {
            loginFrom(clientIp, newAccount().email(), VALID_PASSWORD)
                    .andExpect(status().isUnauthorized());
        }

        // 성공이 예산을 되돌렸다면 이 요청은 한도 안이라 401이다.
        loginFrom(clientIp, newAccount().email(), VALID_PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    /**
     * 이메일 축에서 거절된 요청도 IP 예산을 소모한다. IP 축이 먼저 증가한 뒤 이메일 축이 거절하기 때문이다. 그래서 잠긴 계정의 재시도가 같은 IP를 쓰는 다른
     * 사용자에게 번진다 — 운영 기본값에서 실재하는 증폭 경로이므로 테스트로 고정한다(리뷰 B-2·B-7).
     *
     * <p>테스트 IP 한도가 이메일 한도보다 커야 이 경로가 재현된다. 반대면 IP 축이 먼저 걸려 이메일 축 429가 나오지 않는다.
     */
    @Test
    @DisplayName("이메일 축에서 429가 된 요청도 IP 예산을 소모한다")
    void emailAxisRejectionConsumesIpBudget(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupActive(account, output);
        String clientIp = nextClientIp();

        for (long attempt = 0; attempt < emailAttemptLimit; attempt++) {
            loginFrom(clientIp, account.email(), "Wr0ng@pass").andExpect(status().isUnauthorized());
        }
        for (long attempt = emailAttemptLimit; attempt < ipLimit; attempt++) {
            loginFrom(clientIp, account.email(), "Wr0ng@pass")
                    .andExpect(status().isTooManyRequests());
        }

        // 이메일 카운터가 0인 새 이메일인데도 그 IP의 예산이 남아 있지 않다.
        loginFrom(clientIp, newAccount().email(), VALID_PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    /**
     * IP 축의 원자성을 본다. 확인과 기록이 분리되면 동시 요청이 모두 확인을 통과해 한도보다 많은 비밀번호 대조가 일어나는데, 그것이 시도를 세기로 한 D-1의 유일한
     * 근거다. 기존 동시성 테스트는 요청마다 IP가 달라 이 축을 지나지 않는다(리뷰 A-3·B-8).
     */
    @Test
    @DisplayName("한 IP에서 동시에 요청해도 한도만큼만 대조되고 나머지는 429다")
    void concurrentAttemptsFromOneIpAreCappedAtIpLimit() throws Exception {
        String clientIp = nextClientIp();
        int attempts = (int) ipLimit + 4;
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        List<MockHttpServletResponse> responses = new ArrayList<>();
        try {
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (int attempt = 0; attempt < attempts; attempt++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    start.await();
                                    return loginFrom(clientIp, newAccount().email(), VALID_PASSWORD)
                                            .andReturn()
                                            .getResponse();
                                }));
            }
            ready.await();
            start.countDown();
            for (Future<MockHttpServletResponse> future : futures) {
                responses.add(future.get());
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(responses)
                .filteredOn(response -> response.getStatus() == 401)
                .as("한도 안에서 대조된 요청")
                .hasSize((int) ipLimit);
        assertThat(responses)
                .filteredOn(response -> response.getStatus() != 401)
                .hasSize(attempts - (int) ipLimit)
                .allSatisfy(
                        response -> {
                            assertThat(response.getStatus()).isEqualTo(429);
                            assertThat(response.getContentAsString()).contains("RATE_LIMITED");
                        });
    }

    @Test
    @DisplayName("한 IP가 막혀도 다른 IP의 시도는 그대로 401이다")
    void limitAppliesPerIp() throws Exception {
        String blockedIp = nextClientIp();
        exhaustIpBudget(blockedIp);
        loginFrom(blockedIp, newAccount().email(), VALID_PASSWORD)
                .andExpect(status().isTooManyRequests());

        loginFrom(nextClientIp(), newAccount().email(), VALID_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    /** IP 축이 이메일 축과 독립인지 본다. 이메일 축의 한도는 IP를 매번 바꿔도 그대로 적용된다(완료 조건 5). */
    @Test
    @DisplayName("이메일 축의 한도는 IP를 바꿔가며 시도해도 그대로 적용된다")
    void emailLimitDoesNotDependOnIp(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupActive(account, output);

        for (long attempt = 0; attempt < emailAttemptLimit; attempt++) {
            loginFrom(nextClientIp(), account.email(), "Wr0ng@pass")
                    .andExpect(status().isUnauthorized());
        }

        loginFrom(nextClientIp(), account.email(), account.password())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    /**
     * 429가 가입 여부를 드러내지 않는지 본다(완료 조건 4). IP 축은 요청 이메일과 무관하게 세므로 가입한 이메일과 없는 이메일의 응답이 상태·본문 모두 같아야
     * 한다.
     */
    @Test
    @DisplayName("IP 한도 초과 응답은 가입 이메일과 미가입 이메일에서 상태·본문이 같다")
    void rateLimitedResponseDoesNotRevealAccountExistence(CapturedOutput output) throws Exception {
        TestAccount registered = newAccount();
        signupActive(registered, output);
        String unknownEmail = newAccount().email();
        String clientIp = nextClientIp();
        exhaustIpBudget(clientIp);

        MvcResult registeredAttempt =
                loginFrom(clientIp, registered.email(), registered.password()).andReturn();
        MvcResult unknownAttempt = loginFrom(clientIp, unknownEmail, VALID_PASSWORD).andReturn();

        assertThat(statusOf(registeredAttempt)).as("제한을 넘은 로그인 시도").isEqualTo(429);
        assertThat(statusOf(unknownAttempt)).isEqualTo(statusOf(registeredAttempt));
        assertThat(bodyOf(unknownAttempt)).isEqualTo(bodyOf(registeredAttempt));
    }

    /**
     * 설정한 창을 모두 적용하는지 본다. 첫 창만 평가하는 구현도 다른 테스트는 모두 통과한다.
     *
     * <p>첫 창이 만료된 상황은 그 창의 카운터를 지워서 만든다. 창 길이만큼 기다릴 수 없고, 두 번째 창의 카운터는 그대로 남아야 하기 때문이다. 키 형식을 테스트가
     * 알아야 하는 결합은 공통 골격의 다른 제한 검증과 같은 이유로 감수한다.
     */
    @Test
    @DisplayName("첫 창이 만료돼도 두 번째 창의 한도가 남아 있으면 429다")
    void everyConfiguredWindowIsApplied() throws Exception {
        String clientIp = nextClientIp();
        exhaustIpBudget(clientIp);
        loginFrom(clientIp, newAccount().email(), VALID_PASSWORD)
                .andExpect(status().isTooManyRequests());

        redisTemplate.delete(loginIpLimitKey(clientIp, ipWindow));

        for (long remaining = secondIpLimit - ipLimit; remaining > 0; remaining--) {
            loginFrom(clientIp, newAccount().email(), VALID_PASSWORD)
                    .andExpect(status().isUnauthorized());
        }
        loginFrom(clientIp, newAccount().email(), VALID_PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("모든 창의 카운터에 창 길이 이내의 TTL이 걸린다")
    void counterHasTtlWithinEachWindow() throws Exception {
        String clientIp = nextClientIp();

        loginFrom(clientIp, newAccount().email(), VALID_PASSWORD)
                .andExpect(status().isUnauthorized());

        assertThat(redisTemplate.getExpire(loginIpLimitKey(clientIp, ipWindow)))
                .as("첫 창(%s) 카운터 TTL(초)".formatted(ipWindow))
                .isBetween(1L, ipWindow.toSeconds());
        assertThat(redisTemplate.getExpire(loginIpLimitKey(clientIp, secondIpWindow)))
                .as("두 번째 창(%s) 카운터 TTL(초)".formatted(secondIpWindow))
                .isBetween(1L, secondIpWindow.toSeconds());
    }

    /**
     * 고정 창이라 창 중간의 요청은 만료를 다시 걸지 않는다(결정 D-5). 매번 만료를 거는 구현으로 퇴행하면 슬라이딩 창이 되어, 시도가 계속되는 동안 카운터가 영영
     * 만료되지 않고 그 IP가 무기한 429가 된다.
     */
    @Test
    @DisplayName("창 중간의 시도는 카운터 TTL을 연장하지 않는다")
    void laterAttemptDoesNotExtendCounterTtl() throws Exception {
        String clientIp = nextClientIp();
        String counterKey = loginIpLimitKey(clientIp, ipWindow);

        loginFrom(clientIp, newAccount().email(), VALID_PASSWORD)
                .andExpect(status().isUnauthorized());
        Long ttlAfterFirstAttempt = redisTemplate.getExpire(counterKey, TimeUnit.MILLISECONDS);

        loginFrom(clientIp, newAccount().email(), VALID_PASSWORD)
                .andExpect(status().isUnauthorized());

        assertThat(redisTemplate.getExpire(counterKey, TimeUnit.MILLISECONDS))
                .as("두 번째 시도 뒤 IP 카운터 TTL(ms)")
                .isLessThanOrEqualTo(ttlAfterFirstAttempt);
    }

    /** 한 IP의 시도 예산을 한도까지 소모한다. 없는 계정의 시도도 IP 카운터를 올리며 응답은 401이다. */
    private void exhaustIpBudget(String clientIp) throws Exception {
        for (long attempted = 0; attempted < ipLimit; attempted++) {
            loginFrom(clientIp, newAccount().email(), VALID_PASSWORD)
                    .andExpect(status().isUnauthorized());
        }
    }

    private int statusOf(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }
}
