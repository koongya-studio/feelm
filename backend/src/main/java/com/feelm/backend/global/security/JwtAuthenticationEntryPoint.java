package com.feelm.backend.global.security;

import com.feelm.backend.global.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 인증이 필요한 요청이 신원 없이 도달했을 때 401을 응답한다.
 *
 * <p>{@link JwtAuthenticationFilter}가 남긴 오류 코드를 그대로 사용해 <b>만료</b>와 <b>토큰 없음·서명 불일치</b>를 구분한다. 이 구분이
 * 있어야 클라이언트가 재발급 후 재시도할지, 로그인 화면으로 보낼지 판단할 수 있다(API-000).
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityErrorResponder responder;

    public JwtAuthenticationEntryPoint(SecurityErrorResponder responder) {
        this.responder = responder;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {
        Object recorded = request.getAttribute(SecurityErrorResponder.ERROR_CODE_ATTRIBUTE);
        ErrorCode errorCode = recorded instanceof ErrorCode code ? code : ErrorCode.UNAUTHORIZED;
        responder.write(response, errorCode);
    }
}
