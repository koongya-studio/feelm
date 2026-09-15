package com.feelm.backend.domain.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 애플리케이션이 보는 클라이언트 IP와 서버 이름을 그대로 돌려주는 검증 전용 컨트롤러. 테스트 소스에만 존재하며 운영 코드에 포함되지 않는다.
 *
 * <p>프록시 신뢰 경계(S15P21E106-288)는 서블릿 컨테이너의 밸브가 적용하므로 MockMvc로는 확인할 수 없다. 실제 포트로 요청을 보내 {@code
 * getRemoteAddr()}가 무엇을 돌려주는지 직접 본다. 서버 이름을 함께 돌려주는 것은 밸브가 host 헤더까지 신뢰하지 않는지 보기 위해서다(리뷰 A-8·B-5).
 * 이 경로들은 인증 대상이라 {@link ClientIpEchoSecurityConfig}를 함께 등록해야 호출할 수 있다.
 */
@RestController
class ClientIpEchoController {

    static final String PATH = "/test/client-ip";
    static final String SERVER_NAME_PATH = "/test/client-ip/server-name";

    @GetMapping(PATH)
    String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    @GetMapping(SERVER_NAME_PATH)
    String serverName(HttpServletRequest request) {
        return request.getServerName();
    }
}
