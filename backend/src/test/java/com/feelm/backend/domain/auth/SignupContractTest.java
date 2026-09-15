package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.test.web.servlet.MvcResult;

/**
 * API-001 이메일 회원가입의 계약을 검증한다.
 *
 * <p>비밀번호·닉네임 규칙은 명세의 '가정' 값이 아니라 플랜 6장의 결정 1·2를, 미인증 재가입은 결정 3을, 알림 설정 기본행은 결정 10을, {@code gender}
 * 필수는 결정 11을 기준으로 한다.
 */
@DisplayName("회원가입 — API-001")
class SignupContractTest extends EmailAuthApiTestSupport {

    @Test
    @DisplayName("가입에 성공하면 201과 함께 PENDING_VERIFICATION 계정을 반환한다")
    void validSignupReturnsCreatedPendingAccount() throws Exception {
        TestAccount account = newAccount();

        MvcResult result =
                signup(signupBody(account))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.userId").isNumber())
                        .andExpect(jsonPath("$.email").value(account.email()))
                        .andExpect(jsonPath("$.nickname").value(account.nickname()))
                        .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
                        .andReturn();

        assertThat(userStatus(longField(result, "userId"))).isEqualTo("PENDING_VERIFICATION");
    }

    @Test
    @DisplayName("이메일은 소문자로 정규화되며 대소문자가 다른 같은 이메일은 중복으로 본다")
    void emailIsNormalizedToLowerCase() throws Exception {
        TestAccount account = newAccount();
        String upperCasedEmail = account.email().toUpperCase();

        signup(signupBody(upperCasedEmail, account.password(), account.nickname()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(account.email()));

        signup(signupBody(account.email(), account.password(), newAccount().nickname()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_DUPLICATED"));
    }

    @Test
    @DisplayName("이미 가입된 이메일은 409 EMAIL_DUPLICATED로 응답한다")
    void duplicatedEmailReturnsConflict() throws Exception {
        TestAccount account = newAccount();
        signupPending(account);

        signup(signupBody(account.email(), account.password(), newAccount().nickname()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_DUPLICATED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("대소문자만 다른 닉네임은 409 NICKNAME_DUPLICATED로 응답한다")
    void duplicatedNicknameIgnoringCaseReturnsConflict() throws Exception {
        TestAccount account = newAccount();
        signupPending(account);

        signup(
                        signupBody(
                                newAccount().email(),
                                account.password(),
                                account.nickname().toUpperCase()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NICKNAME_DUPLICATED"));
    }

    @Test
    @DisplayName("가입 성공 시 인증 메일을 한 번만 발송한다")
    void signupSendsVerificationMailOnce(CapturedOutput output) throws Exception {
        signupPending(newAccount());

        assertThat(verificationTokens(output)).hasSize(1);
    }

    @Test
    @DisplayName("가입 트랜잭션에서 알림 설정 기본행을 함께 만든다")
    void signupCreatesDefaultNotificationSettings() throws Exception {
        long userId = signupPending(newAccount());

        Map<String, Object> settings = notificationSettings(userId);
        assertThat(settings.get("rating_enabled")).isEqualTo(true);
        assertThat(settings.get("recommendation_enabled")).isEqualTo(true);
        assertThat(settings.get("marketing_enabled")).isEqualTo(false);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPasswords")
    @DisplayName("비밀번호 정책 위반은 400 VALIDATION_FAILED로 응답한다")
    void invalidPasswordReturnsValidationFailed(String reason, String password) throws Exception {
        TestAccount account = newAccount();

        signup(signupBody(account.email(), password, account.nickname()))
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
        TestAccount account = newAccount();

        signup(signupBody(account.email(), account.password(), nickname))
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
        TestAccount account = newAccount();

        signup(
                        """
                        {"email":"%s","password":"%s","nickname":"%s","birthDate":"%s"}
                        """
                                .formatted(
                                        account.email(),
                                        account.password(),
                                        account.nickname(),
                                        VALID_BIRTH_DATE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.errors[?(@.field == 'gender')]").exists());
    }

    @Test
    @DisplayName("birthDate가 없으면 400 VALIDATION_FAILED로 응답한다")
    void missingBirthDateReturnsValidationFailed() throws Exception {
        TestAccount account = newAccount();

        signup(
                        """
                        {"email":"%s","password":"%s","nickname":"%s","gender":"%s"}
                        """
                                .formatted(
                                        account.email(),
                                        account.password(),
                                        account.nickname(),
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
                Arguments.of("이메일 형식 아님", signupBody("not-an-email", VALID_PASSWORD, "userabc")),
                Arguments.of(
                        "허용하지 않는 gender 값",
                        """
                        {"email":"gender@example.com","password":"%s","nickname":"userdef",\
                        "birthDate":"%s","gender":"UNKNOWN"}
                        """
                                .formatted(VALID_PASSWORD, VALID_BIRTH_DATE)),
                Arguments.of(
                        "birthDate 형식 아님",
                        """
                        {"email":"birth@example.com","password":"%s","nickname":"userghi",\
                        "birthDate":"1999/03/14","gender":"%s"}
                        """
                                .formatted(VALID_PASSWORD, VALID_GENDER)));
    }

    @Test
    @DisplayName("미인증 상태로 24시간이 지난 계정의 이메일은 재가입할 수 있다")
    void pendingAccountOlderThanDayAllowsSignupAgain() throws Exception {
        TestAccount account = newAccount();
        long previousUserId = signupPending(account);
        ageUser(previousUserId, Duration.ofHours(25));

        MvcResult result =
                signup(signupBody(account.email(), account.password(), newAccount().nickname()))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
                        .andReturn();

        assertThat(longField(result, "userId")).isNotEqualTo(previousUserId);
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
        ageUser(signupPending(account), Duration.ofHours(23));

        signup(signupBody(account.email(), account.password(), newAccount().nickname()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_DUPLICATED"));
    }

    @Test
    @DisplayName("인증을 마친 계정은 24시간이 지나도 재가입할 수 없다")
    void activeAccountIsNeverReplacedBySignup(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        long userId = signupActive(account, output);
        ageUser(userId, Duration.ofHours(25));

        signup(signupBody(account.email(), account.password(), newAccount().nickname()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_DUPLICATED"));

        assertThat(userStatus(userId)).isEqualTo("ACTIVE");
    }
}
