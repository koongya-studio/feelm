package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.auth.entity.OAuthProvider;
import com.feelm.backend.global.security.TokenProvider;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * API-013 회원 탈퇴의 계약을 검증한다.
 *
 * <p>서비스는 {@code domain/user}에 있지만 테스트는 계정 API 골격 위에 둔다. 탈퇴는 가입·인증·로그인으로 만든 실제 계정과 발급된 토큰이 있어야 확인할
 * 수 있고, 소셜 연결 수단 삭제까지 보려면 {@link OAuthApiTestSupport}의 provider 대역이 필요하다. 비밀번호 변경(API-012)의 계약 테스트가
 * 같은 이유로 이 골격을 쓴다.
 *
 * <p>이번 범위는 이메일 계정 경로뿐이다. 소셜 전용 계정은 {@code PASSWORD_NOT_SET}으로 거절되는 것까지만 고정한다(플랜 결정 U1).
 */
@DisplayName("회원 탈퇴 — API-013")
class UserWithdrawalContractTest extends OAuthApiTestSupport {

    private static final String WITHDRAWAL_PATH = "/api/v1/users/me";

    /** 미인증 계정처럼 로그인으로는 토큰을 받을 수 없는 상태를 만들 때만 쓴다. */
    @Autowired private TokenProvider tokenProvider;

    @Test
    @DisplayName("T1 비밀번호가 맞으면 204와 Refresh 쿠키 삭제로 응답하고 계정이 WITHDRAWN이 된다")
    void withdrawsEmailAccount(CapturedOutput output) throws Exception {
        Member member = newMember(output);

        MvcResult result =
                withdraw(member.accessToken(), member.password())
                        .andExpect(status().isNoContent())
                        .andReturn();

        assertThat(userStatus(member.userId())).isEqualTo("WITHDRAWN");
        assertThat(withdrawnAt(member.userId())).isNotNull();

        // 쿠키는 이 요청에 실려 오지 않지만, 응답은 계약된 Path의 쿠키를 만료시켜야 한다.
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .contains(COOKIE_NAME + "=")
                .contains("Max-Age=0")
                .contains("/api/v1/auth");
    }

    @Test
    @DisplayName("T2 식별 가능한 값을 치환하고 계정 처리 목적이 끝난 값을 비운다 (D-9)")
    void anonymizesIdentifyingColumns(CapturedOutput output) throws Exception {
        Member member = newMember(output);

        withdraw(member.accessToken(), member.password()).andExpect(status().isNoContent());

        assertThat(stringColumn(member.userId(), "email"))
                .isEqualTo("withdrawn-" + member.userId() + "@feelm.invalid");
        assertThat(stringColumn(member.userId(), "nickname"))
                .isEqualTo("withdrawn-" + member.userId());
        assertThat(stringColumn(member.userId(), "password_hash")).isNull();
        assertThat(stringColumn(member.userId(), "birth_date")).isNull();
        assertThat(stringColumn(member.userId(), "gender")).isNull();
        assertThat(stringColumn(member.userId(), "visibility")).isEqualTo("PRIVATE");
    }

    @Test
    @DisplayName("T3 비밀번호가 틀리면 401 INVALID_CREDENTIALS이고 계정은 그대로다")
    void rejectsWrongPassword(CapturedOutput output) throws Exception {
        Member member = newMember(output);

        withdraw(member.accessToken(), "WrongP@ssw0rd9")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        assertThat(userStatus(member.userId())).isEqualTo("ACTIVE");
        assertThat(stringColumn(member.userId(), "password_hash")).isNotNull();
    }

