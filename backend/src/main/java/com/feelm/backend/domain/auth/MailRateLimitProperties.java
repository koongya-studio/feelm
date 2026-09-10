package com.feelm.backend.domain.auth;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;

/**
 * 인증 메일 재발송(API-003)과 비밀번호 재설정 요청(API-010)의 <b>요청 단위</b> 발송 제한 값이다(S15P21E106-281 결정 D-8).
 *
 * <p>접두사는 {@code feelm.mail.rate-limit}이다. 발송 제한은 메일 발송 정책이라 {@code feelm.mail} 블록에 두었고, 값을 읽는 쪽이
 * 인증 도메인의 {@link AuthRateLimiter} 하나뿐이라 record는 도메인에 둔다.
 *
 * <p>축은 이메일 단위 간격과 IP 단위 둘이다. 전역 축은 여기에 없다. 요청을 세는 전역 상한은 메일을 한 통도 보내지 않고 예산을 말릴 수 있어 계정 복구 경로를 막는
 * 지렛대였고, SMTP 쿼터를 지키는 상한은 실제 발송을 세는 {@code feelm.mail.send-budget}으로 옮겼다(S15P21E106-287 결정
 * D-1·D-4).
 *
 * <p>{@code ip}는 창 하나가 아니라 목록으로 받는다. 짧은 창과 긴 창을 함께 두어야 몰아치는 요청과 오래 끄는 요청을 같이 잡을 수 있고, 목록이면 창을 늘리거나
 * 줄여도 코드가 그대로다. 운영 기본값은 {@code application.yml}에 있고, 목록이 비어 있으면 그 축은 제한하지 않는다. 목록은 창 중복을 거부하고 창 길이
 * 오름차순으로 정규화한다({@code feelm.auth.login.ip}·{@code feelm.mail.send-budget}과 같은 규칙, S15P21E106-287 리뷰
 * B).
 *
 * <p>기간에 {@link DurationUnit}으로 초를 못박은 이유는 단위 없는 숫자의 Spring 기본 해석이 <b>밀리초</b>이기 때문이다(리뷰 A-3·B-6). 이
 * 값들은 배포 환경변수로 열려 있어(S15P21E106-287 결정 D-9) 운영 중에 사람이 직접 적는다. {@code
 * MAIL_RATE_LIMIT_IP_WINDOW=3600}을 한 시간으로 의도한 설정이 3.6초로 조용히 바인딩되면 기동은 성공하고 IP 축 보호만 사라진다. 전역 축을 발송
 * 지점으로 옮긴 뒤 요청 폭주를 잡는 축이 이것뿐이라 특히 위험하다. {@code feelm.mail.send-budget}·{@code feelm.auth.login}과 같은
 * 규칙이다.
 *
 * <p>값은 생성 시점에 검증해 잘못된 설정이 기동에 실패하게 한다. 검증이 없으면 {@code window:} 줄을 빠뜨린 항목이 조용히 바인딩되어 첫 요청에서 {@code
 * NullPointerException}(500)이 되고, {@code limit: 0}이나 {@code email-interval: 0s}는 두 공개 엔드포인트를 통째로
 * 막거나 Redis 오류로 500을 만든다. 설정 오타를 런타임 장애가 아니라 기동 실패로 드러내는 편이 낫다. Bean Validation을 쓰지 않은 것은 다른
 * properties record와 방식을 맞추고({@code VerificationMailModeGuard}) 어떤 키가 왜 잘못됐는지 메시지에 담기 위해서다.
 */
