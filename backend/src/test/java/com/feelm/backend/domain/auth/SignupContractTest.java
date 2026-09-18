package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * API-001 이메일 회원가입의 계약을 검증한다.
 *
 * <p>가입할 이메일은 요청 본문이 아니라 인증번호 확인(API-079)이 발급한 토큰에서만 읽고, 계정은 인증 대기 상태 없이 곧바로 {@code ACTIVE}가 되며 응답은
 * 소셜 가입(API-008)과 같은 형태로 세션을 함께 내린다(S15P21E106-603).
 *
 * <p>비밀번호·닉네임 규칙은 명세의 '가정' 값이 아니라 플랜 6장의 결정 1·2를, 잔존 미인증 계정의 재가입은 결정 3을, 알림 설정 기본행은 결정 10을, {@code
 * gender} 필수는 결정 11을 기준으로 한다.
 */
@DisplayName("회원가입 — API-001")
class SignupContractTest extends EmailAuthApiTestSupport {

    /** 서명을 통과하지 못하는 토큰. 형식 검증만 통과시켜 서비스의 토큰 검증까지 보낸다. */
    private static final String FORGED_TOKEN = "not.a.valid-token";

    /** 소셜 가입 토큰을 만들어 토큰 종류 분리를 확인하는 데만 쓴다. */
    @org.springframework.beans.factory.annotation.Autowired private TokenProvider tokenProvider;

    @Test
    @DisplayName("가입에 성공하면 201과 함께 ACTIVE 계정·세션을 반환한다")
    void validSignupReturnsActiveAccountWithSession() throws Exception {
        TestAccount account = newAccount();

        MvcResult result =
                signup(signupBody(account))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.accessToken").isNotEmpty())
                        .andExpect(jsonPath("$.expiresIn").isNumber())
                        .andExpect(jsonPath("$.user.userId").isNumber())
                        .andExpect(jsonPath("$.user.nickname").value(account.nickname()))
                        .andExpect(jsonPath("$.user.onboardingCompleted").value(false))
                        .andReturn();

        assertThat(userStatus(signupUserId(result))).isEqualTo("ACTIVE");
        assertThat(refreshCookieOf(result).getValue()).as("refresh 쿠키").isNotBlank();
    }

