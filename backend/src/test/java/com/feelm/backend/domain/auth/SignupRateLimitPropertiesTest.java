package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feelm.backend.domain.auth.SignupRateLimitProperties.Quota;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 가입 요청 제한 설정값의 검증을 확인한다(S15P21E106-603).
 *
 * <p>메일 축과 같은 규칙을 쓴다({@link com.feelm.backend.global.properties.WindowQuotas}). 잘못된 설정이 기동에 실패해야 하는
 * 이유도 같다. {@code window}가 없으면 첫 가입 요청에서 {@code NullPointerException}(500)이 되고, {@code limit: 0}은
 * 가입을 통째로 막으며, 1초 미만 창은 카운터가 쌓이지 않아 제한이 조용히 사라진다.
 *
 * <p>바인딩 자체가 아니라 record 생성만 검증한다. {@code @ConfigurationProperties} 바인딩이 이 생성자를 호출하므로 여기서 던지는 예외가 곧
 * 기동 실패다.
 */
@DisplayName("가입 요청 제한 설정값 — 검증")
class SignupRateLimitPropertiesTest {

    private static final Duration ONE_HOUR = Duration.ofHours(1);

    @Test
    @DisplayName("창 길이가 없는 항목은 만들 수 없다")
    void quotaRejectsMissingWindow() {
        assertThatThrownBy(() -> new Quota(10, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.signup.ip");
    }

    @Test
    @DisplayName("한도가 1 미만이면 만들 수 없다")
    void quotaRejectsNonPositiveLimit() {
        assertThatThrownBy(() -> new Quota(0, ONE_HOUR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.signup.ip");
    }

    @Test
    @DisplayName("1초 미만 창은 만들 수 없다")
    void quotaRejectsTooShortWindow() {
        assertThatThrownBy(() -> new Quota(10, Duration.ofMillis(600)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.signup.ip");
    }

    @Test
    @DisplayName("같은 창을 두 번 넣을 수 없다")
    void rejectsDuplicatedWindow() {
        assertThatThrownBy(
                        () ->
                                new SignupRateLimitProperties(
                                        List.of(new Quota(10, ONE_HOUR), new Quota(20, ONE_HOUR))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.signup.ip");
    }

    @Test
    @DisplayName("목록이 없으면 빈 목록이 되어 그 축은 제한하지 않는다")
    void missingListBecomesEmpty() {
        assertThat(new SignupRateLimitProperties(null).ip()).isEmpty();
    }

    @Test
    @DisplayName("창 길이 오름차순으로 정규화한다")
    void normalizesQuotasByWindow() {
        SignupRateLimitProperties properties =
                new SignupRateLimitProperties(
                        List.of(new Quota(30, ONE_HOUR), new Quota(10, Duration.ofMinutes(10))));

        assertThat(properties.ip())
                .extracting(Quota::window)
                .containsExactly(Duration.ofMinutes(10), ONE_HOUR);
    }
}
