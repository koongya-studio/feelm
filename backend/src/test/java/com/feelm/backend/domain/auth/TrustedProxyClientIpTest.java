package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.redis.testcontainers.RedisContainer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 신뢰 경계 안의 프록시가 보낸 {@code X-Real-IP}가 클라이언트 IP가 되는지 확인한다(S15P21E106-288).
 *
 * <p>발신지 {@code 127.0.0.1}은 설정한 신뢰 목록에 들어 있으므로 프록시로 취급된다. 배포 구성에서 이 자리에 오는 것은 Nginx 컨테이너다.
 *
 * <p>읽는 헤더가 {@code X-Forwarded-For}가 아니라 {@code X-Real-IP}인 이유는 Nginx가 전자는 이어 붙이고 후자는 {@code
 * $remote_addr}로 덮어쓰기 때문이다(리뷰 A-7·B-3). 이어 붙인 목록은 항목이 모두 신뢰 대역일 때 맨 왼쪽 값이 채택되고, backend 컨테이너는 호스트
 * 포트를 공개하지 않아 Tomcat에 닿는 모든 발신지가 신뢰 대역이다.
 *
 * <p>Redis는 다른 계약 테스트가 쓰는 컨테이너를 그대로 쓴다. 컨테이너를 하나 더 띄우면 JVM 수명 동안 같은 이미지를 두 번 올리게 된다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.forward-headers-strategy=native",
            "server.tomcat.remoteip.remote-ip-header=x-real-ip",
            "server.tomcat.remoteip.protocol-header=x-forwarded-proto",
            "server.tomcat.remoteip.host-header=",
            "server.tomcat.remoteip.port-header=",
            "server.tomcat.remoteip.internal-proxies=" + ProxyClientIpTestSupport.INTERNAL_PROXIES
        })
@Import(ClientIpEchoSecurityConfig.class)
@DisplayName("신뢰하는 프록시의 X-Real-IP — S15P21E106-288")
class TrustedProxyClientIpTest extends ProxyClientIpTestSupport {

    @ServiceConnection static RedisContainer redis = EmailAuthApiTestSupport.redis;

    /** 테스트 설정의 IP 창(1시간)이 키에 들어간다. */
    private static final Duration IP_WINDOW = Duration.ofHours(1);

    private static final String CLIENT = "203.0.113.7";

    /** 기대 키의 해시 입력을 리터럴로 고정한다. 운영 헬퍼로 계산하면 버킷 산출이 통째로 틀려도 통과한다(리뷰 B-8). */
    private static final String CLIENT_BUCKET = "203.0.113.7/32";

    private static final String PROXY = "127.0.0.1";
    private static final String PROXY_BUCKET = "127.0.0.1/32";

    /** 같은 JVM에서 재실행해도 이메일 간격 키에 걸리지 않도록 요청마다 다른 이메일을 쓴다(리뷰 B-9). */
    private static final AtomicInteger EMAIL_SEQUENCE = new AtomicInteger();

    @Autowired private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("프록시가 전달한 주소가 클라이언트 IP가 된다")
    void realIpFromTrustedPeerBecomesClientIp() throws Exception {
        assertThat(clientIp(CLIENT).body()).isEqualTo(CLIENT);
    }

    @Test
    @DisplayName("헤더가 없으면 실제 발신지가 클라이언트 IP다")
    void requestWithoutHeaderKeepsPeerAddress() throws Exception {
        assertThat(clientIp(null).body()).isEqualTo(PROXY);
    }

    /**
     * 읽는 헤더를 {@code X-Real-IP}로 정한 결정을 고정한다. {@code X-Forwarded-For}를 읽는 구현으로 되돌아가면 Nginx의 이어 붙이기
     * 구성에서 내부 대역 주체가 예산 주체를 바꿀 수 있다(리뷰 B-3).
     */
    @Test
    @DisplayName("X-Forwarded-For는 읽지 않는다")
    void forwardedForIsNotRead() throws Exception {
        assertThat(clientIpWith(FORWARDED_FOR, "203.0.113.9").body()).isEqualTo(PROXY);
        assertThat(clientIpWith(FORWARDED_FOR, "172.16.9.9").body()).isEqualTo(PROXY);
    }

    /**
     * Nginx가 {@code $remote_addr}로 덮어쓰므로 배포에서 항목은 하나다. 그래도 목록이 들어오면 밸브가 오른쪽부터 신뢰 대역을 걷어내며 처음 만나는
     * 비신뢰 주소를 쓰는지 확인한다.
     */
    @Test
    @DisplayName("목록이 오면 오른쪽의 비신뢰 주소를 쓴다")
    void rightmostUntrustedEntryWins() throws Exception {
        assertThat(clientIp("203.0.113.9, 198.51.100.5").body()).isEqualTo("198.51.100.5");
    }

    /**
     * {@code native}는 XFF만 켜지 않는다. Boot가 밸브에 host·port 헤더 기본값을 넣고 밸브가 {@code serverName}을 바꾸는데,
     * Nginx는 {@code X-Forwarded-Host}를 정리하지 않으므로 클라이언트 값이 그대로 온다. 지금 소비처는 없지만 요청에서 절대 URL을 만드는 코드가
     * 생기면 조용히 결함이 되므로 껐다(리뷰 A-8·B-5).
     */
    @Test
    @DisplayName("X-Forwarded-Host로 서버 이름을 바꿀 수 없다")
    void forwardedHostDoesNotChangeServerName() throws Exception {
        assertThat(serverName("evil.example").body()).isEqualTo(serverName(null).body());
    }

    /**
     * 이 업무의 완료 조건 1을 검증한다. 해석한 주소가 실제로 IP 단위 제한의 예산 주체가 되는지, 즉 리미터까지 흐르는지 본다.
     *
     * <p>프록시 주소 키가 생기지 않는 것도 함께 본다. 그 키가 생기면 모든 사용자가 한 예산을 나눠 쓰는 상태이고, 이 업무가 고치려는 결함이다.
     */
    @Test
    @DisplayName("메일 IP 예산이 프록시 주소가 아니라 전달된 주소로 잡힌다")
    void mailIpBudgetFollowsRealIpAddress() throws Exception {
        String clientKey = mailIpKey(CLIENT_BUCKET);
        String proxyKey = mailIpKey(PROXY_BUCKET);
        String email = nextEmail();
        redisTemplate.delete(clientKey);
        redisTemplate.delete(proxyKey);

        assertThat(resend(CLIENT, email).statusCode()).isEqualTo(202);

        assertThat(redisTemplate.opsForValue().get(clientKey)).isEqualTo("1");
        assertThat(redisTemplate.hasKey(proxyKey)).isFalse();

        redisTemplate.delete(clientKey);
        redisTemplate.delete("auth:email-resend:" + Sha256Hasher.hex(email));
    }

    private static String nextEmail() {
        return "real-ip-%d-%d@example.com"
                .formatted(System.nanoTime(), EMAIL_SEQUENCE.incrementAndGet());
    }

    /** IP 단위 발송 제한 카운터 키(S15P21E106-281 결정 D-7). 해시 입력은 집계 버킷이다(S15P21E106-288). */
    private String mailIpKey(String bucket) {
        return "auth:mail:ip:" + Sha256Hasher.hex(bucket) + ":" + IP_WINDOW.toSeconds();
    }
}
