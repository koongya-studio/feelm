package com.feelm.backend.domain.auth.service;

import com.feelm.backend.domain.auth.RefreshTokenService;
import com.feelm.backend.domain.auth.entity.OAuthProvider;
import com.feelm.backend.domain.auth.entity.UserAuthProvider;
import com.feelm.backend.domain.auth.repository.UserAuthProviderRepository;
import com.feelm.backend.domain.user.NicknamePolicy;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.infrastructure.oauth.OAuthUserProfile;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * API-007 소셜 로그인.
 *
 * <p>연결된 사용자가 있으면 로그인 처리하고, 없으면 추가 정보를 받기 위한 가입 토큰을 발급한다. 소셜 계정의 이메일이 기존 이메일 계정과 같아도 여기서 통합하지 않는다.
 * 통합 여부는 가입을 마치는 API-008이 판단한다(D-1).
 *
 * <p>제공자 호출이 포함되므로 트랜잭션을 열지 않는다(컨벤션 8장). 조회는 각각 단일 질의이고, 로그인 발급은 Redis 쓰기라 DB 트랜잭션과 무관하다.
 */
@Service
@RequiredArgsConstructor
public class OAuthLoginService {

    private static final Pattern NICKNAME = Pattern.compile(NicknamePolicy.PATTERN);

    /** 추천 닉네임을 만들 수 없을 때 붙이는 임의 숫자의 범위. 제공자 이름과 합쳐도 10자를 넘지 않는다. */
    private static final int FALLBACK_SUFFIX_BOUND = 1000;

    private final OAuthProfileLoader profileLoader;
    private final UserAuthProviderRepository authProviderRepository;
    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final TokenProvider tokenProvider;

    private final SecureRandom random = new SecureRandom();

    public OAuthLoginResult login(OAuthProvider provider, String code, String redirectUri) {
        OAuthUserProfile profile = profileLoader.load(provider, code, redirectUri);

        Optional<UserAuthProvider> link =
                authProviderRepository.findByProviderAndProviderUserId(
                        provider, profile.providerUserId());
        if (link.isPresent()) {
            return OAuthLoginResult.loggedIn(issueLogin(link.get().getUserId()));
        }

        String suggestedNickname = suggestNickname(provider, profile.nickname());
        return OAuthLoginResult.signupRequired(
                issueSignupToken(provider, profile, suggestedNickname), suggestedNickname);
    }

    /**
     * 연결된 사용자로 토큰 한 쌍을 발급한다.
     *
     * <p>탈퇴했거나 아직 활성이 아닌 계정은 이메일 로그인과 같은 {@code INVALID_CREDENTIALS}로 응답한다. 어떤 계정이 어떤 상태인지 소셜 로그인
     * 경로로 알아낼 수 있으면 안 되기 때문이다.
     */
    private LoginService.LoginResult issueLogin(Long userId) {
        User user =
                userRepository
                        .findById(userId)
                        .filter(User::isActive)
                        .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        RefreshTokenService.IssuedTokens tokens = refreshTokenService.issue(user.getId());
        return LoginService.LoginResult.of(tokens, user);
    }

    /**
     * 가입을 마칠 때까지만 유효한 토큰을 발급한다.
     *
     * <p>제공자 식별자와 이메일을 토큰에 담아 서명한다. 클라이언트가 다시 보내는 값을 믿지 않기 위해서다. 이메일이 없으면 계정을 만들 수 없으므로 이 시점에
     * 거절한다(D-2). 연결(API-009)은 이메일이 필요 없어 이 검사를 거치지 않는다.
     */
    private String issueSignupToken(
            OAuthProvider provider, OAuthUserProfile profile, String suggestedNickname) {
        if (!profile.hasEmail()) {
            throw new BusinessException(ErrorCode.OAUTH_EMAIL_REQUIRED);
        }
        return tokenProvider.createSignupToken(
                new TokenProvider.SignupTokenClaims(
                        provider.name(),
                        profile.providerUserId(),
                        profile.email().trim().toLowerCase(Locale.ROOT),
                        suggestedNickname));
    }

    /**
     * 화면이 그대로 입력란에 채울 수 있는 닉네임을 고른다.
     *
     * <p>제공자 닉네임은 길이·문자 제한을 지키지 않을 수 있으므로, 규칙을 만족할 때만 쓰고 그렇지 않으면 제공자 이름과 임의 숫자로 만든다. 중복 여부는 가입
     * (API-008)에서 판단하므로 여기서 보지 않는다.
     */
    private String suggestNickname(OAuthProvider provider, String providerNickname) {
        if (providerNickname != null && NICKNAME.matcher(providerNickname.trim()).matches()) {
            return providerNickname.trim();
        }
        return provider.name().toLowerCase(Locale.ROOT)
                + "_"
                + String.format("%03d", random.nextInt(FALLBACK_SUFFIX_BOUND));
    }
}
