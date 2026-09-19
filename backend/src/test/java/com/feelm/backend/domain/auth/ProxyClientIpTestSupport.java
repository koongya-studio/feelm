package com.feelm.backend.domain.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;

/**
 * 프록시 신뢰 경계(S15P21E106-288) 검증의 공통 골격이다.
 *
 * <p>MockMvc는 서블릿 컨테이너를 거치지 않아 {@code RemoteIpValve}를 지나지 않는다. 그래서 실제 포트로 요청을 보낸다. 대상 주소는 {@code
 * localhost}가 아니라 {@code 127.0.0.1}로 고정한다. 이름으로 붙으면 환경에 따라 발신지가 {@code ::1}이 되어, 어떤 발신지를 신뢰 목록에
 * 넣었는지에 따라 결과가 흔들린다.
 *
 * <p>요청은 JDK {@link HttpClient}로 보낸다. 검증 대상이 헤더와 발신지의 조합이라 요청을 가공하지 않는 최소 클라이언트가 맞고, 테스트 지원 클래스의
 * 패키지 이동에도 영향을 받지 않는다.
 */
abstract class ProxyClientIpTestSupport {

    /**
     * 운영 {@code application.yml}과 같은 신뢰 목록. 값을 그대로 쓰는 이유는 표기가 이 서버·Boot 버전에서 실제로 받아들여지는지 함께 확인하기
     * 위해서다. 운영 파일의 값 자체는 {@code ClientIpPropertiesTest}가 파일을 읽어 고정한다(리뷰 A-6·B-1).
     */
    static final String INTERNAL_PROXIES = "127.0.0.0/8,::1/128,172.16.0.0/12";

    static final String VERIFICATION_CODE_PATH = "/api/v1/auth/email/verification-code";

    /** 애플리케이션이 클라이언트 IP로 읽는 헤더(리뷰 A-7). Nginx가 {@code $remote_addr}로 덮어써 보낸다. */
    static final String REAL_IP = "X-Real-IP";

    static final String FORWARDED_FOR = "X-Forwarded-For";
    static final String FORWARDED_HOST = "X-Forwarded-Host";

    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${local.server.port}")
    private int port;

    /** 인증번호 발송(API-078)을 보낸다. 계정이 없어도 202이며 제한 카운터는 시도 시점에 올라간다(결정 D-4). */
    HttpResponse<Void> sendVerificationCode(String realIp, String email) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(uri(VERIFICATION_CODE_PATH))
                        .header("Content-Type", "application/json")
                        .header(REAL_IP, realIp)
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"email\":\"%s\"}".formatted(email)))
                        .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    }

    /** 애플리케이션이 본 클라이언트 IP를 돌려준다. {@code realIp}가 {@code null}이면 헤더를 붙이지 않는다. */
    HttpResponse<String> clientIp(String realIp) throws Exception {
        return realIp == null
                ? get(ClientIpEchoController.PATH)
                : get(ClientIpEchoController.PATH, REAL_IP, realIp);
    }

    /** 지정한 헤더만 붙여 클라이언트 IP를 물어본다. 읽지 않는 헤더가 무시되는지 보는 데 쓴다. */
    HttpResponse<String> clientIpWith(String headerName, String headerValue) throws Exception {
        return get(ClientIpEchoController.PATH, headerName, headerValue);
    }

    /** 애플리케이션이 본 서버 이름을 돌려준다. 밸브가 host 헤더를 신뢰하는지 보는 데 쓴다. */
    HttpResponse<String> serverName(String forwardedHost) throws Exception {
        return forwardedHost == null
                ? get(ClientIpEchoController.SERVER_NAME_PATH)
                : get(ClientIpEchoController.SERVER_NAME_PATH, FORWARDED_HOST, forwardedHost);
    }

    private HttpResponse<String> get(String path, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path));
        for (int index = 0; index < headers.length; index += 2) {
            request.header(headers[index], headers[index + 1]);
        }
        return httpClient.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
