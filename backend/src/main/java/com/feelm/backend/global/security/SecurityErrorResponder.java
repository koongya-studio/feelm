package com.feelm.backend.global.security;

import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.error.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Security 필터 단계에서 공통 오류 형식으로 응답한다.
 *
 * <p>필터는 {@code DispatcherServlet} 앞에서 동작하므로 여기서 발생한 예외는 {@code GlobalExceptionHandler}에 도달하지 않는다.
 * 그대로 두면 인증 실패 응답만 규약을 벗어나므로, 응답 본문 생성은 {@link ErrorResponse#from}으로 통일한다.
 */
@Component
public class SecurityErrorResponder {

    /** 필터가 판정한 오류 코드를 진입점(EntryPoint)까지 전달하는 요청 속성 이름이다. */
    static final String ERROR_CODE_ATTRIBUTE =
            SecurityErrorResponder.class.getName() + ".errorCode";

    private final ObjectMapper objectMapper;

    public SecurityErrorResponder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ErrorResponse.from(errorCode));
    }
}
