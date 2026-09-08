package com.feelm.backend.infrastructure.oauth;

import com.feelm.backend.domain.auth.entity.OAuthProvider;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 테스트에서 사용할 provider별 {@link FakeOAuthClient} 모음이다.
 *
 * <p>GOOGLE·KAKAO만 등록하고 NAVER는 비워 둔다. 자격 증명이 설정되지 않은 provider가 기동 실패 대신 {@code
 * UNSUPPORTED_PROVIDER}로 응답한다는 결정(D-5)을 실제 등록 상태로 재현하기 위해서다.
 */
public class FakeOAuthClients {

    private final Map<OAuthProvider, FakeOAuthClient> fakes = new EnumMap<>(OAuthProvider.class);

    public FakeOAuthClients() {
        fakes.put(OAuthProvider.GOOGLE, new FakeOAuthClient());
        fakes.put(OAuthProvider.KAKAO, new FakeOAuthClient());
    }

    /** 등록되지 않은 provider를 요구하면 테스트 설정 실수이므로 즉시 실패시킨다. */
    public FakeOAuthClient of(OAuthProvider provider) {
        FakeOAuthClient fake = fakes.get(provider);
        if (fake == null) {
            throw new IllegalArgumentException("테스트에 등록되지 않은 provider입니다: " + provider);
        }
        return fake;
    }

    /** 컨텍스트를 공유하는 테스트 사이에 응답 설정과 호출 기록이 남지 않게 한다. */
    public void reset() {
        fakes.values().forEach(FakeOAuthClient::reset);
    }

    /** 운영 구현 대신 주입할 레지스트리. */
    public OAuthClients registry() {
        return new OAuthClients(new EnumMap<OAuthProvider, OAuthClient>(fakes));
    }

    @TestConfiguration
    public static class Registrar {

        @Bean
        FakeOAuthClients fakeOAuthClients() {
            return new FakeOAuthClients();
        }

        @Bean
        @Primary
        OAuthClients fakeOAuthClientRegistry(FakeOAuthClients fakeOAuthClients) {
            return fakeOAuthClients.registry();
        }
    }
}
