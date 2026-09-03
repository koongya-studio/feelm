package com.feelm.backend.global.security;

import com.feelm.backend.global.error.BusinessException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code Authorization: Bearer} 헤더의 Access Token을 검증해 신원을 확인한다.
 *
 * <p>검증에 실패해도 여기서 응답을 끊지 않고 통과시킨다. 인증 없이도 호출할 수 있는 <b>공개</b>·<b>선택</b> 구분의 API가 있기 때문이다(API-000).
 * 실제 거절 여부는 뒤의 인가 단계가 판단하고, 그때 필요한 오류 코드는 요청 속성으로 넘겨 {@link JwtAuthenticationEntryPoint}가 사용한다.
 *
 * <p>Bean으로 등록하지 않고 {@link SecurityConfig}에서 직접 생성한다. {@code Filter} 타입 Bean은 Security 필터 체인과 별개로
 * 서블릿 컨테이너에도 자동 등록되어 의도한 순서 밖에서 한 번 더 동작하기 때문이다.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenProvider tokenProvider;

    public JwtAuthenticationFilter(TokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null) {
            try {
                long userId = tokenProvider.parseAccessToken(token);
                SecurityContextHolder.getContext()
                        .setAuthentication(
                                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
            } catch (BusinessException exception) {
                request.setAttribute(
                        SecurityErrorResponder.ERROR_CODE_ATTRIBUTE, exception.getErrorCode());
            }
        }
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
