package com.feelm.backend.infrastructure.mail;

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
 * 계정 메일의 전역 발송 상한이다(S15P21E106-287 결정 D-1).
 *
 * <p>접두사는 {@code feelm.mail.send-budget}이다. 이 값은 SMTP 계정의 발송 쿼터를 지키는 상한이라 발송 수단 쪽 관심사이고, 값을 읽는 쪽도
 * {@link RedisMailSendBudget} 하나뿐이라 record를 발송 인프라에 둔다({@link MailSendBudget} 자체는 요청 경로의 {@code
 * hasCapacity()} 확인 때문에 인증 도메인의 서비스도 주입받지만, 그 서비스들은 이 설정값을 보지 않는다). 요청 단위 제한({@code
 * feelm.mail.rate-limit})은 인증 도메인의 관심사라 그대로 둔다.
 *
 * <p>이 상한은 <b>실제 발송</b>을 센다. S15P21E106-281은 세 축을 모두 시도 기준으로 두었는데(결정 D-4), 전역 축까지 시도를 세면 메일을 한 통도
 * 보내지 않고 예산을 말릴 수 있었다. 기본값에서 IP 버킷 30개가 각각 IP 한도를 채우면 하루 상한이 마르고, 그동안 정상 사용자의 인증 메일 재발송(API-003)과
 * 비밀번호 재설정(API-010)이 모두 429가 된다. IPv6 종단 사용자는 보통 {@code /56} 이상을 배정받아 버킷 수백 개를 혼자 쓸 수 있으므로 값싼
 * 지렛대였다.
 *
 * <p>발송을 세면서도 계정 존재가 드러나지 않는 것은 <b>평가 지점을 함께 옮겼기</b> 때문이다(결정 D-2). D-4가 경계한 "발송한 것만 세면 카운터 소모 여부가
 * 가입 여부를 알려 준다"는 그 카운터가 응답을 바꿀 때만 성립한다. 이 예산은 요청 경로가 아니라 발송 스레드에서 소모되고 초과해도 응답이 바뀌지 않으므로, 응답을 관찰해
 * 예산을 되짚는 경로가 없다.
 *
 * <p>{@code windows}는 창 하나가 아니라 목록으로 받는다. SMTP 계정에는 시간당 상한과 별도로 일 발송 상한이 있어 시간 창 하나로는 하루 총량을 묶지
 * 못한다. 운영 기본값은 {@code application.yml}에 있고, 목록이 비어 있으면 발송을 막지 않는다.
 *
 * <p>값은 생성 시점에 검증해 잘못된 설정이 기동에 실패하게 한다({@code feelm.auth.login}과 같은 방식). {@code limit: 0}은 모든 메일을
 * 막고, 1초 미만의 창은 {@code PEXPIRE key 0}이 카운터를 곧바로 지워 상한을 조용히 무력화하며, 24시간을 넘는 창은 한 번 마르면 사실상 영구 차단이다.
 * 발송이 응답과 분리돼 있어(S15P21E106-282) 이 실패들은 요청 쪽에 아무 신호도 남기지 않고 메일만 사라지므로, 기동 실패로 드러내는 편이 특히 낫다.
 *
 * @param windows 창별 발송 상한. 비어 있으면 전역 상한을 적용하지 않는다. 같은 창을 두 번 넣을 수 없고, 어떤 순서로 적어도 창 길이 오름차순으로 정규화된다
 */
@ConfigurationProperties(prefix = "feelm.mail.send-budget")
public record MailSendBudgetProperties(@DefaultValue List<Quota> windows) {

    /** 창의 하한. Redis 만료를 밀리초로 넘기므로 1초 미만은 카운터가 쌓이지 않는 구간을 만든다. */
    private static final Duration MINIMUM_WINDOW = Duration.ofSeconds(1);

    /** 창의 상한. 이보다 긴 창은 한 번 마르면 사실상 영구 차단이다(리뷰 A-8). */
    private static final Duration MAXIMUM_WINDOW = Duration.ofHours(24);

    /**
     * 목록은 값이 없으면 빈 목록으로 맞추고, 창 길이 오름차순으로 정규화한다.
     *
     * <p>정렬과 창 중복 거부의 이유는 {@code feelm.auth.login.ip}와 같다. 앞 창에서 막히면 뒤 창의 카운터를 올리지 않으므로 목록 순서가 정책을
     * 바꾸고, 창을 초 단위로 키에 담으므로 {@code 1h}와 {@code 3600}은 같은 카운터가 되어 한 번의 발송이 같은 값을 두 번 올린다.
     */
    public MailSendBudgetProperties {
        windows = normalizeQuotas(windows);
    }

    private static List<Quota> normalizeQuotas(List<Quota> quotas) {
        if (quotas == null || quotas.isEmpty()) {
            return List.of();
        }
        Set<Long> windowSeconds = new HashSet<>();
        for (Quota quota : quotas) {
            if (!windowSeconds.add(quota.window().toSeconds())) {
                throw new IllegalArgumentException(
                        ("feelm.mail.send-budget.windows에 같은 창이 두 번 있습니다. 창은 초 단위로 카운터 키에"
                                        + " 들어가므로 표기가 달라도 %d초는 하나만 쓸 수 있습니다. 현재 값: %s")
                                .formatted(quota.window().toSeconds(), quotas));
            }
        }
        List<Quota> sorted = new ArrayList<>(quotas);
        sorted.sort(Comparator.comparing(Quota::window));
        return List.copyOf(sorted);
    }

    /**
     * 한 창에서 허용하는 실제 발송 건수.
     *
     * @param limit 창 안에서 보낼 수 있는 메일 수
     * @param window 발송을 세는 기간. 첫 발송 시점부터 흐르는 고정 창이며 단위를 생략하면 초로 읽는다
     */
    public record Quota(long limit, @DurationUnit(ChronoUnit.SECONDS) Duration window) {

        /** 한도가 0 이하이면 어떤 메일도 나가지 않고, 창이 범위를 벗어나면 카운터가 쌓이지 않거나 영구히 남는다. */
        public Quota {
            if (limit < 1) {
                throw new IllegalArgumentException(
                        "feelm.mail.send-budget.windows의 limit은 1 이상이어야 합니다. 현재 값: " + limit);
            }
            requireWindowInRange(window);
        }

        private static void requireWindowInRange(Duration window) {
            if (window == null) {
                throw new IllegalArgumentException(
                        "feelm.mail.send-budget.windows의 window는 반드시 있어야 합니다.");
            }
            if (window.compareTo(MINIMUM_WINDOW) < 0) {
                throw new IllegalArgumentException(
                        "feelm.mail.send-budget.windows의 window는 %s 이상이어야 합니다. 현재 값: %s"
                                .formatted(MINIMUM_WINDOW, window));
            }
            if (window.compareTo(MAXIMUM_WINDOW) > 0) {
                throw new IllegalArgumentException(
                        "feelm.mail.send-budget.windows의 window는 %s 이하여야 합니다. 현재 값: %s"
                                .formatted(MAXIMUM_WINDOW, window));
            }
        }
    }
}
