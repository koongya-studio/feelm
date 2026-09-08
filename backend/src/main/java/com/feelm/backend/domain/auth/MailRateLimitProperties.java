package com.feelm.backend.domain.auth;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 인증 메일 재발송(API-003)과 비밀번호 재설정 요청(API-010)의 발송 제한 값이다(S15P21E106-281 결정 D-8).
 *
 * <p>접두사는 {@code feelm.mail.rate-limit}이다. 발송 제한은 메일 발송 정책이라 {@code feelm.mail} 블록에 두었고, 값을 읽는 쪽이
 * 인증 도메인의 {@link AuthRateLimiter} 하나뿐이라 record는 도메인에 둔다.
 *
 * <p>{@code ip}와 {@code global}은 창 하나가 아니라 목록으로 받는다. SMTP 계정에는 시간당 상한과 별도로 일 발송 상한이 있어 시간 창 하나로는 하루
 * 총량을 묶지 못하기 때문이다. 목록이면 축을 늘리거나 줄여도 코드가 그대로다. 운영 기본값은 {@code application.yml}에 있고, 목록이 비어 있으면 그 축은
 * 제한하지 않는다.
 *
 * <p>값은 생성 시점에 검증해 잘못된 설정이 기동에 실패하게 한다. 검증이 없으면 {@code window:} 줄을 빠뜨린 항목이 조용히 바인딩되어 첫 요청에서 {@code
 * NullPointerException}(500)이 되고, {@code limit: 0}이나 {@code email-interval: 0s}는 두 공개 엔드포인트를 통째로
 * 막거나 Redis 오류로 500을 만든다. 설정 오타를 런타임 장애가 아니라 기동 실패로 드러내는 편이 낫다. Bean Validation을 쓰지 않은 것은 다른
 * properties record와 방식을 맞추고({@code VerificationMailModeGuard}) 어떤 키가 왜 잘못됐는지 메시지에 담기 위해서다.
 */
@ConfigurationProperties(prefix = "feelm.mail.rate-limit")
public record MailRateLimitProperties(
        @DefaultValue("1m") Duration emailInterval,
        @DefaultValue List<Quota> ip,
        @DefaultValue List<Quota> global) {

    /** 목록은 값이 없으면 빈 목록으로 맞춘다. 키만 있고 항목이 없는 설정({@code ip:})이 {@code null}로 들어와도 그 축만 제한하지 않게 된다. */
    public MailRateLimitProperties {
        requirePositiveDuration("feelm.mail.rate-limit.email-interval", emailInterval);
        ip = ip == null ? List.of() : List.copyOf(ip);
        global = global == null ? List.of() : List.copyOf(global);
    }

    /**
     * 한 창에서 허용하는 발송 시도 횟수.
     *
     * @param limit 창 안에서 허용하는 시도 횟수
     * @param window 시도를 세는 기간
     */
    public record Quota(long limit, Duration window) {

        /** 한도가 0 이하이면 그 축이 모든 요청을 막고, 창이 없거나 0 이하이면 카운터에 만료를 걸 수 없다. */
        public Quota {
            if (limit < 1) {
                throw new IllegalArgumentException(
                        "feelm.mail.rate-limit의 limit은 1 이상이어야 합니다. 현재 값: " + limit);
            }
            requirePositiveDuration("feelm.mail.rate-limit의 window", window);
        }
    }

    private static void requirePositiveDuration(String property, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "%s는 0보다 큰 기간이어야 합니다. 현재 값: %s".formatted(property, value));
        }
    }
}
