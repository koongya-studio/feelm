package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.feelm.backend.infrastructure.mail.RedisMailSendBudget;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;

/**
 * 계정 메일 전체(API-001·003·010)의 전역 발송 상한을 검증한다(S15P21E106-287).
 *
 * <p>이 축은 요청이 아니라 <b>실제 발송</b>을 세고 발송 스레드에서 소모하므로, 응답이 아니라 발송 스파이의 기록과 Redis 카운터가 검증 대상이다. 상한에 걸린
 * 요청도 응답은 그대로 202·201이며, 그 사실 자체는 {@link MailSendRateLimitContractTest}가 함께 고정한다.
 *
 * <p>상한 값은 테스트 설정({@code src/test/resources/application.yml})에서 읽는다. 컨텍스트를 공유하는 다른 테스트의 발송도 같은 카운터를
 * 소모하므로 값을 크게 두었고, 여기서는 요청을 반복하는 대신 카운터를 직접 채운다. 카운터는 테스트마다 비운다.
 */
@DisplayName("계정 메일 전역 발송 상한 — API-001·003·010")
class MailSendBudgetContractTest extends EmailAuthApiTestSupport {

    private static final String EXHAUSTED_LOG = "전역 발송 상한에 도달";

    private static final String APPROACHING_LOG = "전역 발송 상한에 가까워졌";

    @Value("${feelm.mail.send-budget.windows[0].limit}")
    private long budgetLimit;

    @Value("${feelm.mail.send-budget.windows[0].window}")
    private Duration budgetWindow;

    /** 두 번째 창. 목록의 창을 모두 세는지 확인하려고 첫 창과 따로 읽는다. */
    @Value("${feelm.mail.send-budget.windows[1].window}")
    private Duration secondBudgetWindow;

    @Value("${feelm.mail.send-budget.windows[1].limit}")
    private long secondBudgetLimit;

    /**
     * 상한 컴포넌트가 남기는 로그를 모은다. 표준 출력에서 찾으면 콘솔 인코딩에 따라 한글이 깨질 수 있어 로거에 직접 붙인다.
     *
     * <p>WARN 줄 수 자체가 검증 대상이다(예고와 소진 각각 창마다 한 번).
     */
    private ListAppender<ILoggingEvent> budgetLogs;

    @BeforeEach
    void captureBudgetLogsAndClearCounters() {
        clearMailSendRateLimits();
        budgetLogs = new ListAppender<>();
        budgetLogs.start();
        budgetLogger().addAppender(budgetLogs);
    }

    @AfterEach
    void releaseBudgetLogsAndClearCounters() {
        budgetLogger().detachAppender(budgetLogs);
        budgetLogs.stop();
        clearMailSendRateLimits();
    }

