package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.auth.entity.OAuthProvider;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.infrastructure.oauth.OAuthUserProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

/**
 * API-009 소셜 계정 연결의 계약을 검증한다.
 *
 * <p>연결은 회원 전용이며, 한 사용자당 provider 하나와 한 소셜 계정당 사용자 하나라는 두 유일 규칙을 서로 다른 오류 코드로 구분한다.
 */
@DisplayName("소셜 계정 연결 — API-009")
class OAuthLinkContractTest extends OAuthApiTestSupport {

    /** 연결 결과를 읽는 유일한 경로인 내 프로필 조회(API-014). */
    private static final String ME_PATH = "/api/v1/users/me";

    @Test
    @DisplayName("이메일 계정에 소셜 수단을 연결하면 201과 연결 정보를 받는다")
    void linksProviderToEmailAccount(CapturedOutput output) throws Exception {
        Member member = newMember(output);
        OAuthUserProfile profile = newProfile();
        fake(OAuthProvider.GOOGLE).willReturn(profile);

        oauthLink(OAuthProvider.GOOGLE, member.accessToken(), VALID_CODE, ALLOWED_LINK_REDIRECT_URI)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.provider").value("GOOGLE"))
                // 시각은 숫자 타임스탬프가 아니라 API-000의 UTC ISO 8601 문자열이어야 한다.
                .andExpect(
                        jsonPath("$.linkedAt")
                                .value(matchesPattern("\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z")));

        assertThat(linkedProviderUserId(member.userId(), OAuthProvider.GOOGLE))
                .isEqualTo(profile.providerUserId());
    }

    @Test
    @DisplayName("이메일을 주지 않는 provider도 연결은 성공한다 (D-2)")
    void linksProviderWithoutEmail(CapturedOutput output) throws Exception {
        Member member = newMember(output);
        OAuthUserProfile profile = newProfile();
        fake(OAuthProvider.KAKAO)
                .willReturn(new OAuthUserProfile(profile.providerUserId(), null, null));

        oauthLink(OAuthProvider.KAKAO, member.accessToken(), VALID_CODE, ALLOWED_LINK_REDIRECT_URI)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.provider").value("KAKAO"));

        assertThat(linkedProviderCount(member.userId())).isEqualTo(1);
    }

    @Test
    @DisplayName("인증 없이 호출하면 401이다")
    void anonymousRequestIsRejected() throws Exception {
        postJson(OAUTH_LINK_PATH + "google", oauthCodeBody(VALID_CODE, ALLOWED_LINK_REDIRECT_URI))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("발급된 Access Token이 유효해도 탈퇴한 계정으로는 연결할 수 없다")
    void withdrawnAccountCannotLink(CapturedOutput output) throws Exception {
        Member member = newMember(output);
        withdrawUser(member.userId());

        fake(OAuthProvider.GOOGLE).willReturn(newProfile());
        oauthLink(OAuthProvider.GOOGLE, member.accessToken(), VALID_CODE, ALLOWED_LINK_REDIRECT_URI)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(linkedProviderCount(member.userId())).isZero();
    }

    @Test
    @DisplayName("같은 provider를 다시 연결하면 409 PROVIDER_ALREADY_LINKED다")
    void sameProviderCannotBeLinkedTwice(CapturedOutput output) throws Exception {
        Member member = newMember(output);

        fake(OAuthProvider.GOOGLE).willReturn(newProfile());
        oauthLink(OAuthProvider.GOOGLE, member.accessToken(), VALID_CODE, ALLOWED_LINK_REDIRECT_URI)
                .andExpect(status().isCreated());

        fake(OAuthProvider.GOOGLE).willReturn(newProfile());
        oauthLink(OAuthProvider.GOOGLE, member.accessToken(), VALID_CODE, ALLOWED_LINK_REDIRECT_URI)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROVIDER_ALREADY_LINKED"));

        assertThat(linkedProviderCount(member.userId())).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 사용자에 연결된 소셜 계정이면 409 OAUTH_ACCOUNT_ALREADY_LINKED다")
    void providerAccountLinkedToAnotherUserIsRejected(CapturedOutput output) throws Exception {
        OAuthUserProfile profile = newProfile();
        socialSignup(OAuthProvider.GOOGLE, profile);

        Member member = newMember(output);
        fake(OAuthProvider.GOOGLE).willReturn(profile);

        oauthLink(OAuthProvider.GOOGLE, member.accessToken(), VALID_CODE, ALLOWED_LINK_REDIRECT_URI)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OAUTH_ACCOUNT_ALREADY_LINKED"));

        assertThat(linkedProviderCount(member.userId())).isZero();
    }

    @Test
    @DisplayName("provider 응답 오류는 502 OAUTH_PROVIDER_ERROR이고 연결은 남지 않는다")
    void providerFailureReturnsBadGateway(CapturedOutput output) throws Exception {
        Member member = newMember(output);
        fake(OAuthProvider.GOOGLE).willFail(new BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR));

        oauthLink(OAuthProvider.GOOGLE, member.accessToken(), VALID_CODE, ALLOWED_LINK_REDIRECT_URI)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("OAUTH_PROVIDER_ERROR"));

        assertThat(linkedProviderCount(member.userId())).isZero();
    }

