package com.feelm.backend.global.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 인증 관련 환경별 설정값이다.
 *
 * <p>{@code allowedOrigins}는 기본값이 비어 있다. Refresh Token 쿠키를 주고받으려면 브라우저가 {@code credentials}를 실어 보내야
 * 하고, 그때는 와일드카드 Origin을 쓸 수 없어 환경마다 명시해야 하기 때문이다.
 */
@ConfigurationProperties(prefix = "feelm.auth")
public record AuthProperties(@DefaultValue List<String> allowedOrigins, @DefaultValue Mock mock) {

    /** 개발용 고정 사용자 주입 설정. 운영 환경에서는 켜지 않는다. */
    public record Mock(@DefaultValue("false") boolean enabled, @DefaultValue("1") long userId) {}
}
