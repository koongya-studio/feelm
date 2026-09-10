package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feelm.backend.domain.auth.MailRateLimitProperties.Quota;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 요청 단위 발송 제한 설정값의 검증을 확인한다(S15P21E106-281). 전역 발송 상한은 {@code MailSendBudgetPropertiesTest}가 본다.
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

    /**
     * 이 값들은 배포 환경변수로 열려 있어 사람이 직접 적는다(S15P21E106-287 결정 D-9). 단위 없는 숫자는 초로 읽히고, 1초 미만은 {@code
     * PEXPIRE key 0}이 카운터를 곧바로 지워 IP 축을 조용히 무력화한다(리뷰 A-3·B-6).
     */
    @Test
    @DisplayName("창 길이가 1초 미만이거나 24시간을 넘으면 만들 수 없다")
    void quotaRejectsWindowOutOfRange() {
        assertThatThrownBy(() -> new Quota(30, Duration.ofMillis(600)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
        assertThatThrownBy(() -> new Quota(30, Duration.ofHours(25)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("잘못된 값의 메시지가 메일 축의 설정 키를 가리킨다")
    void messagesPointAtMailKeys() {
        assertThatThrownBy(() -> new Quota(0, ONE_HOUR))
                .as("검증 규칙이 공통 헬퍼로 옮겨진 뒤에도 메시지는 자기 설정 키를 가리켜야 한다")
                .hasMessageContaining("feelm.mail.rate-limit");
        assertThatThrownBy(() -> new Quota(100, null))
                .hasMessageContaining("feelm.mail.rate-limit의 window");
        assertThatThrownBy(() -> new MailRateLimitProperties(Duration.ZERO, List.of()))
                .hasMessageContaining("feelm.mail.rate-limit.email-interval");
    }

    @Test
    @DisplayName("이메일 간격이 1초 미만이거나 24시간을 넘으면 만들 수 없다")
    void propertiesRejectEmailIntervalOutOfRange() {
        assertThatThrownBy(() -> properties(Duration.ofMillis(600)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email-interval");
        assertThatThrownBy(() -> properties(Duration.ofHours(25)))
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

    /**
     * {@code AuthRateLimiter}가 창을 초 단위로 카운터 키에 담으므로({@code auth:mail:ip:<hash>:<초>}) {@code 1h}와
     * {@code 3600}은 같은 키다. 그 설정은 한 번의 요청이 같은 카운터를 두 번 올려 실효 한도를 절반으로 떨어뜨리는데, 기동도 성공하고 로그도 남지
     * 않는다(S15P21E106-287 리뷰 B).
     */
    @Test
    @DisplayName("같은 창을 두 번 넣으면 만들 수 없다")
    void rejectsDuplicatedWindow() {
        assertThatThrownBy(
                        () ->
                                new MailRateLimitProperties(
                                        Duration.ofMinutes(1),
                                        List.of(
                                                new Quota(30, ONE_HOUR),
                                                new Quota(10, Duration.ofSeconds(3600)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.mail.rate-limit.ip")
                .hasMessageContaining("3600");
    }

    /** 앞 창에서 막히면 뒤 창의 카운터를 올리지 않으므로 목록 순서가 정책을 바꾼다. 어떤 순서로 적어도 짧은 창이 먼저 평가돼야 한다. */
    @Test
    @DisplayName("창은 어떤 순서로 적어도 짧은 창이 앞에 온다")
    void quotasAreSortedByWindowLength() {
        Quota hourly = new Quota(30, ONE_HOUR);
        Quota tenMinutes = new Quota(5, Duration.ofMinutes(10));

        MailRateLimitProperties properties =
                new MailRateLimitProperties(Duration.ofMinutes(1), List.of(hourly, tenMinutes));

        assertThat(properties.ip()).containsExactly(tenMinutes, hourly);
    }

    /** 목록 키만 있고 항목이 없는 설정({@code ip:})은 그 축만 제한하지 않는 상태이고, 기동을 막을 이유는 아니다. */
    @Test
    @DisplayName("IP 목록이 비어 있거나 없으면 빈 목록이 된다")
    void missingAxisBindsToEmptyList() {
        assertThat(new MailRateLimitProperties(Duration.ofMinutes(1), null).ip()).isEmpty();
        assertThat(new MailRateLimitProperties(Duration.ofMinutes(1), List.of()).ip()).isEmpty();
    }

    @Test
    @DisplayName("정상 값은 그대로 유지된다")
    void validValuesArePreserved() {
        MailRateLimitProperties properties =
                new MailRateLimitProperties(
                        Duration.ofMinutes(1), List.of(new Quota(30, ONE_HOUR)));

        assertThat(properties.emailInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.ip()).containsExactly(new Quota(30, ONE_HOUR));
    }

    private MailRateLimitProperties properties(Duration emailInterval) {
        return new MailRateLimitProperties(emailInterval, List.of(new Quota(10, ONE_HOUR)));
    }
}
