package com.feelm.backend.infrastructure.oauth;

import com.feelm.backend.domain.auth.entity.OAuthProvider;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 소셜 로그인 설정값이다.
 *
 * <p>{@code clientSecret}은 Secret이므로 소스에 기본값을 두지 않고 환경변수로만 주입한다({@code
 * docs/secret-environment-variables.md}).
 *
 * <p>{@code allowedRedirectUris}는 기본값이 비어 있고, 비어 있으면 모든 요청이 거절된다(D-4). 목록을 열어 두면 공격자가 자신의 주소를
 * {@code redirectUri}로 넣어 인가 코드를 자기 화면으로 돌려받을 수 있어, 값을 명시한 환경에서만 동작하게 한다.
 */
@ConfigurationProperties(prefix = "feelm.oauth")
public record OAuthProperties(
        @DefaultValue List<String> allowedRedirectUris,
        @DefaultValue Map<OAuthProvider, Registration> providers) {

    /** 제공자 앱 등록 정보. {@code clientId}가 없으면 그 제공자는 사용할 수 없다(D-5). */
    public record Registration(String clientId, String clientSecret) {

        public boolean isConfigured() {
            return clientId != null && !clientId.isBlank();
        }
    }

    /**
     * 요청의 {@code redirectUri}가 허용 목록에 있는지 확인한다.
     *
     * <p>정확히 일치하는지만 본다. 접두사 비교는 {@code https://feelm.app.evil.com}처럼 앞부분만 같은 주소를 통과시킬 수 있다.
     */
    public boolean isAllowedRedirectUri(String redirectUri) {
        return redirectUri != null && allowedRedirectUris.contains(redirectUri);
    }
}
