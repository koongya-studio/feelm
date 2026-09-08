package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.auth.entity.OAuthProvider;
import com.feelm.backend.infrastructure.oauth.OAuthUserProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.test.web.servlet.MvcResult;

/**
 * API-009 소셜 계정 연결의 계약을 검증한다.
 *
 * <p>연결은 회원 전용이며, 한 사용자당 provider 하나와 한 소셜 계정당 사용자 하나라는 두 유일 규칙을 서로 다른 오류 코드로 구분한다.
 */
@DisplayName("소셜 계정 연결 — API-009")
class OAuthLinkContractTest extends OAuthApiTestSupport {

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