@ConfigurationProperties(prefix = "feelm.mail.rate-limit")
public record MailRateLimitProperties(
        @DefaultValue("1m") @DurationUnit(ChronoUnit.SECONDS) Duration emailInterval,
        @DefaultValue List<Quota> ip) {

    /** 창의 하한. Redis 만료를 밀리초로 넘기므로 1초 미만은 카운터가 쌓이지 않는 구간을 만든다. */
    private static final Duration MINIMUM_WINDOW = Duration.ofSeconds(1);

    /** 창의 상한. 이보다 긴 창은 사실상 영구 차단이다. */
    private static final Duration MAXIMUM_WINDOW = Duration.ofHours(24);

    /**
     * 목록은 값이 없으면 빈 목록으로 맞추고, 창 길이 오름차순으로 정규화한다. 키만 있고 항목이 없는 설정({@code ip:})이 {@code null}로 들어와도 그
     * 축만 제한하지 않게 된다.
     */
    public MailRateLimitProperties {
        requireDurationInRange("feelm.mail.rate-limit.email-interval", emailInterval);
        ip = normalizeQuotas(ip);
    }

    /**
     * 창 중복을 거부하고 창 길이 오름차순으로 맞춘다. 이유는 {@code feelm.auth.login.ip}·{@code
     * feelm.mail.send-budget.windows}와 같다(S15P21E106-287 리뷰 B).
     *
     * <p>{@code AuthRateLimiter}가 창을 초 단위로 카운터 키에 담으므로({@code auth:mail:ip:<hash>:<초>}) {@code 1h}와
     * {@code 3600}은 같은 카운터가 되어 한 번의 요청이 같은 값을 두 번 올리고 실효 한도가 절반이 된다. 앞 창에서 막히면 뒤 창의 카운터를 올리지 않으므로
     * 목록 순서도 정책을 바꾼다. 두 사고 모두 기동은 성공하고 로그도 남지 않아 기동 실패로 드러내는 편이 낫다.
     */
    private static List<Quota> normalizeQuotas(List<Quota> quotas) {
        if (quotas == null || quotas.isEmpty()) {
            return List.of();
        }
        Set<Long> windowSeconds = new HashSet<>();
        for (Quota quota : quotas) {
            if (!windowSeconds.add(quota.window().toSeconds())) {
                throw new IllegalArgumentException(
                        ("feelm.mail.rate-limit.ip에 같은 창이 두 번 있습니다. 창은 초 단위로 카운터 키에 들어가므로"
                                        + " 표기가 달라도 %d초는 하나만 쓸 수 있습니다. 현재 값: %s")
                                .formatted(quota.window().toSeconds(), quotas));
            }
        }
        List<Quota> sorted = new ArrayList<>(quotas);
        sorted.sort(Comparator.comparing(Quota::window));
        return List.copyOf(sorted);
    }

    /**
     * 한 창에서 허용하는 발송 시도 횟수.
     *
     * @param limit 창 안에서 허용하는 시도 횟수
     * @param window 시도를 세는 기간
     */
    public record Quota(long limit, @DurationUnit(ChronoUnit.SECONDS) Duration window) {

        /** 한도가 0 이하이면 그 축이 모든 요청을 막고, 창이 범위를 벗어나면 카운터가 쌓이지 않거나 사실상 영구히 남는다. */
        public Quota {
            if (limit < 1) {
                throw new IllegalArgumentException(
                        "feelm.mail.rate-limit의 limit은 1 이상이어야 합니다. 현재 값: " + limit);
            }
            requireDurationInRange("feelm.mail.rate-limit의 window", window);
        }
    }

    /** 값이 있고 Redis 만료로 넘길 수 있는 범위인지 본다. 간격과 창이 같은 이유로 같은 범위를 갖는다. */
    private static void requireDurationInRange(String property, Duration value) {
        if (value == null) {
            throw new IllegalArgumentException("%s는 반드시 있어야 합니다.".formatted(property));
        }
        if (value.compareTo(MINIMUM_WINDOW) < 0) {
            throw new IllegalArgumentException(
                    "%s는 %s 이상이어야 합니다. 현재 값: %s".formatted(property, MINIMUM_WINDOW, value));
        }
        if (value.compareTo(MAXIMUM_WINDOW) > 0) {
            throw new IllegalArgumentException(
                    "%s는 %s 이하여야 합니다. 현재 값: %s".formatted(property, MAXIMUM_WINDOW, value));
        }
    }
}
