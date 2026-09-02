package com.feelm.backend.global.security;

import com.feelm.backend.global.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** 인증은 됐으나 권한이 없는 요청에 공통 오류 형식으로 403을 응답한다. */
@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityErrorResponder responder;

    public JwtAccessDeniedHandler(SecurityErrorResponder responder) {
        this.responder = responder;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException {
        responder.write(response, ErrorCode.FORBIDDEN);
    }
}