    /** 가입 직후 온보딩으로 이어지려면 응답의 Access Token이 곧바로 회원 API에 통해야 한다. */
    @Test
    @DisplayName("응답의 Access Token으로 회원 전용 API를 호출할 수 있다")
    void issuedTokenCallsMemberApi() throws Exception {
        MvcResult result =
                signup(signupBody(newAccount())).andExpect(status().isCreated()).andReturn();
        String accessToken = stringField(result, "accessToken");

        mockMvc.perform(get(MEMBER_PATH).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    /** 인증 메일 발송 이벤트는 이 경로에서 사라졌다. 메일은 인증번호 발송(API-078)만 보낸다. */
    @Test
    @DisplayName("가입은 메일을 보내지 않는다")
    void doesNotSendVerificationMail() throws Exception {
        signup(signupBody(newAccount())).andExpect(status().isCreated());
        awaitMailsDispatched();

        assertThat(verificationMails().tokens()).as("가입이 보낸 인증 메일").isEmpty();
    }

    @Test
    @DisplayName("토큰이 없으면 400 VALIDATION_FAILED로 응답한다")
    void rejectsMissingToken() throws Exception {
        signup(
                        """
                        {"password":"%s","nickname":"%s","birthDate":"%s","gender":"%s"}
                        """
                                .formatted(
                                        VALID_PASSWORD,
                                        newAccount().nickname(),
                                        VALID_BIRTH_DATE,
                                        VALID_GENDER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(
                        jsonPath("$.details.errors[?(@.field == 'emailVerificationToken')]")
                                .exists());
    }

    @Test
    @DisplayName("위조된 토큰은 401 EMAIL_VERIFICATION_TOKEN_INVALID로 응답한다")
    void rejectsForgedToken() throws Exception {
        signup(signupBodyWithToken(FORGED_TOKEN, VALID_PASSWORD, newAccount().nickname()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_TOKEN_INVALID"));
    }

    /** 서명과 만료만으로는 재사용을 막을 수 없다. 같은 토큰으로 두 계정이 생기면 한 번의 인증으로 이메일을 여러 번 쓸 수 있다. */
    @Test
    @DisplayName("같은 토큰으로 두 번 가입할 수 없다")
    void rejectsReusedToken() throws Exception {
        TestAccount account = newAccount();
        String token = issueEmailVerificationToken(account.email());

        signup(signupBodyWithToken(token, account.password(), account.nickname()))
                .andExpect(status().isCreated());

        signup(signupBodyWithToken(token, account.password(), newAccount().nickname()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_TOKEN_INVALID"));

        assertThat(
                        jdbcTemplate.queryForObject(
                                "select count(*) from users where email = ?",
                                Integer.class,
                                account.email()))
                .as("같은 이메일의 계정 수")
                .isEqualTo(1);
    }

    /** 한 종류가 다른 자리에서 통하면 소셜 가입 토큰으로 이메일 계정을 만들 수 있다(API-001 처리 규칙). */
    @Test
    @DisplayName("소셜 가입 토큰은 이메일 인증 토큰 자리에 쓸 수 없다")
    void rejectsSignupTokenInEmailSlot() throws Exception {
        String socialSignupToken =
                tokenProvider.createSignupToken(
                        new TokenProvider.SignupTokenClaims(
                                "GOOGLE", "provider-user-603", newAccount().email(), "소셜사용자"));

        signup(signupBodyWithToken(socialSignupToken, VALID_PASSWORD, newAccount().nickname()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_TOKEN_INVALID"));
    }

    /** 본문 값을 믿으면 남의 이메일로 가입할 수 있다. 계약에 없는 필드는 무시되고 계정은 토큰의 이메일로 만들어져야 한다. */
    @Test
    @DisplayName("요청 본문의 email은 무시하고 토큰의 이메일로 계정을 만든다")
    void ignoresEmailInRequestBody() throws Exception {
        TestAccount account = newAccount();
        String token = issueEmailVerificationToken(account.email());
        String victimEmail = newAccount().email();

        MvcResult result =
                signup(
                                """
                                {"emailVerificationToken":"%s","email":"%s","password":"%s",\
                                "nickname":"%s","birthDate":"%s","gender":"%s"}
                                """
                                        .formatted(
                                                token,
                                                victimEmail,
                                                account.password(),
                                                account.nickname(),
                                                VALID_BIRTH_DATE,
                                                VALID_GENDER))
                        .andExpect(status().isCreated())
                        .andReturn();

        assertThat(
                        jdbcTemplate.queryForObject(
                                "select email from users where id = ?",
                                String.class,
                                signupUserId(result)))
                .isEqualTo(account.email());
    }

    /**
     * 토큰은 인증번호 발송(API-078)이 예약 도메인을 이미 거른 이메일에만 발급되지만, 계약(API-001 처리 규칙)이 이 경로도 지목한다. 발송 경로가 바뀌어도
     * D-9 방어가 한 겹으로 줄지 않도록 직접 만든 토큰으로 두 번째 겹을 확인한다(리뷰 A-F6 · B-5).
     */
    @Test
    @DisplayName("예약 도메인 이메일의 토큰으로는 가입할 수 없다")
    void rejectsReservedEmailToken() throws Exception {
        String token =
                tokenProvider.createEmailVerificationToken(
                        new TokenProvider.EmailVerificationTokenClaims(
                                "withdrawn-1@feelm.invalid",
                                java.util.UUID.randomUUID().toString()));

        signup(signupBodyWithToken(token, VALID_PASSWORD, newAccount().nickname()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("대소문자만 다른 닉네임은 409 NICKNAME_DUPLICATED로 응답한다")
    void duplicatedNicknameIgnoringCaseReturnsConflict() throws Exception {
        TestAccount account = newAccount();
        signupActive(account);

        signup(
                        signupBody(
                                newAccount().email(),
                                account.password(),
                                account.nickname().toUpperCase()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NICKNAME_DUPLICATED"));
    }

    /** 사용자가 곧바로 고칠 수 있는 실패가 토큰을 태우면 인증번호부터 다시 받아야 한다(플랜 결정 4). */
    @Test
    @DisplayName("닉네임 중복으로 거절된 요청은 토큰을 소진하지 않는다")
    void duplicatedNicknameKeepsToken() throws Exception {
        TestAccount taken = newAccount();
        signupActive(taken);
        TestAccount account = newAccount();
        String token = issueEmailVerificationToken(account.email());

        signup(signupBodyWithToken(token, account.password(), taken.nickname()))
                .andExpect(status().isConflict());

        signup(signupBodyWithToken(token, account.password(), account.nickname()))
                .andExpect(status().isCreated());
    }

    /** 토큰을 발급한 뒤 같은 이메일이 선점된 경합에서만 나오는 응답이다(API-001 오류 응답). */
    @Test
    @DisplayName("토큰 발급 뒤 같은 이메일이 선점되면 409 EMAIL_DUPLICATED로 응답한다")
    void duplicatedEmailReturnsConflict() throws Exception {
        TestAccount account = newAccount();
        String token = issueEmailVerificationToken(account.email());
        signupActive(account);

        signup(signupBodyWithToken(token, account.password(), newAccount().nickname()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_DUPLICATED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("이메일은 소문자로 정규화된다")
    void emailIsNormalizedToLowerCase() throws Exception {
        TestAccount account = newAccount();

        MvcResult result =
                signup(
                                signupBody(
                                        account.email().toUpperCase(),
                                        account.password(),
                                        account.nickname()))
                        .andExpect(status().isCreated())
                        .andReturn();

        assertThat(
                        jdbcTemplate.queryForObject(
                                "select email from users where id = ?",
                                String.class,
                                signupUserId(result)))
                .isEqualTo(account.email());
    }

    @Test
    @DisplayName("가입 트랜잭션에서 알림 설정 기본행을 함께 만든다")
    void signupCreatesDefaultNotificationSettings() throws Exception {
        long userId = signupActive(newAccount());

        Map<String, Object> settings = notificationSettings(userId);
        assertThat(settings.get("rating_enabled")).isEqualTo(true);
        assertThat(settings.get("recommendation_enabled")).isEqualTo(true);
        assertThat(settings.get("marketing_enabled")).isEqualTo(false);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPasswords")
    @DisplayName("비밀번호 정책 위반은 400 VALIDATION_FAILED로 응답한다")
    void invalidPasswordReturnsValidationFailed(String reason, String password) throws Exception {
        signup(signupBodyWithToken(FORGED_TOKEN, password, newAccount().nickname()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.errors[?(@.field == 'password')]").exists());
    }

    static Stream<Arguments> invalidPasswords() {
        return Stream.of(
                Arguments.of("8자 미만", "P@ss1w"),
                Arguments.of("64자 초과", "P@ss1" + "a".repeat(60)),
                Arguments.of("숫자 없음", "Password!"),
                Arguments.of("영문 없음", "1234567!@"),
                Arguments.of("특수문자 없음", "Password1"),
                Arguments.of("공백 포함", "P@ss w0rd"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidNicknames")
    @DisplayName("닉네임 규칙 위반은 400 VALIDATION_FAILED로 응답한다")
    void invalidNicknameReturnsValidationFailed(String reason, String nickname) throws Exception {
        signup(signupBodyWithToken(FORGED_TOKEN, VALID_PASSWORD, nickname))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.errors[?(@.field == 'nickname')]").exists());
    }

    static Stream<Arguments> invalidNicknames() {
        return Stream.of(
                Arguments.of("2자 미만", "a"),
                Arguments.of("10자 초과", "abcdefghijk"),
                Arguments.of("공백 포함", "user name"),
                Arguments.of("허용하지 않는 특수문자", "user!"),
                Arguments.of("허용하지 않는 하이픈", "user-name"));
    }

    @Test
    @DisplayName("gender가 없으면 400 VALIDATION_FAILED로 응답한다")
    void missingGenderReturnsValidationFailed() throws Exception {
        signup(
                        """
                        {"emailVerificationToken":"%s","password":"%s","nickname":"%s",\
                        "birthDate":"%s"}
                        """
                                .formatted(
                                        FORGED_TOKEN,
                                        VALID_PASSWORD,
                                        newAccount().nickname(),
                                        VALID_BIRTH_DATE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.errors[?(@.field == 'gender')]").exists());
    }

    @Test
    @DisplayName("birthDate가 없으면 400 VALIDATION_FAILED로 응답한다")
    void missingBirthDateReturnsValidationFailed() throws Exception {
        signup(
                        """
                        {"emailVerificationToken":"%s","password":"%s","nickname":"%s",\
                        "gender":"%s"}
                        """
                                .formatted(
                                        FORGED_TOKEN,
                                        VALID_PASSWORD,
                                        newAccount().nickname(),
                                        VALID_GENDER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.errors[?(@.field == 'birthDate')]").exists());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedRequests")
    @DisplayName("형식이 어긋난 요청은 400 VALIDATION_FAILED로 응답한다")
    void malformedRequestReturnsValidationFailed(String reason, String body) throws Exception {
        signup(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    static Stream<Arguments> malformedRequests() {
        return Stream.of(
                Arguments.of(
                        "허용하지 않는 gender 값",
                        """
                        {"emailVerificationToken":"%s","password":"%s","nickname":"userdef",\
                        "birthDate":"%s","gender":"UNKNOWN"}
                        """
                                .formatted(FORGED_TOKEN, VALID_PASSWORD, VALID_BIRTH_DATE)),
                Arguments.of(
                        "birthDate 형식 아님",
                        """
                        {"emailVerificationToken":"%s","password":"%s","nickname":"userghi",\
                        "birthDate":"1999/03/14","gender":"%s"}
                        """
                                .formatted(FORGED_TOKEN, VALID_PASSWORD, VALID_GENDER)));
    }

    /**
     * 개정 이전 흐름이 남긴 미인증 계정은 보존 기간이 지났으면 그 이메일을 붙들지 않는다(플랜 결정 3). 잔존 행의 일괄 정리와 이 분기 제거는
     * S15P21E106-604가 한다.
     */
    @Test
    @DisplayName("미인증 상태로 24시간이 지난 계정의 이메일은 재가입할 수 있다")
    void stalePendingAccountIsReplaced() throws Exception {
        TestAccount account = newAccount();
        long previousUserId = signupPending(account);
        ageUser(previousUserId, Duration.ofHours(25));

        MvcResult result =
                signup(signupBody(account.email(), account.password(), newAccount().nickname()))
                        .andExpect(status().isCreated())
                        .andReturn();

        assertThat(signupUserId(result)).isNotEqualTo(previousUserId);
        assertThat(userStatus(signupUserId(result))).isEqualTo("ACTIVE");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "select count(*) from users where email = ?",
                                Integer.class,
                                account.email()))
                .as("이전 미인증 행은 삭제된다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("미인증 상태로 24시간이 지나지 않은 계정의 이메일은 409 EMAIL_DUPLICATED다")
    void pendingAccountWithinDayReturnsConflict() throws Exception {
        TestAccount account = newAccount();
        String token = issueEmailVerificationToken(account.email());
        long userId = signupPending(account);
        ageUser(userId, Duration.ofHours(23));

        signup(signupBodyWithToken(token, account.password(), newAccount().nickname()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_DUPLICATED"));
    }

    @Test
    @DisplayName("인증을 마친 계정은 24시간이 지나도 재가입할 수 없다")
    void activeAccountIsNeverReplacedBySignup() throws Exception {
        TestAccount account = newAccount();
        String token = issueEmailVerificationToken(account.email());
        long userId = signupActive(account);
        ageUser(userId, Duration.ofHours(25));

        signup(signupBodyWithToken(token, account.password(), newAccount().nickname()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_DUPLICATED"));

        assertThat(userStatus(userId)).isEqualTo("ACTIVE");
    }
}
