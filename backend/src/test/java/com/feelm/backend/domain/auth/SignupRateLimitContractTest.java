package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 이메일 회원가입(API-001)의 IP 단위 요청 제한을 검증한다(S15P21E106-603).
 *
 * <p>핵심은 <b>메일 축과의 분리</b>다. 인증번호 전환 전에는 가입이 인증 메일을 보내 같은 SMTP 쿼터를 소모했으므로 메일 카운터를 공유했지만, 지금 이 경로는
 * 메일을 보내지 않는다. 공유를 유지하면 가입 한 건이 같은 예산을 발송(API-078)과 가입으로 두 번 소모해 공유 NAT 한 버킷의 실질 가입 한도가 절반이 된다(601
 * 결정 4).
 */
@DisplayName("회원가입 요청 제한 — API-001")
class SignupRateLimitContractTest extends EmailAuthApiTestSupport {

    @Value("${feelm.auth.signup.ip[0].limit}")
    private long signupIpLimit;

    @Value("${feelm.auth.signup.ip[0].window}")
    private Duration signupIpWindow;

    @Value("${feelm.mail.rate-limit.ip[0].limit}")
    private long mailIpLimit;

    @AfterEach
    void clearLimitCounters() {
        clearMailSendRateLimits();
    }

    @Test
    @DisplayName("같은 IP에서 한도를 넘긴 가입은 429 RATE_LIMITED다")
    void rejectsOverIpLimit() throws Exception {
        String clientIp = nextClientIp();
        exhaustSignupBudget(clientIp);

        signupFrom(clientIp, signupBody(newAccount()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    /** 가입 한 건이 발송과 가입으로 예산을 두 번 소모하면 안 된다. 메일 예산이 말라도 가입은 통과한다. */
    @Test
    @DisplayName("메일 IP 예산이 소진돼도 가입은 201이다")
    void usesSeparateCounterFromMailAxis() throws Exception {
        String clientIp = nextClientIp();
        String body = signupBody(newAccount());
        for (long sent = 0; sent < mailIpLimit; sent++) {
            requestPasswordResetFrom(clientIp, newAccount().email())
                    .andExpect(status().isAccepted());
        }
        requestPasswordResetFrom(clientIp, newAccount().email())
                .andExpect(status().isTooManyRequests());

        signupFrom(clientIp, body).andExpect(status().isCreated());
    }

    /** 반대 방향도 본다. 가입이 메일 예산을 소모하면 가입 버스트가 같은 IP의 계정 복구 메일을 마르게 한다. */
    @Test
    @DisplayName("가입은 메일 발송 예산을 소모하지 않는다")
    void doesNotConsumeMailBudget() throws Exception {
        String clientIp = nextClientIp();

        exhaustSignupBudget(clientIp);

        requestPasswordResetFrom(clientIp, newAccount().email()).andExpect(status().isAccepted());
    }

    /** 제한을 토큰 검증·중복 검사보다 먼저 두어, 429가 토큰이나 이메일의 상태를 알아내는 수단이 되지 않게 한다. */
    @Test
    @DisplayName("한도를 넘긴 요청은 토큰을 검증하기 전에 429가 된다")
    void rateLimitedBeforeTokenValidation() throws Exception {
        String clientIp = nextClientIp();
        exhaustSignupBudget(clientIp);

        MvcResult forged =
                signupFrom(
                                clientIp,
                                signupBodyWithToken(
                                        "not.a.valid-token",
                                        VALID_PASSWORD,
                                        newAccount().nickname()))
                        .andExpect(status().isTooManyRequests())
                        .andReturn();
        MvcResult valid =
                signupFrom(clientIp, signupBody(newAccount()))
                        .andExpect(status().isTooManyRequests())
                        .andReturn();

        assertThat(valid.getResponse().getContentAsString())
                .as("유효한 토큰과 위조 토큰의 제한 응답")
                .isEqualTo(forged.getResponse().getContentAsString());
    }

    /**
     * 제한을 중복 검사보다 먼저 적용해, {@code 429}가 닉네임의 사용 여부를 알아내는 수단이 되지 않게 한다. 메일 축에 있던 같은 성질의 테스트가 카운터 분리로
     * 옮겨 왔다(리뷰 A-F3 · B-4).
     */
    @Test
    @DisplayName("제한 초과 응답은 중복 닉네임과 새 닉네임에서 같다")
    void rateLimitedResponseDoesNotRevealDuplication() throws Exception {
        TestAccount registered = newAccount();
        signupActive(registered);
        String duplicatedBody =
                signupBody(newAccount().email(), VALID_PASSWORD, registered.nickname());
        String freshBody = signupBody(newAccount());
        String clientIp = nextClientIp();
        exhaustSignupBudget(clientIp);

        MvcResult duplicated = signupFrom(clientIp, duplicatedBody).andReturn();
        MvcResult fresh = signupFrom(clientIp, freshBody).andReturn();

        assertThat(duplicated.getResponse().getStatus()).as("중복 닉네임 가입").isEqualTo(429);
        assertThat(fresh.getResponse().getStatus()).isEqualTo(duplicated.getResponse().getStatus());
        assertThat(fresh.getResponse().getContentAsString())
                .isEqualTo(duplicated.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("한 IP가 막혀도 다른 IP는 201이다")
    void limitAppliesPerIp() throws Exception {
        String blockedIp = nextClientIp();
        exhaustSignupBudget(blockedIp);
        signupFrom(blockedIp, signupBody(newAccount())).andExpect(status().isTooManyRequests());

        signupFrom(nextClientIp(), signupBody(newAccount())).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("가입 카운터에는 창 길이 이내의 TTL이 걸린다")
    void counterExpiresWithinWindow() throws Exception {
        String clientIp = nextClientIp();

        signupFrom(clientIp, signupBody(newAccount())).andExpect(status().isCreated());

        assertThat(redisTemplate.getExpire(signupIpLimitKey(clientIp, signupIpWindow)))
                .as("가입 카운터 TTL(초)")
                .isBetween(1L, signupIpWindow.toSeconds());
    }

    /** 한 IP의 가입 예산을 한도까지 소모한다. */
    private void exhaustSignupBudget(String clientIp) throws Exception {
        for (long sent = 0; sent < signupIpLimit; sent++) {
            signupFrom(clientIp, signupBody(newAccount())).andExpect(status().isCreated());
        }
    }
}