    @Test
    @DisplayName("T4 인증 없이 호출하면 401이다")
    void anonymousRequestIsRejected() throws Exception {
        mockMvc.perform(
                        delete(WITHDRAWAL_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(withdrawalBody(VALID_PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("T5 탈퇴한 계정의 Access Token은 잔여 수명 안이라도 다시 탈퇴할 수 없다")
    void withdrawnAccountCannotWithdrawAgain(CapturedOutput output) throws Exception {
        Member member = newMember(output);
        withdraw(member.accessToken(), member.password()).andExpect(status().isNoContent());
        String withdrawnAt = withdrawnAt(member.userId());

        withdraw(member.accessToken(), member.password())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(withdrawnAt(member.userId())).isEqualTo(withdrawnAt);
    }

    @Test
    @DisplayName("T6 연결된 소셜 수단과 인증·재설정 토큰을 모두 지운다")
    void deletesLinkedProvidersAndTokens(CapturedOutput output) throws Exception {
        Member member = newMember(output);
        fake(OAuthProvider.GOOGLE).willReturn(newProfile());
        oauthLink(OAuthProvider.GOOGLE, member.accessToken(), VALID_CODE, ALLOWED_LINK_REDIRECT_URI)
                .andExpect(status().isCreated());
        requestPasswordResetToken(member.email());

        assertThat(linkedProviderCount(member.userId())).isEqualTo(1);
        assertThat(unusedPasswordResetTokenCount(member.userId())).isEqualTo(1);

        withdraw(member.accessToken(), member.password()).andExpect(status().isNoContent());

        assertThat(linkedProviderCount(member.userId())).isZero();
        assertThat(unusedPasswordResetTokenCount(member.userId())).isZero();
        assertThat(rowCount("email_verification_tokens", member.userId())).isZero();
        assertThat(rowCount("password_reset_tokens", member.userId())).isZero();
    }

    @Test
    @DisplayName("T7 다른 기기의 Refresh Token까지 모두 폐기한다")
    void revokesAllRefreshTokens(CapturedOutput output) throws Exception {
        Member member = newMember(output);
        Cookie firstDevice = refreshCookieOf(loginResult(member));
        Cookie secondDevice = refreshCookieOf(loginResult(member));

        withdraw(member.accessToken(), member.password()).andExpect(status().isNoContent());

        for (Cookie cookie : new Cookie[] {firstDevice, secondDevice}) {
            mockMvc.perform(post(REFRESH_PATH).cookie(cookie)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("T8 같은 이메일·닉네임으로 즉시 재가입할 수 있다 (D-9). 유일 제약 자체는 PostgreSQL 테스트가 본다")
    void allowsImmediateResignupWithSameEmailAndNickname(CapturedOutput output) throws Exception {
        Member member = newMember(output);
        withdraw(member.accessToken(), member.password()).andExpect(status().isNoContent());

        signup(signupBody(member.email(), member.password(), member.nickname()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("T9 탈퇴한 계정으로는 로그인할 수 없다")
    void withdrawnAccountCannotLogIn(CapturedOutput output) throws Exception {
        Member member = newMember(output);
        withdraw(member.accessToken(), member.password()).andExpect(status().isNoContent());

        login(member.email(), member.password())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("T10 미인증 계정은 탈퇴할 수 없다")
    void rejectsPendingVerificationAccount() throws Exception {
        TestAccount account = newAccount();
        long userId = signupPending(account);

        withdraw(tokenProvider.createAccessToken(userId), account.password())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(userStatus(userId)).isEqualTo("PENDING_VERIFICATION");
    }

    @Test
    @DisplayName("T13 계정에 매달린 OTT 구독·알림 설정·온보딩 취향·차단(양방향)을 지운다")
    void deletesAccountAdjacentData(CapturedOutput output) throws Exception {
        Member member = newMember(output);
        Member other = newMember(output);

        subscribeToTestOtt(member.userId());
        mockMvc.perform(
                        put("/api/v1/users/me/notification-settings")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + member.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"RATING":false,"RECOMMENDATION":false,"MARKETING":true}
                                        """))
                .andExpect(status().isOk());
        block(member.accessToken(), other.userId());
        block(other.accessToken(), member.userId());

        insertOnboardingPreference(member.userId());
        jdbcTemplate.update(
                "INSERT INTO notifications(user_id,type,source_id,target_type,target_id,title,body,"
                        + "notified_at,expires_at) VALUES"
                        + " (?,'PARTY_INVITE',?,'PARTY_INVITATION',?,'제목','본문',CURRENT_TIMESTAMP,"
                        + " DATEADD('DAY',90,CURRENT_TIMESTAMP))",
                member.userId(),
                member.userId(),
                member.userId());

        assertThat(rowCount("user_otts", member.userId())).isEqualTo(1);
        assertThat(rowCount("onboarding_preferences", member.userId())).isEqualTo(1);
        assertThat(rowCount("notification_settings", member.userId())).isEqualTo(1);
        assertThat(rowCount("notifications", member.userId())).isEqualTo(1);
        assertThat(blockRowCount(member.userId())).isEqualTo(2);

        withdraw(member.accessToken(), member.password()).andExpect(status().isNoContent());

        assertThat(rowCount("user_otts", member.userId())).isZero();
        assertThat(rowCount("notification_settings", member.userId())).isZero();
        assertThat(rowCount("notifications", member.userId())).isZero();
        assertThat(rowCount("onboarding_preferences", member.userId())).isZero();
        assertThat(blockRowCount(member.userId())).isZero();
    }

    @Test
    @DisplayName("S15P21E106-582 탈퇴 시 Web Push capability 원문과 대기 발송을 제거한다")
    void withdrawalPurgesPushSubscriptionSecrets(CapturedOutput output) throws Exception {
        Member member = newMember(output);
        jdbcTemplate.update(
                "INSERT INTO notifications(user_id,type,source_id,target_type,target_id,title,body,"
                        + "notified_at,expires_at) VALUES"
                        + " (?,'PARTY_INVITE',?,'PARTY_INVITATION',?,'제목','본문',CURRENT_TIMESTAMP,"
                        + " DATEADD('DAY',90,CURRENT_TIMESTAMP))",
                member.userId(),
                member.userId(),
                member.userId());
        jdbcTemplate.update(
                "INSERT INTO web_push_subscriptions(user_id,endpoint,endpoint_fingerprint,"
                        + "p256dh_key,auth_secret,active,capability_version)"
                        + " VALUES (?,?,?,?,?,true,1)",
                member.userId(),
                "https://fcm.googleapis.com/fcm/send/withdrawal",
                "f".repeat(64),
                "secret-p256dh",
                "secret-auth");
        long subscriptionId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM web_push_subscriptions WHERE endpoint_fingerprint=?",
                        Long.class,
                        "f".repeat(64));
        jdbcTemplate.update(
                "INSERT INTO web_push_deliveries(notification_id,subscription_id,"
                        + "endpoint_fingerprint,status,next_attempt_at) VALUES"
                        + " ((SELECT id FROM notifications WHERE user_id=?),?,?,'PENDING',CURRENT_TIMESTAMP)",
                member.userId(),
                subscriptionId,
                "f".repeat(64));

        withdraw(member.accessToken(), member.password()).andExpect(status().isNoContent());

        assertThat(
                        jdbcTemplate.queryForMap(
                                "SELECT active,endpoint,p256dh_key,auth_secret"
                                        + " FROM web_push_subscriptions WHERE id=?",
                                subscriptionId))
                .containsEntry("ACTIVE", false)
                .containsEntry("ENDPOINT", null)
                .containsEntry("P256DH_KEY", null)
                .containsEntry("AUTH_SECRET", null);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM web_push_deliveries", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("T14 소셜 전용 계정이 password를 생략하면 409 PASSWORD_NOT_SET이다 (플랜 결정 U1)")
    void rejectsSocialOnlyAccount(CapturedOutput output) throws Exception {
        Member member = newMember(output);
        clearPassword(member.userId());

        // 소셜 전용 클라이언트는 보낼 비밀번호가 없다. 값 검증이 계정 판정보다 앞서면 이 요청은 400이 되어
        // 계약이 약속한 409에 영영 닿지 못한다(리뷰 A-F7·B-F6).
        withdrawWithBody(member.accessToken(), "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PASSWORD_NOT_SET"));

        assertThat(userStatus(member.userId())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("T15 이메일 계정이 password를 생략하면 400 VALIDATION_FAILED다")
    void rejectsMissingPasswordOnEmailAccount(CapturedOutput output) throws Exception {
        Member member = newMember(output);

        withdrawWithBody(member.accessToken(), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(userStatus(member.userId())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("T16 탈퇴 익명화가 쓰는 예약 도메인으로는 가입할 수 없다 (리뷰 A-F3·B-F2)")
    void rejectsSignupWithReservedEmail() throws Exception {
        TestAccount account = newAccount();

        signup(signupBody("withdrawn-1@feelm.invalid", account.password(), account.nickname()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 대소문자를 바꿔도 같은 판정이어야 한다. 이메일은 소문자로 정규화해 저장한다.
        signup(signupBody("Withdrawn-1@FEELM.INVALID", account.password(), account.nickname()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("T17 탈퇴한 계정의 Refresh Token은 회전에서도 거절된다 (리뷰 A-F4·B-F3)")
    void withdrawnAccountCannotRotateRefreshToken(CapturedOutput output) throws Exception {
        Member member = newMember(output);
        Cookie cookie = refreshCookieOf(loginResult(member));

        // 폐기를 우회해 저장소에 남은 토큰을 흉내낸다. 탈퇴와 동시에 진행되던 로그인이 만들어 내는 상태다.
        withdrawUser(member.userId());

        mockMvc.perform(post(REFRESH_PATH).cookie(cookie)).andExpect(status().isUnauthorized());
    }

    // ----- 보조 -----

    private ResultActions withdraw(String accessToken, String password) throws Exception {
        return mockMvc.perform(
                delete(WITHDRAWAL_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withdrawalBody(password)));
    }

    private ResultActions withdrawWithBody(String accessToken, String body) throws Exception {
        return mockMvc.perform(
                delete(WITHDRAWAL_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private static String withdrawalBody(String password) {
        return "{\"password\":\"%s\"}".formatted(password);
    }

    private ResultActions block(String accessToken, long targetUserId) throws Exception {
        return mockMvc.perform(
                        post("/api/v1/users/{userId}/block", targetUserId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isCreated());
    }

    /** 기준정보가 시드되지 않는 골격이라 구독 대상 OTT를 직접 만든다. 이미 있으면 그대로 쓴다. */
    private void subscribeToTestOtt(long userId) {
        jdbcTemplate.update(
                """
                insert into otts (id, code, name, tmdb_provider_id)
                select 9001, 'TEST_OTT', '테스트 OTT', 99001
                 where not exists (select 1 from otts where id = 9001)
                """);
        jdbcTemplate.update("insert into user_otts (user_id, ott_id) values (?, 9001)", userId);
    }

    /**
     * 온보딩 취향 한 건을 직접 넣는다.
     *
     * <p>카드 조회(API-023)는 {@code movies}와 대표 맛 기준정보가 있어야 하고 그 테이블은 Entity가 없어 이 골격에 없다. 여기서 볼 것은 탈퇴가
     * 이 행을 지우는지뿐이라 API를 거치지 않는다. {@code movie_id}는 Entity에 FK 없이 선언된 컬럼이라 값만 있으면 된다.
     */
    private void insertOnboardingPreference(long userId) {
        jdbcTemplate.update(
                "insert into onboarding_preferences (user_id, movie_id, preference, created_at)"
                        + " values (?, 9001, 'LIKE', current_timestamp)",
                userId);
    }

    private long rowCount(String table, long userId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from " + table + " where user_id = ?", Long.class, userId);
    }

    private long blockRowCount(long userId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from user_blocks where blocker_id = ? or blocked_id = ?",
                Long.class,
                userId,
                userId);
    }

    private String withdrawnAt(long userId) {
        return stringColumn(userId, "withdrawn_at");
    }

    private String stringColumn(long userId, String column) {
        Object value =
                jdbcTemplate.queryForObject(
                        "select " + column + " from users where id = ?", Object.class, userId);
        return value == null ? null : value.toString();
    }

    private MvcResult loginResult(Member member) throws Exception {
        return login(member.email(), member.password()).andExpect(status().isOk()).andReturn();
    }

    /** 탈퇴 대상이 되는 활성 이메일 계정과 그 계정의 Access Token. */
    private Member newMember(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        long userId = signupActive(account, output);
        MvcResult result =
                login(account.email(), account.password()).andExpect(status().isOk()).andReturn();
        return new Member(
                userId,
                account.email(),
                account.nickname(),
                account.password(),
                stringField(result, "accessToken"));
    }

    private record Member(
            long userId, String email, String nickname, String password, String accessToken) {}
}
