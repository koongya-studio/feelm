package com.feelm.backend.global.properties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 창 목록으로 표현하는 제한 설정의 공통 검증이다(S15P21E106-284, S15P21E106-334 흡수).
 *
 * <p>창 목록을 받는 설정 레코드가 세 개다. {@code feelm.auth.login.ip}, {@code feelm.mail.rate-limit.ip}, {@code
 * feelm.mail.send-budget.windows}. 세 축 모두 창 길이를 <b>초 단위</b>로 Redis 카운터 키에 담고, 앞 창에서 막히면 뒤 창의 카운터를
 * 올리지 않는 방식으로 평가한다. 그래서 세 곳에 같은 검증이 필요하다.
 *
 * <ul>
 *   <li><b>창 중복 거부</b>: {@code 1h}와 {@code 3600}은 표기만 달라 같은 카운터 키가 된다. 두 항목이 같은 키를 쓰면 한 번의 요청이 같은
 *       값을 두 번 올려 실효 한도가 절반이 되는데, 기동은 성공하고 로그도 남지 않는다.
 *   <li><b>창 길이 오름차순 정렬</b>: 목록 순서가 정책을 바꾼다. 긴 창을 먼저 두면 짧은 시간에 몰아치는 시도가 긴 창의 카운터를 먼저 태워 짧은 폭주가 긴
 *       차단으로 확대된다. 어떤 순서로 적어도 짧은 창이 먼저 평가되게 고정한다.
 *   <li><b>창 범위</b>: 1초 미만은 {@code PEXPIRE key 0}이 카운터를 곧바로 지워 제한을 조용히 무력화하고, 24시간 초과는 한 번 마르면 사실상
 *       영구 차단이다.
 * </ul>
 *
 * <p><b>이 클래스를 {@code global}에 둔 이유</b>(S15P21E106-284 완료 조건 5). 세 레코드가 {@code domain/auth}와 {@code
 * infrastructure/mail}로 나뉘어 있어 위치가 곧 패키지 의존 방향 결정이다. {@code domain/auth}에 두면 {@code
 * infrastructure/mail}의 설정이 도메인 패키지를 의존하게 되어 방향이 뒤집히고, {@code infrastructure/mail}에 두면 <b>로그인</b>
 * 제한 설정이 <b>메일</b> 발송 인프라를 의존하게 되어 의미가 맞지 않는다. {@code global}은 모든 패키지가 의존할 수 있는 자리이고, 세 레코드가 실제로
 * 사용하며 의미도 같으므로 컨벤션 3장이 요구하는 "실제 사용 + 같은 의미" 조건을 만족한다.
 *
 * <p>예외 메시지에 설정 키를 호출자가 넘기는 것도 같은 이유다. 잘못된 값이 어떤 키에서 왔는지 기동 실패 메시지가 알려 주어야 하고(각 레코드가 자기 접두사를 안다),
 * 그래서 이 헬퍼는 키를 알지 못한 채 문구만 만든다.
 */
public final class WindowQuotas {

    /** 창의 하한. Redis 만료를 밀리초로 넘기므로 1초 미만은 카운터가 쌓이지 않는 구간을 만든다. */
    private static final Duration MINIMUM_WINDOW = Duration.ofSeconds(1);

    /** 창의 상한. 이보다 긴 창은 사실상 영구 차단이고, 더 크면 밀리초 변환이 넘친다. */
    private static final Duration MAXIMUM_WINDOW = Duration.ofHours(24);

    private WindowQuotas() {}

    /**
     * 목록을 빈 목록으로 맞추고, 창 중복을 거부한 뒤 창 길이 오름차순으로 정렬한다.
     *
     * <p>키만 있고 항목이 없는 설정({@code ip:})이 {@code null}로 들어와도 되게 한다. 항목별 값 검증(한도, 창 범위)은 각 {@code
     * Quota} 생성자가 이미 했으므로 여기서 다시 보지 않는다.
     *
     * @param property 오류 메시지에 담을 설정 키
     * @param quotas 바인딩된 목록. {@code null}이거나 비어 있을 수 있다
     * @param window 항목에서 창 길이를 꺼내는 함수
     * @return 정렬된 불변 목록
     */
    public static <T> List<T> normalize(
            String property, List<T> quotas, Function<T, Duration> window) {
        if (quotas == null || quotas.isEmpty()) {
            return List.of();
        }
        Set<Long> windowSeconds = new HashSet<>();
        for (T quota : quotas) {
            long seconds = window.apply(quota).toSeconds();
            if (!windowSeconds.add(seconds)) {
                throw new IllegalArgumentException(
                        ("%s에 같은 창이 두 번 있습니다. 창은 초 단위로 카운터 키에 들어가므로"
                                        + " 표기가 달라도 %d초는 하나만 쓸 수 있습니다. 현재 값: %s")
                                .formatted(property, seconds, quotas));
            }
        }
        List<T> sorted = new ArrayList<>(quotas);
        sorted.sort(Comparator.comparing(window));
        return List.copyOf(sorted);
    }

    /**
     * 창이 있고 Redis 만료로 넘길 수 있는 범위인지 본다.
     *
     * <p>창이 아닌 기간({@code feelm.mail.rate-limit.email-interval})도 같은 이유로 같은 범위를 쓴다. 그 값도 Redis 만료로
     * 넘어가고, 0에 가까우면 간격이 사라지고 지나치게 길면 사실상 영구 차단이 된다.
     *
     * @param property 오류 메시지에 담을 설정 키
     */
    public static void requireWindowInRange(String property, Duration window) {
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
