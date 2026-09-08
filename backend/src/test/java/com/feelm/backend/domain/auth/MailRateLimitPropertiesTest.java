package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feelm.backend.domain.auth.MailRateLimitProperties.Quota;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 발송 제한 설정값의 검증을 확인한다(S15P21E106-281).
 *
 * <p>잘못된 설정이 기동에 실패해야 하는 이유는 실패하지 않을 때의 결과가 런타임 장애이기 때문이다. {@code window}가 없으면 첫 요청에서 {@code
 * NullPointerException}(500)이 되고, {@code limit: 0}은 두 공개 엔드포인트를 통째로 막고, {@code email-interval: 0s}는
 * Redis가 만료 0을 거부해 500이 된다. 설정 오타가 첫 사용자 요청이 아니라 기동에서 드러나는지 본다.
 *
 * <p>바인딩 자체가 아니라 record 생성만 검증한다. {@code @ConfigurationProperties} 바인딩은 이 생성자를 호출하므로 여기서 던지는 예외가 곧
 * 기동 실패다.
 */
@DisplayName("메일 발송 제한 설정값 — 검증")
class MailRateLimitPropertiesTest {

    private static final Duration ONE_HOUR = Duration.ofHours(1);

    @Test
    @DisplayName("창 길이가 없는 항목은 만들 수 없다")
    void quotaRejectsMissingWindow() {
        assertThatThrownBy(() -> new Quota(10, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
    }

    @Test
    @DisplayName("창 길이가 0 이하인 항목은 만들 수 없다")
    void quotaRejectsNonPositiveWindow() {
        assertThatThrownBy(() -> new Quota(10, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Quota(10, ONE_HOUR.negated()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("한도가 1보다 작은 항목은 만들 수 없다")
    void quotaRejectsNonPositiveLimit() {
        assertThatThrownBy(() -> new Quota(0, ONE_HOUR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> new Quota(-1, ONE_HOUR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("이메일 간격이 없거나 0 이하이면 만들 수 없다")
    void propertiesRejectNonPositiveEmailInterval() {
        assertThatThrownBy(() -> properties(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email-interval");
        assertThatThrownBy(() -> properties(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 목록 키만 있고 항목이 없는 설정({@code ip:})은 그 축만 제한하지 않는 상태이고, 기동을 막을 이유는 아니다. */
    @Test
    @DisplayName("축 목록이 비어 있거나 없으면 빈 목록이 된다")
    void missingAxisBindsToEmptyList() {
        MailRateLimitProperties properties =
                new MailRateLimitProperties(Duration.ofMinutes(1), null, List.of());

        assertThat(properties.ip()).isEmpty();
        assertThat(properties.global()).isEmpty();
    }

    @Test
    @DisplayName("정상 값은 그대로 유지된다")
    void validValuesArePreserved() {
        Quota hourly = new Quota(100, ONE_HOUR);

        MailRateLimitProperties properties =
                new MailRateLimitProperties(
                        Duration.ofMinutes(1), List.of(new Quota(10, ONE_HOUR)), List.of(hourly));

        assertThat(properties.emailInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.ip()).containsExactly(new Quota(10, ONE_HOUR));
        assertThat(properties.global()).containsExactly(hourly);
    }

    private MailRateLimitProperties properties(Duration emailInterval) {
        return new MailRateLimitProperties(
                emailInterval, List.of(new Quota(10, ONE_HOUR)), List.of());
    }
}
