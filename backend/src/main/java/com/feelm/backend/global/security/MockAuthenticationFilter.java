package com.feelm.backend.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 로그인 구현 전에 회원 API를 호출할 수 있도록 고정 사용자를 주입하는 개발용 필터다.
 *
 * <p>인증을 무력화하는 장치이므로 {@code feelm.auth.mock.enabled}가 켜진 환경에서만 Bean으로 등록된다({@link SecurityConfig}
 * 참고). 운영 프로파일에서 이 값이 켜지지 않도록 주의한다.
 *
 * <p>이미 유효한 Access Token으로 신원이 확인된 요청은 건드리지 않는다. 실제 토큰 흐름을 함께 시험할 수 있어야 하기 때문이다.
 */
public class MockAuthenticationFilter extends OncePerRequestFilter {

    private final long userId;

    public MockAuthenticationFilter(long userId) {
        this.userId = userId;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            new UsernamePasswordAuthenticationToken(userId, null, List.of()));
        }
        filterChain.doFilter(request, response);
    }
}
