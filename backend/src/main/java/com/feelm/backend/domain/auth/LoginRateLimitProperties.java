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
 * 이메일 로그인(API-004)의 시도 제한 값이다(S15P21E106-311, IP 축은 S15P21E106-312).
 *
 * <p>접두사는 {@code feelm.auth.login}이다. 같은 클래스가 쓰는 메일 발송 제한({@link MailRateLimitProperties})만 설정에 있고
 * 로그인 제한은 코드 상수였다. 두 제한의 설정 위치를 같은 방식으로 맞춘다. 값을 읽는 쪽이 {@link AuthRateLimiter} 하나뿐이라 record는 발송 제한과
 * 같이 도메인에 둔다. {@code feelm.auth}는 {@code global/security}의 {@code AuthProperties}도 바인딩하지만, 그
 * record는 CORS 허용 Origin과 개발용 mock 설정이고 이쪽은 제한 정책이라 타입을 나눈다. {@code @ConfigurationProperties}는 자기
 * 필드에 없는 키를 무시하므로 접두사가 겹쳐도 서로의 바인딩에 영향이 없다.
 *
 * <p>축은 이메일과 IP 둘이다. 이메일 축({@code attempt-limit}·{@code attempt-window})은 한 계정을 겨냥한 무차별 대입을 막고, IP
 * 축({@code ip})은 비밀번호 하나를 서로 다른 계정 수천 개에 한 번씩 넣는 password spraying과 남의 계정을 반복해서 잠그는 시도를 막는다. 이메일 축만
 * 있으면 두 공격이 계정당 한도에 걸리지 않아 어디에도 세지지 않는다(S15P21E106-312).
 *
 * <p>키 이름이 {@code attempt-}인 것은 카운터가 실패가 아니라 <b>시도</b>를 세기 때문이다. {@link
 * AuthRateLimiter#tryAcquireLoginAttempt}는 비밀번호를 대조하기 전에 카운터를 올려, 동시 요청이 몰려도 한 창 안에서 대조가 정확히 한도만큼만
 * 일어나게 한다. 계약 문장("연속 실패 제한")과 Redis 키({@code auth:login-fail:})는 그대로 둔다. IP 축도 같은 이유로 시도를
 * 센다(S15P21E106-312).
 *
 * <p>이메일 축의 운영 기본값은 {@code application.yml}에 두고 여기에도 같은 값을 기본값으로 둔다. 이 값은 {@code docs/api/users.md}
 * API-004에 적힌 계약값이라 바뀐 사실이 설정 diff로 드러나야 하고, 설정 블록이 없는 환경에서도 계약값으로 떠야 한다. IP 축은 목록이라 애노테이션으로 같은 방식의
 * 기본값을 둘 수 없어, 값이 없으면 <b>그 축은 제한하지 않는다</b>(메일 축과 같은 방식). 운영 기본값은 jar에 담기는 {@code application.yml}에
 * 있다.
 *
 * <p>창에 {@link DurationUnit}으로 초를 못박은 이유는 단위 없는 숫자의 Spring 기본 해석이 <b>밀리초</b>이기 때문이다. {@code
 * attempt-window: 600}을 600초로 의도한 설정이 600ms로 조용히 바인딩되면 기동은 성공하고 무차별 대입 보호만 사라진다. 이 업무가 없애려던 실패 모드가
 * 설정화 때문에 새로 생기는 셈이라, 단위 없는 값의 해석을 사람의 의도에 맞춘다(리뷰 B-1). IP 축의 창도 같은 함정을 갖는다.
 *
 * <p>값은 생성 시점에 검증해 잘못된 설정이 기동에 실패하게 한다({@code feelm.mail.rate-limit}·{@code feelm.mail.async}와 같은
 * 방식). 검증이 없을 때의 결과는 세 방향 모두 런타임 장애다.
 *
 * <ul>
 *   <li>{@code attempt-limit: 0} — 비밀번호가 맞아도 모든 로그인이 대조 없이 429가 된다.
 *   <li>창이 1초 미만 — {@code window.toMillis()}가 0이 되면 스크립트의 {@code PEXPIRE key 0}이 카운터를 곧바로 삭제한다.
 *       Redis는 만료 0을 <b>오류로 거부하지 않는다</b>. 그래서 결과는 500이 아니라 카운터가 쌓이지 않는 <b>제한의 조용한 무력화</b>다(리뷰 A-2).
 *       하한을 1초로 두어 {@code 500us}·{@code 600ms} 같은 표기도 기동에서 막는다.
 *   <li>창이 24시간 초과 — {@code toMillis()}가 {@code long} 범위를 넘으면 첫 로그인에서 {@code ArithmeticException}으로
 *       500이 되고, 그보다 조금 작은 값은 사실상 만료되지 않는 카운터를 만들어 실패한 계정을 영구히 잠근다(리뷰 B-5).
 * </ul>
 *
 * <p>설정 오타를 런타임 장애가 아니라 기동 실패로 드러내는 편이 낫다. Bean Validation을 쓰지 않은 것은 다른 properties record와 방식을 맞추고
 * 어떤 키가 왜 잘못됐는지 메시지에 담기 위해서다.
 *
 * <p>{@link Quota}를 {@link MailRateLimitProperties.Quota}와 공유하지 않은 이유는 잘못된 값의 메시지가 어떤 키를 가리키는지에 있다.
 * 메일 쪽 record는 메시지에 자기 접두사를 담고 있어, 그 타입을 그대로 쓰면 로그인 설정 오타가 {@code feelm.mail.rate-limit}을 가리키는 메시지로
 * 기동에 실패한다. 접두사를 알려 줄 통로가 바인딩 시점에 없고, 검증 범위도 메일 쪽(양수)보다 좁다(1초~24시간).
 *
 * @param attemptLimit 한 이메일에 대해 한 창에서 비밀번호를 대조하는 시도 횟수. 이 횟수를 넘는 시도는 대조 없이 429다
 * @param attemptWindow 이메일 축이 시도를 세는 기간. 첫 시도 시점부터 흐르는 고정 창이며 단위를 생략하면 초로 읽는다
 * @param ip 한 클라이언트 IP에 허용하는 창별 시도 횟수. 비어 있으면 IP 축은 제한하지 않는다. 같은 창을 두 번 넣을 수 없고, 어떤 순서로 적어도 창 길이
 *     오름차순으로 정규화된다
 */
@ConfigurationProperties(prefix = "feelm.auth.login")
public record LoginRateLimitProperties(
        @DefaultValue("5") long attemptLimit,
        @DefaultValue("10m") @DurationUnit(ChronoUnit.SECONDS) Duration attemptWindow,
        @DefaultValue List<Quota> ip) {

    /** 창의 하한. Redis 만료를 밀리초로 넘기므로 1초 미만은 카운터가 쌓이지 않는 구간을 만든다. */
    private static final Duration MINIMUM_WINDOW = Duration.ofSeconds(1);

    /** 창의 상한. 이보다 긴 창은 사실상 영구 잠금이고, 더 크면 밀리초 변환이 넘친다. */
    private static final Duration MAXIMUM_WINDOW = Duration.ofHours(24);

    /**
     * 목록은 값이 없으면 빈 목록으로 맞추고, 창 길이 오름차순으로 정규화한다.
     *
     * <p>키만 있고 항목이 없는 설정({@code ip:})이 {@code null}로 들어와도 되게 한다.
     *
     * <p><b>정렬하는 이유</b>: 앞 창에서 막히면 뒤 창의 카운터를 올리지 않으므로(리미터의 평가 방식) 목록 순서가 정책을 바꾼다. 긴 창을 먼저 두면 짧은 시간에
     * 몰아치는 시도가 긴 창의 카운터를 먼저 태워, 10분짜리 폭주가 1시간 차단으로 확대된다. 설정 파일에 적은 순서가 정책이 되면 사람이 알기 어려운 함정이라, 어떤
     * 순서로 적어도 짧은 창이 먼저 평가되게 고정한다(리뷰 A-2).
     *
     * <p><b>창 중복을 거부하는 이유</b>: 키에 창을 <b>초 단위</b>로 담으므로 {@code 10m}과 {@code 600}처럼 표기가 달라도 같은 키가 된다.
     * 그 설정은 한 요청이 같은 카운터를 두 번 올려 실효 한도를 절반으로 떨어뜨리는데, 기동도 성공하고 로그도 남지 않는다(리뷰 B-4·A-5). 1초 미만은 이미
     * 거부하므로 절삭으로 0초가 되는 경우는 없다.
     */
    public LoginRateLimitProperties {
        if (attemptLimit < 1) {
            throw new IllegalArgumentException(
                    "feelm.auth.login.attempt-limit은 1 이상이어야 합니다. 현재 값: " + attemptLimit);
        }
        requireWindowInRange("feelm.auth.login.attempt-window", attemptWindow);
        ip = normalizeQuotas(ip);
    }

    /** 빈 목록으로 맞추고, 창 중복을 거부한 뒤 창 길이 오름차순으로 정렬한다. */
    private static List<Quota> normalizeQuotas(List<Quota> quotas) {
        if (quotas == null || quotas.isEmpty()) {
            return List.of();
        }
        Set<Long> windowSeconds = new HashSet<>();
        for (Quota quota : quotas) {
            if (!windowSeconds.add(quota.window().toSeconds())) {
                throw new IllegalArgumentException(
                        ("feelm.auth.login.ip에 같은 창이 두 번 있습니다. 창은 초 단위로 카운터 키에 들어가므로"
                                        + " 표기가 달라도 %d초는 하나만 쓸 수 있습니다. 현재 값: %s")
                                .formatted(quota.window().toSeconds(), quotas));
            }
        }
        List<Quota> sorted = new ArrayList<>(quotas);
        sorted.sort(Comparator.comparing(Quota::window));
        return List.copyOf(sorted);
    }

    /**
     * 한 창에서 허용하는 IP 단위 시도 횟수.
     *
     * <p>창 하나가 아니라 목록으로 받는다. 짧은 창은 한 번에 몰아치는 spraying을 막고 긴 창은 오래 끌며 천천히 훑는 시도를 막는데, 창 하나로는 둘을 함께
     * 잡을 수 없다. 목록이면 창을 늘리거나 줄여도 코드가 그대로다(완료 조건 3).
     *
     * @param limit 창 안에서 한 IP에 허용하는 시도 횟수
     * @param window 시도를 세는 기간. 단위를 생략하면 초로 읽는다
     */
    public record Quota(long limit, @DurationUnit(ChronoUnit.SECONDS) Duration window) {

        /** 한도가 0 이하이면 그 IP의 모든 로그인이 대조 없이 429가 되고, 창이 범위를 벗어나면 카운터가 쌓이지 않거나 영구히 남는다. */
        public Quota {
            if (limit < 1) {
                throw new IllegalArgumentException(
                        "feelm.auth.login.ip의 limit은 1 이상이어야 합니다. 현재 값: " + limit);
            }
            requireWindowInRange("feelm.auth.login.ip의 window", window);
        }
    }

    /** 창이 있고 Redis 만료로 넘길 수 있는 범위인지 본다. 두 축의 창이 같은 이유로 같은 범위를 갖는다. */
    private static void requireWindowInRange(String property, Duration window) {
        if (window == null) {
            throw new IllegalArgumentException("%s는 반드시 있어야 합니다.".formatted(property));
        }
        if (window.compareTo(MINIMUM_WINDOW) < 0) {
            throw new IllegalArgumentException(
                    "%s는 %s 이상이어야 합니다. 현재 값: %s".formatted(property, MINIMUM_WINDOW, window));
        }
        if (window.compareTo(MAXIMUM_WINDOW) > 0) {
            throw new IllegalArgumentException(
                    "%s는 %s 이하여야 합니다. 현재 값: %s".formatted(property, MAXIMUM_WINDOW, window));
        }
    }
}