    /**
     * 연결 결과를 사용자가 확인하는 경로는 API-014 하나뿐이다. 연결과 조회가 각각 동작하는 것만으로는 계약이 이어졌다고 할 수 없어, 연결을 API로 만든 뒤 같은
     * 계정의 프로필에서 읽는다. 프로필 조회 자체의 계약은 {@code UserProfileApiContractTest}가 고정하며, 여기서는 API-009가 쓴 연결이 그
     * 응답에 그대로 실리는지만 본다.
     */
    @Test
    @DisplayName("연결한 provider가 내 프로필의 linkedProviders에 이름순으로 나타난다")
    void linkedProvidersAppearInProfile(CapturedOutput output) throws Exception {
        createActivityCountTables();
        Member member = newMember(output);

        fake(OAuthProvider.KAKAO).willReturn(newProfile());
        oauthLink(OAuthProvider.KAKAO, member.accessToken(), VALID_CODE, ALLOWED_LINK_REDIRECT_URI)
                .andExpect(status().isCreated());

        fake(OAuthProvider.GOOGLE).willReturn(newProfile());
        oauthLink(OAuthProvider.GOOGLE, member.accessToken(), VALID_CODE, ALLOWED_LINK_REDIRECT_URI)
                .andExpect(status().isCreated());

        mockMvc.perform(
                        get(ME_PATH)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + member.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkedProviders", contains("GOOGLE", "KAKAO")))
                .andExpect(jsonPath("$.hasPassword").value(true));
    }

    @Test
    @DisplayName("자격 증명이 설정되지 않은 provider는 400 UNSUPPORTED_PROVIDER다")
    void unconfiguredProviderIsRejected(CapturedOutput output) throws Exception {
        Member member = newMember(output);

        oauthLink(OAuthProvider.NAVER, member.accessToken(), VALID_CODE, ALLOWED_LINK_REDIRECT_URI)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_PROVIDER"));
    }

    @Test
    @DisplayName("허용 목록에 없는 redirectUri는 provider를 호출하지 않고 400이다 (D-4)")
    void redirectUriOutsideAllowListIsRejected(CapturedOutput output) throws Exception {
        Member member = newMember(output);
        fake(OAuthProvider.GOOGLE).willReturn(newProfile());

        oauthLink(
                        OAuthProvider.GOOGLE,
                        member.accessToken(),
                        VALID_CODE,
                        "https://evil.example.com/link")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(fake(OAuthProvider.GOOGLE).calls()).isEmpty();
    }

    /**
     * 프로필 요약이 집계하는 콘텐츠·감상 테이블을 빈 테이블로 만든다.
     *
     * <p>네 테이블은 Entity가 없어 이 골격의 H2 스키마(Entity 기준 생성)에 없다. 운영에서는 Flyway가 만드는 테이블이라 애플리케이션 결함이 아니지만,
     * 없으면 프로필 조회가 집계 질의에서 500이 된다. 계정 API 골격 전체를 넓히지 않도록 이 테스트에서만 만든다. 컬럼은 집계 질의가 읽는 것만 두며, 정의는
     * {@code UserProfileApiTestSupport}와 맞춘다.
     */
    private void createActivityCountTables() {
        jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS popcorn_flavors (
                    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    code varchar(30) NOT NULL
                )
                """);
        jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS movies (
                    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    title varchar(255) NOT NULL,
                    representative_flavor_id bigint
                )
                """);
        jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS ratings (
                    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    user_id bigint NOT NULL,
                    movie_id bigint NOT NULL,
                    score numeric(2, 1) NOT NULL,
                    created_at timestamp with time zone NOT NULL
                )
                """);
        jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS frames (
                    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    rating_id bigint NOT NULL,
                    user_id bigint NOT NULL,
                    movie_id bigint NOT NULL,
                    created_at timestamp with time zone NOT NULL
                )
                """);
    }

    /** 연결 대상이 되는 활성 이메일 계정과 그 계정의 Access Token. */
    private Member newMember(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        long userId = signupActive(account, output);
        MvcResult result =
                login(account.email(), account.password()).andExpect(status().isOk()).andReturn();
        return new Member(userId, stringField(result, "accessToken"));
    }

    private record Member(long userId, String accessToken) {}
}
