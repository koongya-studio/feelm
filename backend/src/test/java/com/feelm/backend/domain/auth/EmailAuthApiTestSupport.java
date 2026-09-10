package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.infrastructure.mail.MailDispatcher;
import com.feelm.backend.infrastructure.mail.MailKind;
import com.feelm.backend.infrastructure.mail.RecordingAuthMailSender;
import com.feelm.backend.infrastructure.mail.RecordingMailSends;
import com.redis.testcontainers.RedisContainer;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 이메일 계정 API(API-001~005) 계약 테스트의 공통 골격이다.
 *
 * <p>요청·응답을 JSON 문자열과 MockMvc로만 다뤄 계약에 없는 프로덕션 타입에 의존하지 않는다. 사전 상태도 되도록 API 호출로 만들며, API가 없는 상태(만료,
 * 탈퇴 등)만 테스트 전용 SQL로 만든다.
 *
 * <p>레이트 리밋(재발송 간격, 로그인 연속 실패)이 Redis를 쓰므로 Testcontainers Redis를 하나만 띄워 상속 클래스 전체가 공유한다. 컨테이너와 설정이
 * 같아 Spring 컨텍스트도 한 번만 만들어진다. 그 대신 H2와 Redis 상태가 테스트 사이에 남으므로 계정은 {@link #newAccount()}로 매번 새로 만든다.
 *
 * <p>컨테이너는 {@code @Testcontainers}·{@code @Container}가 아니라 정적 초기화로 직접 띄운다. 그 방식은 컨테이너를 <b>테스트 클래스
 * 단위</b>로 중지·재시작하는데, 재시작하면 매핑 포트가 바뀌는 반면 캐시된 Spring 컨텍스트는 최초 포트를 계속 가리켜 두 번째 클래스부터 Redis 명령이
 * 타임아웃된다. JVM 수명 동안 한 번만 띄우고 정리는 Testcontainers의 Ryuk에 맡긴다.
 *
 * <p>인증 토큰 원문은 DB에 SHA-256 해시로만 남으므로(플랜 결정 8) 테스트가 되돌릴 수 없다. 그래서 발송 지점을 대체하는 스파이 빈({@link
 * RecordingAuthMailSender})에서 원문을 얻는다(플랜 결정 13). 발송 로그를 파싱하지 않으므로 인증 토큰을 로그에 남기지 않는 컨벤션 12장과 충돌하지
 * 않는다. 비밀번호 재설정 토큰(API-010)도 같은 스파이가 종류별로 나눠 기록한다(S15P21E106-284).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Import(RecordingAuthMailSender.Registrar.class)
abstract class EmailAuthApiTestSupport {

    static final String SIGNUP_PATH = "/api/v1/auth/signup";
    static final String VERIFY_PATH = "/api/v1/auth/email/verify";
    static final String RESEND_PATH = "/api/v1/auth/email/resend";
    static final String LOGIN_PATH = "/api/v1/auth/login";
    static final String LOGOUT_PATH = "/api/v1/auth/logout";
    static final String REFRESH_PATH = "/api/v1/auth/token/refresh";
    static final String RESET_REQUEST_PATH = "/api/v1/auth/password/reset-request";
    static final String RESET_PATH = "/api/v1/auth/password/reset";
    static final String CHANGE_PASSWORD_PATH = "/api/v1/users/me/password";
    static final String MEMBER_PATH = "/api/v1/test/security/me";
    static final String COOKIE_NAME = "refresh_token";

    /** 정책(플랜 결정 1)을 만족하는 기준 비밀번호. 영문·숫자·특수문자를 모두 포함한 9자다. */
    static final String VALID_PASSWORD = "P@ssw0rd1";

    static final String VALID_BIRTH_DATE = "1999-03-14";
    static final String VALID_GENDER = "FEMALE";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /**
     * 요청마다 다른 클라이언트 IP를 만드는 일련번호. 메일 발송 제한(S15P21E106-281 결정 D-12)과 로그인 시도 제한(S15P21E106-312)이 IP
     * 단위로 걸리므로, 컨텍스트를 공유하는 테스트가 같은 {@code 127.0.0.1}을 쓰면 서로의 IP 예산을 갉아먹는다. 제한 자체를 검증하는 테스트만 {@link
     * #nextClientIp()}로 얻은 IP를 고정해서 쓴다.
     */
    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger();

    /** 비동기 발송 완료를 기다리는 상한. 넘기면 테스트를 실패시킨다. */
    private static final Duration MAIL_DISPATCH_TIMEOUT = Duration.ofSeconds(10);

    @ServiceConnection static RedisContainer redis = new RedisContainer("redis:8-alpine");

    static {
        redis.start();
    }

    @Autowired protected MockMvc mockMvc;
    @Autowired protected JdbcTemplate jdbcTemplate;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected RecordingAuthMailSender authMailSender;
    @Autowired protected MailDispatcher mailDispatcher;
    @Autowired protected StringRedisTemplate redisTemplate;
    @Autowired protected ClientIpProperties clientIpPrefixes;

    /**
     * 컨텍스트를 공유하는 테스트 사이에 발송 기록이 남지 않게 한다. 발송 횟수 검증이 자기 테스트의 발송만 세야 한다.
     *
     * <p>비우기 전에 붙잡아 둔 래치를 풀고 진행 중인 발송을 기다린다. 발송이 요청 스레드를 떠났으므로(S15P21E106-282) 앞 테스트의 발송이 이 테스트로
     * 넘어와 기록될 수 있다.
     */
    @BeforeEach
    void clearSentMails() {
        authMailSender.releaseAll();
        awaitMailsDispatched();
        authMailSender.clearAll();
    }

    /** 인증 메일의 발송 기록과 제어 수단. 종류별로 분리돼 있어 한쪽을 붙잡아도 다른 쪽 발송은 진행된다. */
    protected RecordingMailSends verificationMails() {
        return authMailSender.sends(MailKind.VERIFICATION);
    }

    /** 비밀번호 재설정 메일의 발송 기록과 제어 수단. */
    protected RecordingMailSends passwordResetMails() {
        return authMailSender.sends(MailKind.PASSWORD_RESET);
    }

    /**
     * 제출된 비동기 발송이 모두 끝날 때까지 기다린다.
     *
     * <p>발송은 응답보다 늦게 끝나므로 발송 기록을 읽기 전에 이 지점이 필요하다. 제출은 응답이 나가기 전에 끝나기 때문에(커밋 후 리스너가 요청 스레드에서 제출한다)
     * 요청을 받은 뒤에 기다리면 발송을 놓치지 않는다.
     */
    protected void awaitMailsDispatched() {
        Instant deadline = Instant.now().plus(MAIL_DISPATCH_TIMEOUT);
        while (!mailDispatcher.isIdle()) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("메일 발송이 " + MAIL_DISPATCH_TIMEOUT + " 안에 끝나지 않았다");
            }
            sleepBriefly();
        }
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("발송 완료 대기가 중단됐다", interrupted);
        }
    }

    /** 테스트용 계정 값. 이메일과 닉네임은 같은 일련번호를 공유해 로그에서 짝을 알아보기 쉽게 한다. */
    record TestAccount(String email, String nickname, String password) {}

    /** 닉네임은 결정 2의 상한인 10자를 넘지 않도록 {@code user} + 6자리 일련번호로 만든다. */
    static TestAccount newAccount() {
        String suffix = String.format("%06d", SEQUENCE.incrementAndGet());
        return new TestAccount("user" + suffix + "@example.com", "user" + suffix, VALID_PASSWORD);
    }

    static String signupBody(TestAccount account) {
        return signupBody(account.email(), account.password(), account.nickname());
    }

    static String signupBody(String email, String password, String nickname) {
        return """
                {"email":"%s","password":"%s","nickname":"%s","birthDate":"%s","gender":"%s"}
                """
                .formatted(email, password, nickname, VALID_BIRTH_DATE, VALID_GENDER);
    }

    /** 테스트 사이에 IP 단위 발송 제한이 섞이지 않도록 요청마다 다른 IP에서 보낸다. */
    ResultActions postJson(String path, String body) throws Exception {
        return postJsonFrom(nextClientIp(), path, body);
    }

    /** 같은 IP에서 여러 번 보내야 하는 시나리오(IP 단위 발송 제한)용. */
    ResultActions postJsonFrom(String clientIp, String path, String body) throws Exception {
        return mockMvc.perform(
                post(path)
                        .with(
                                request -> {
                                    request.setRemoteAddr(clientIp);
                                    return request;
                                })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    /** 다른 테스트와 겹치지 않는 클라이언트 IP를 하나 얻는다. 사설 주소 대역({@code 10/8})에서만 만든다. */
    static String nextClientIp() {
        int sequence = IP_SEQUENCE.incrementAndGet();
        return "10.%d.%d.%d"
                .formatted((sequence >> 16) & 0xFF, (sequence >> 8) & 0xFF, sequence & 0xFF);
    }

    ResultActions signup(String body) throws Exception {
        return postJson(SIGNUP_PATH, body);
    }

    /** 가입(API-001)을 지정한 IP에서 보낸다. IP 단위 요청 제한을 검증하는 시나리오용(S15P21E106-287 리뷰 B-1). */
    ResultActions signupFrom(String clientIp, String body) throws Exception {
        return postJsonFrom(clientIp, SIGNUP_PATH, body);
    }

    ResultActions verifyEmail(String token) throws Exception {
        return postJson(VERIFY_PATH, "{\"token\":\"%s\"}".formatted(token));
    }

    ResultActions resend(String email) throws Exception {
        return postJson(RESEND_PATH, "{\"email\":\"%s\"}".formatted(email));
    }

    /** 재발송(API-003)을 지정한 IP에서 보낸다. */
    ResultActions resendFrom(String clientIp, String email) throws Exception {
        return postJsonFrom(clientIp, RESEND_PATH, "{\"email\":\"%s\"}".formatted(email));
    }

    ResultActions login(String email, String password) throws Exception {
        return loginFrom(nextClientIp(), email, password);
    }

    /** 로그인(API-004)을 지정한 IP에서 보낸다. IP 단위 시도 제한을 검증하는 시나리오용(S15P21E106-312). */
    ResultActions loginFrom(String clientIp, String email, String password) throws Exception {
        return postJsonFrom(
                clientIp,
                LOGIN_PATH,
                "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
    }

    ResultActions requestPasswordReset(String email) throws Exception {
        return postJson(RESET_REQUEST_PATH, "{\"email\":\"%s\"}".formatted(email));
    }

    /** 재설정 요청(API-010)을 지정한 IP에서 보낸다. */
    ResultActions requestPasswordResetFrom(String clientIp, String email) throws Exception {
        return postJsonFrom(clientIp, RESET_REQUEST_PATH, "{\"email\":\"%s\"}".formatted(email));
    }

    ResultActions resetPassword(String token, String newPassword) throws Exception {
        return postJson(
                RESET_PATH,
                "{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, newPassword));
    }

    /** 가입만 마친 {@code PENDING_VERIFICATION} 사용자를 만들고 userId를 돌려준다. */
    long signupPending(TestAccount account) throws Exception {
        MvcResult result = signup(signupBody(account)).andExpect(status().isCreated()).andReturn();
        return longField(result, "userId");
    }

    /** 가입과 인증을 모두 마친 {@code ACTIVE} 사용자를 만든다. */
    long signupActive(TestAccount account, CapturedOutput output) throws Exception {
        long userId = signupPending(account);
        verifyEmail(latestVerificationToken(output)).andExpect(status().isOk());
        return userId;
    }

    /**
     * 발송된 인증 토큰 원문을 발송 순서대로 모은다. 발송 횟수 검증에도 쓴다.
     *
     * <p>{@code output}은 쓰지 않는다. 3단계가 발송 로그 대신 스파이 빈을 seam으로 제공하면서 이 메서드만 교체했고, 호출 측 시그니처는 그대로 두었다.
     */
    List<String> verificationTokens(CapturedOutput output) {
        awaitMailsDispatched();
        return verificationMails().tokens();
    }

    String latestVerificationToken(CapturedOutput output) {
        List<String> tokens = verificationTokens(output);
        assertThat(tokens).as("인증 메일 발송 로그에서 인증 토큰을 찾지 못했다").isNotEmpty();
        return tokens.getLast();
    }

    /** 발송된 재설정 토큰 원문을 발송 순서대로 모은다. 발송 횟수 검증에도 쓴다. */
    List<String> passwordResetTokens() {
        awaitMailsDispatched();
        return passwordResetMails().tokens();
    }

    /** 재설정 요청(API-010)을 보내고 발송된 토큰 원문을 돌려준다. */
    String requestPasswordResetToken(String email) throws Exception {
        requestPasswordReset(email).andExpect(status().isAccepted());
        List<String> tokens = passwordResetTokens();
        assertThat(tokens).as("재설정 메일이 발송되지 않았다").isNotEmpty();
        return tokens.getLast();
    }

    long longField(MvcResult result, String field) throws Exception {
        return jsonOf(result).get(field).asLong();
    }

    String stringField(MvcResult result, String field) throws Exception {
        return jsonOf(result).get(field).asText();
    }

    Cookie refreshCookieOf(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(COOKIE_NAME);
        assertThat(cookie).as("응답에 %s 쿠키가 없다", COOKIE_NAME).isNotNull();
        return cookie;
    }

    /**
     * users 행의 생성 시각을 과거로 밀어 놓는다. 미인증 계정의 24시간 경과 판정(결정 3)을 검증하는 데 쓴다. 시간을 기다리거나 Clock을 주입하는 대신 저장된
     * 값을 바꾸는 쪽이 계약 테스트의 관심사와 가깝다.
     */
    void ageUser(long userId, Duration age) {
        jdbcTemplate.update(
                "update users set created_at = ? where id = ?",
                utc(Instant.now().minus(age)),
                userId);
    }

    /** 인증 토큰을 만료시킨다. 만료(410) 경로는 API로 만들 수 없다. */
    void expireVerificationTokens(long userId) {
        jdbcTemplate.update(
                "update email_verification_tokens set expires_at = ? where user_id = ?",
                utc(Instant.now().minus(Duration.ofMinutes(1))),
                userId);
    }

    /**
     * 소진 표시만 되돌린다. 유효한 미사용 토큰과 이미 활성화된 계정이 겹치는 상태({@code ALREADY_VERIFIED})는 토큰 원문을 새로 만들 수 없어 API로
     * 만들 방법이 없다.
     */
    void restoreVerificationTokens(long userId) {
        jdbcTemplate.update(
                "update email_verification_tokens set used_at = null where user_id = ?", userId);
    }

    /** 재설정 토큰을 만료시킨다. 만료(410) 경로는 API로 만들 수 없다. */
    void expirePasswordResetTokens(long userId) {
        jdbcTemplate.update(
                "update password_reset_tokens set expires_at = ? where user_id = ?",
                utc(Instant.now().minus(Duration.ofMinutes(1))),
                userId);
    }

    long unusedPasswordResetTokenCount(long userId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from password_reset_tokens"
                        + " where user_id = ? and used_at is null",
                Long.class,
                userId);
    }

    /**
     * 비밀번호가 없는 계정을 만든다. 소셜 전용 계정(API-007~009)은 -150·-209 범위라 API로 만들 수 없어, 그 계정이 갖는 상태인 {@code
     * password_hash IS NULL}만 직접 만든다.
     */
    void clearPassword(long userId) {
        jdbcTemplate.update("update users set password_hash = null where id = ?", userId);
    }

    /**
     * 재설정 요청의 간격 제한(1분)을 지운다. 같은 이메일로 두 번 요청해야 하는 시나리오는 이 제한 때문에 기다리지 않고는 만들 수 없다.
     *
     * <p>키 형식을 테스트가 알아야 하는 결합은 감수한다. 대안은 1분을 실제로 기다리거나 리미터에 테스트 전용 진입점을 두는 것인데, 둘 다 이보다 나쁘다.
     */
    void clearPasswordResetInterval(String email) {
        redisTemplate.delete("auth:password-reset:" + Sha256Hasher.hex(email));
    }

    /** IP 단위 발송 제한 카운터 키(S15P21E106-281 결정 D-7). 해시 입력은 IP 원문이 아니라 집계 버킷이다(S15P21E106-288). */
    String mailIpLimitKey(String clientIp, Duration window) {
        return "auth:mail:ip:"
                + Sha256Hasher.hex(ClientIpBucket.of(clientIp, clientIpPrefixes))
                + ":"
                + window.toSeconds();
    }

    /** 이메일 단위 로그인 시도 카운터 키. IP 축에서 거절된 요청이 이 키를 만들지 않는지 확인하는 데 쓴다. */
    String loginFailureKey(String email) {
        return "auth:login-fail:" + Sha256Hasher.hex(email);
    }

    /** IP 단위 로그인 시도 제한 카운터 키(S15P21E106-312). 해시 입력은 메일 축과 같은 집계 버킷이다(S15P21E106-288). */
    String loginIpLimitKey(String clientIp, Duration window) {
        return "auth:login:ip:"
                + Sha256Hasher.hex(ClientIpBucket.of(clientIp, clientIpPrefixes))
                + ":"
                + window.toSeconds();
    }

    /** 전역 발송 상한 카운터 키(S15P21E106-287 결정 D-10). 세는 대상이 시도가 아니라 실제 발송이라 옛 전역 키와 이름이 다르다. */
    String mailSentKey(Duration window) {
        return "auth:mail:sent:" + window.toSeconds();
    }

    /**
     * IP 요청 제한과 전역 발송 상한 카운터를 모두 지운다. 전역 카운터는 컨텍스트를 공유하는 모든 테스트의 발송이 함께 소모하므로, 상한까지 채워 검증한 테스트가 그
     * 상태를 남기면 이후 테스트의 메일이 전부 사라진다.
     *
     * <p>창 길이가 키에 들어가 설정에 따라 달라지므로 접두사 패턴으로 지운다. 이메일 간격 키({@code auth:email-resend:}, {@code
     * auth:password-reset:})는 접두사가 달라 함께 지워지지 않는다.
     */
    void clearMailSendRateLimits() {
        Set<String> keys = redisTemplate.keys("auth:mail:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    /** 탈퇴 계정을 만든다. 회원 탈퇴(API-013)는 이 업무 범위가 아니다(S15P21E106-198). */
    void withdrawUser(long userId) {
        jdbcTemplate.update(
                "update users set status = 'WITHDRAWN', withdrawn_at = ? where id = ?",
                utc(Instant.now()),
                userId);
    }

    String userStatus(long userId) {
        return jdbcTemplate.queryForObject(
                "select status from users where id = ?", String.class, userId);
    }

    Map<String, Object> notificationSettings(long userId) {
        return jdbcTemplate.queryForMap(
                "select rating_enabled, recommendation_enabled, marketing_enabled"
                        + " from notification_settings where user_id = ?",
                userId);
    }

    private JsonNode jsonOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