    /**
     * 이 업무의 출발점이다(S15P21E106-281 리뷰 R-2). 시도를 세던 옛 전역 축에서는 미가입 이메일로 보낸 요청만으로 상한을 말려 정상 사용자의 계정 복구를
     * 막을 수 있었다. 발송이 없는 요청은 예산을 건드리지 않아야 한다.
     */
    @Test
    @DisplayName("발송이 없는 요청은 전역 예산을 소모하지 않는다")
    void attemptsWithoutSendDoNotConsumeBudget(CapturedOutput output) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            requestPasswordResetFrom(nextClientIp(), newAccount().email())
                    .andExpect(status().isAccepted());
        }
        awaitMailsDispatched();

        assertThat(sentCount(budgetWindow)).as("발송 없는 요청 3건 뒤 카운터").isNull();

        signupPending(newAccount());

        assertThat(verificationTokens(output)).as("실제 발송").hasSize(1);
        assertThat(sentCount(budgetWindow)).as("발송 1건 뒤 카운터").isEqualTo("1");
    }

    @Test
    @DisplayName("상한을 넘기면 응답은 202 그대로이고 메일만 발송되지 않는다")
    void exhaustedBudgetKeepsResponseAndSkipsSend(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupPending(account);
        verificationTokens(output);
        verificationMails().clear();
        fillSendBudget(budgetLimit);

        resendFrom(nextClientIp(), account.email()).andExpect(status().isAccepted());

        assertThat(verificationTokens(output)).as("상한을 넘긴 뒤의 발송").isEmpty();
    }

    /**
     * 가입 인증 메일과 재설정 메일은 같은 SMTP 계정의 쿼터를 쓰므로 한 예산으로 묶는다(결정 D-5). 마지막 한 칸을 가입 메일이 쓰면 재설정 메일은 나가지 않아야
     * 한다. 옛 전역 축은 가입 경로를 세지 않아 쿼터 보호가 반쪽이었다.
     */
    @Test
    @DisplayName("가입 인증 메일과 재설정 메일이 같은 예산을 쓴다")
    void budgetIsSharedAcrossMailKinds(CapturedOutput output) throws Exception {
        TestAccount active = newAccount();
        signupActive(active, output);
        verificationTokens(output);
        verificationMails().clear();
        fillSendBudget(budgetLimit - 1);

        signup(signupBody(newAccount())).andExpect(status().isCreated());

        assertThat(verificationTokens(output)).as("마지막 한 칸을 쓴 가입 인증 메일").hasSize(1);

        requestPasswordResetFrom(nextClientIp(), active.email()).andExpect(status().isAccepted());

        assertThat(passwordResetTokens()).as("예산이 마른 뒤의 재설정 메일").isEmpty();
    }

    /** 첫 창만 세면 목록의 첫 항목만 보는 구현도 이 클래스를 통과한다. 두 창이 각각 오르고 각자의 창 안에서 만료되는지 본다. */
    @Test
    @DisplayName("발송은 모든 창의 카운터를 올리고 창 길이 이내의 TTL이 걸린다")
    void everyWindowCountsSendsAndExpires(CapturedOutput output) throws Exception {
        signupPending(newAccount());

        assertThat(verificationTokens(output)).hasSize(1);
        assertThat(sentCount(budgetWindow))
                .as("첫 창(%s) 카운터".formatted(budgetWindow))
                .isEqualTo("1");
        assertThat(sentCount(secondBudgetWindow))
                .as("두 번째 창(%s) 카운터".formatted(secondBudgetWindow))
                .isEqualTo("1");
        assertThat(redisTemplate.getExpire(mailSentKey(budgetWindow)))
                .as("첫 창 카운터 TTL(초)")
                .isBetween(1L, budgetWindow.toSeconds());
        assertThat(redisTemplate.getExpire(mailSentKey(secondBudgetWindow)))
                .as("두 번째 창 카운터 TTL(초)")
                .isBetween(1L, secondBudgetWindow.toSeconds());
    }

    /** 버려지는 발송마다 WARN을 남기면 부하가 이어지는 동안 같은 줄이 쌓여 정작 필요한 기록을 밀어낸다(결정 D-8). */
    @Test
    @DisplayName("소진 WARN은 창마다 한 번만 남는다")
    void logsExhaustionWarnOncePerWindow(CapturedOutput output) throws Exception {
        fillSendBudget(budgetLimit);

        for (int attempt = 0; attempt < 3; attempt++) {
            signup(signupBody(newAccount())).andExpect(status().isCreated());
        }

        assertThat(verificationTokens(output)).as("상한을 넘긴 뒤의 발송").isEmpty();
        assertThat(warnMessages(EXHAUSTED_LOG)).as("소진 WARN 줄").hasSize(1);
    }

    /**
     * 소진 로그만 있으면 예산이 마른 뒤에야 알게 되고, 마른 상태는 정상 사용자의 메일이 조용히 사라지는 상태다. 상한의 80%에 닿는 순간 창마다 한 번 예고가 남는지
     * 본다(결정 D-8).
     */
    @Test
    @DisplayName("사용률이 상한의 80%에 닿으면 예고 WARN이 창마다 한 번 남는다")
    void logsApproachingWarnOncePerWindow(CapturedOutput output) throws Exception {
        fillSendBudget(approachingThreshold() - 1);

        signup(signupBody(newAccount())).andExpect(status().isCreated());
        signup(signupBody(newAccount())).andExpect(status().isCreated());

        assertThat(verificationTokens(output)).as("예고 지점의 발송은 그대로 나간다").hasSize(2);
        assertThat(warnMessages(APPROACHING_LOG)).as("예고 WARN 줄").hasSize(1);
        assertThat(warnMessages(EXHAUSTED_LOG)).as("소진 WARN 줄").isEmpty();
    }

    /**
     * 예산이 마른 동안의 재발송이 사용자가 이미 받은 링크를 죽이면 안 된다(리뷰 B-2). 토큰 회전은 트랜잭션 안에서 기존 토큰을 무효화하는데 발송 여부는 커밋 뒤에
     * 정해지므로, 확인 없이 두면 살아 있던 링크만 사라지고 새 메일은 오지 않는다.
     */
    @Test
    @DisplayName("상한이 마른 동안의 재발송은 이미 보낸 링크를 무효화하지 않는다")
    void exhaustedBudgetKeepsExistingLinkAlive(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupPending(account);
        String issuedToken = latestVerificationToken(output);
        fillSendBudget(budgetLimit);

        resendFrom(nextClientIp(), account.email()).andExpect(status().isAccepted());

        verifyEmail(issuedToken).andExpect(status().isOk());
    }

    /**
     * 상한이 지키는 것은 SMTP 계정의 쿼터이고 실패한 발송은 쿼터를 쓰지 않는다(리뷰 B-4). 되돌리지 않으면 자격 증명 폐기처럼 즉시 실패하는 장애에서 상한이
     * 순식간에 마른다.
     */
    @Test
    @DisplayName("발송이 실패하면 예산 자리를 되돌린다")
    void failedSendReleasesBudget(CapturedOutput output) throws Exception {
        verificationMails().failNextSend();

        signupPending(newAccount());
        awaitMailsDispatched();

        assertThat(sentCount(budgetWindow)).as("실패한 발송 뒤 카운터").isEqualTo("0");

        signupPending(newAccount());

        assertThat(verificationTokens(output)).as("되돌린 자리로 다음 메일이 나간다").hasSize(1);
        assertThat(sentCount(budgetWindow)).as("성공한 발송 뒤 카운터").isEqualTo("1");
    }

    /**
     * 창 도중에 상한을 내리면 카운터는 이미 임계값을 지나쳐 있다. 정확 일치로 판정하면 그 창에서는 경고가 영영 나오지 않아, 재배포 없이 값을 조정하는 통로(결정
     * D-9)가 인지 수단(결정 D-8)을 조용히 무력화한다(리뷰 B-5).
     */
    @Test
    @DisplayName("카운터가 임계를 이미 지나쳐도 소진·예고 WARN이 남는다")
    void logsWarnEvenWhenCounterPassedThresholds(CapturedOutput output) throws Exception {
        fillSendBudget(budgetLimit + 50);

        signup(signupBody(newAccount())).andExpect(status().isCreated());
        awaitMailsDispatched();

        assertThat(warnMessages(EXHAUSTED_LOG)).as("소진 WARN 줄").hasSize(1);
    }

    /**
     * 창을 순서대로 올리면 뒤 창이 막을 때 앞 창만 소모된다. 일 창이 마른 구간에서 시도마다 시간 창이 발송 없이 타면, 시간 창 소진 경고가 진짜 원인을 가리고 일
     * 창이 만료된 뒤에도 최대 한 시간 더 막힌다(리뷰 A-2).
     */
    @Test
    @DisplayName("긴 창이 말라도 짧은 창의 카운터는 타지 않는다")
    void exhaustedLongWindowDoesNotBurnShortWindow(CapturedOutput output) throws Exception {
        redisTemplate
                .opsForValue()
                .set(
                        mailSentKey(secondBudgetWindow),
                        Long.toString(secondBudgetLimit),
                        secondBudgetWindow);

        signup(signupBody(newAccount())).andExpect(status().isCreated());
        awaitMailsDispatched();

        assertThat(verificationTokens(output)).as("긴 창이 마르면 발송하지 않는다").isEmpty();
        assertThat(sentCount(budgetWindow)).as("짧은 창 카운터").isNull();
        assertThat(sentCount(secondBudgetWindow))
                .as("긴 창 카운터")
                .isEqualTo(Long.toString(secondBudgetLimit));
    }

    /** 예고가 남는 발송 수. 구현과 같은 식({@code limit - limit / 5} = 상한의 80% 올림)을 쓴다. */
    private long approachingThreshold() {
        return budgetLimit - budgetLimit / 5;
    }

    /**
     * 카운터를 원하는 값까지 직접 채운다. 상한이 커서 발송으로 채우면 테스트가 지나치게 느려진다.
     *
     * <p>키 형식을 테스트가 아는 결합은 {@link #clearPasswordResetInterval}과 같은 이유로 감수한다.
     */
    private void fillSendBudget(long count) {
        redisTemplate
                .opsForValue()
                .set(mailSentKey(budgetWindow), Long.toString(count), budgetWindow);
    }

    private String sentCount(Duration window) {
        return redisTemplate.opsForValue().get(mailSentKey(window));
    }

    /** 상한 컴포넌트가 이 테스트 동안 남긴 WARN 중 주어진 문구를 담은 줄. */
    private List<String> warnMessages(String contains) {
        return budgetLogs.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains(contains))
                .toList();
    }

    private Logger budgetLogger() {
        return (Logger) LoggerFactory.getLogger(RedisMailSendBudget.class);
    }
}
