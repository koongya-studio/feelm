package com.feelm.backend.global.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 창 목록 제한 설정의 공통 검증을 검증한다(S15P21E106-284, -334 흡수).
 *
 * <p>세 설정 레코드({@code feelm.auth.login.ip}, {@code feelm.mail.rate-limit.ip}, {@code
 * feelm.mail.send-budget.windows})가 이 헬퍼 하나를 쓴다. 각 레코드의 테스트는 자기 설정 키가 메시지에 담기는지까지 확인하고, 여기서는 규칙 자체를
 * 고정한다.
 */
@DisplayName("창 목록 설정 공통 검증")
class WindowQuotasTest {

    private static final String PROPERTY = "feelm.example.windows";

    @Test
    @DisplayName("값이 없거나 비어 있으면 빈 목록으로 맞춘다")
    void nullOrEmptyBecomesEmptyList() {
        assertThat(WindowQuotas.normalize(PROPERTY, null, Quota::window)).isEmpty();
        assertThat(WindowQuotas.normalize(PROPERTY, List.of(), Quota::window)).isEmpty();
    }

    @Test
    @DisplayName("어떤 순서로 적어도 창 길이 오름차순으로 정규화된다")
    void quotasAreSortedByWindowLength() {
        List<Quota> normalized =
                WindowQuotas.normalize(
                        PROPERTY,
                        List.of(quota(300, Duration.ofHours(1)), quota(60, Duration.ofMinutes(10))),
                        Quota::window);

        assertThat(normalized)
                .containsExactly(
                        quota(60, Duration.ofMinutes(10)), quota(300, Duration.ofHours(1)));
    }

    @Test
    @DisplayName("정규화한 목록은 바꿀 수 없다")
    void normalizedListIsImmutable() {
        List<Quota> normalized =
                WindowQuotas.normalize(
                        PROPERTY, List.of(quota(10, Duration.ofMinutes(1))), Quota::window);

        assertThatThrownBy(() -> normalized.add(quota(1, Duration.ofSeconds(1))))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("표기가 달라도 같은 초의 창이 두 번 있으면 거부하고 메시지가 설정 키와 값을 가리킨다")
    void rejectsDuplicatedWindow() {
        List<Quota> quotas =
                List.of(quota(10, Duration.ofHours(1)), quota(5, Duration.ofSeconds(3600)));

        assertThatThrownBy(() -> WindowQuotas.normalize(PROPERTY, quotas, Quota::window))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PROPERTY)
                .hasMessageContaining("같은 창이 두 번")
                .hasMessageContaining("3600초");
    }

    @Test
    @DisplayName("Duration이 달라도 초로 절삭해 같아지면 거부한다")
    void rejectsWindowsThatCollapseToSameSecond() {
        List<Quota> quotas =
                List.of(quota(10, Duration.ofSeconds(1)), quota(5, Duration.ofMillis(1500)));

        assertThatThrownBy(() -> WindowQuotas.normalize(PROPERTY, quotas, Quota::window))
                .as("중복 판정은 Duration 동치가 아니라 카운터 키에 담기는 초 단위로 해야 한다")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1초");
    }

    @Test
    @DisplayName("창이 없으면 거부하고 메시지가 설정 키를 가리킨다")
    void rejectsMissingWindow() {
        assertThatThrownBy(() -> WindowQuotas.requireWindowInRange(PROPERTY, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PROPERTY)
                .hasMessageContaining("반드시 있어야 합니다");
    }

    @Test
    @DisplayName("창이 1초 미만이면 거부한다")
    void rejectsTooShortWindow() {
        assertThatThrownBy(
                        () -> WindowQuotas.requireWindowInRange(PROPERTY, Duration.ofMillis(600)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PROPERTY)
                .hasMessageContaining("PT1S 이상");
    }

    @Test
    @DisplayName("창이 24시간을 넘으면 거부한다")
    void rejectsTooLongWindow() {
        assertThatThrownBy(
                        () ->
                                WindowQuotas.requireWindowInRange(
                                        PROPERTY, Duration.ofHours(24).plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PROPERTY)
                .hasMessageContaining("PT24H 이하");
    }

    @Test
    @DisplayName("하한과 상한 자체는 허용한다")
    void acceptsBoundaryWindows() {
        WindowQuotas.requireWindowInRange(PROPERTY, Duration.ofSeconds(1));
        WindowQuotas.requireWindowInRange(PROPERTY, Duration.ofHours(24));
    }

    private static Quota quota(long limit, Duration window) {
        return new Quota(limit, window);
    }

    /** 세 설정 레코드의 {@code Quota}를 대신하는 표본. 헬퍼는 창을 꺼내는 함수만 받는다. */
    private record Quota(long limit, Duration window) {}
}
