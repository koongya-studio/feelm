package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.support.SharedTestRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 신뢰 목록 밖에서 직접 온 요청의 전달 헤더가 무시되는지 확인한다(S15P21E106-288).
 *
 * <p>신뢰 목록을 발신지가 들지 않는 대역({@code 203.0.113.0/24})으로 좁혀, 프록시를 지나지 않고 애플리케이션 포트로 직접 붙은 클라이언트를 재현한다. 이
 * 경우 헤더 한 줄로 IP 단위 제한의 예산 주체를 바꿀 수 있으면 제한이 무의미해진다(결정 D-9).
 *
 * <p>현재 배포에서는 backend 컨테이너가 호스트 포트를 공개하지 않아 이 분기가 발생하지 않는다. 그래도 유지하는 이유는 이 클래스가 단독으로는 아무것도 증명하지 못하는
 * 대신 {@code TrustedProxyClientIpTest}와 짝으로 신뢰 판정이 발신지에 따라 갈리는 것을 고정하기 때문이다(리뷰 A 2장).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.forward-headers-strategy=native",
            "server.tomcat.remoteip.remote-ip-header=x-real-ip",
            "server.tomcat.remoteip.protocol-header=x-forwarded-proto",
            "server.tomcat.remoteip.host-header=",
            "server.tomcat.remoteip.port-header=",
            "server.tomcat.remoteip.internal-proxies=203.0.113.0/24"
        })
@Import(ClientIpEchoSecurityConfig.class)
@DisplayName("신뢰하지 않는 발신지의 전달 헤더 — S15P21E106-288")
@SharedTestRuntime
class UntrustedProxyClientIpTest extends ProxyClientIpTestSupport {

    private static final String PEER = "127.0.0.1";

    @Test
    @DisplayName("신뢰 목록 밖에서 온 헤더는 무시되고 실제 발신지가 남는다")
    void realIpFromUntrustedPeerIsIgnored() throws Exception {
        assertThat(clientIp("203.0.113.7").body()).isEqualTo(PEER);
    }

    @Test
    @DisplayName("헤더를 여러 개 끼워 넣어도 실제 발신지가 남는다")
    void realIpChainFromUntrustedPeerIsIgnored() throws Exception {
        assertThat(clientIp("203.0.113.7, 198.51.100.5").body()).isEqualTo(PEER);
    }

    @Test
    @DisplayName("X-Forwarded-For도 무시된다")
    void forwardedForFromUntrustedPeerIsIgnored() throws Exception {
        assertThat(clientIpWith(FORWARDED_FOR, "203.0.113.7").body()).isEqualTo(PEER);
    }
}
