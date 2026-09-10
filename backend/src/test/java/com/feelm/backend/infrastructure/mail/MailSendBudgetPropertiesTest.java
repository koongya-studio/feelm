package com.feelm.backend.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feelm.backend.infrastructure.mail.MailSendBudgetProperties.Quota;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 전역 발송 상한 설정값의 검증을 확인한다(S15P21E106-287).
 *
 * <p>이 값이 잘못되면 요청 쪽에는 아무 신호도 남지 않는다. 상한은 발송 스레드에서만 보고 응답을 바꾸지 않으므로, {@code limit: 0}이나 창 표기 실수는
 * 500이 아니라 <b>메일이 조용히 사라지는 상태</b>가 된다. 설정 오타가 첫 발송이 아니라 기동에서 드러나는지 본다.
 *
 * <p>바인딩 자체가 아니라 record 생성만 검증한다. {@code @ConfigurationProperties} 바인딩은 이 생성자를 호출하므로 여기서 던지는 예외가 곧
 * 기동 실패다.
 */
@DisplayName("전역 발송 상한 설정값 — 검증")
class MailSendBudgetPropertiesTest {

    private static final Duration ONE_HOUR = Duration.ofHours(1);

    private static final Duration ONE_DAY = Duration.ofHours(24);

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
    @DisplayName("잘못된 값의 메시지가 전역 상한의 설정 키를 가리킨다")
    void messagesPointAtSendBudgetKeys() {
        assertThatThrownBy(() -> new Quota(0, ONE_HOUR))
                .as("검증 규칙이 공통 헬퍼로 옮겨진 뒤에도 메시지는 자기 설정 키를 가리켜야 한다")
                .hasMessageContaining("feelm.mail.send-budget.windows");
        assertThatThrownBy(() -> new Quota(100, null))
                .hasMessageContaining("feelm.mail.send-budget.windows의 window");
        assertThatThrownBy(() -> new Quota(100, Duration.ofMillis(600)))
                .hasMessageContaining("feelm.mail.send-budget.windows의 window");
    }

    @Test
    @DisplayName("창 길이가 없는 항목은 만들 수 없다")
    void quotaRejectsMissingWindow() {
        assertThatThrownBy(() -> new Quota(100, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
    }

    /**
     * 1초 미만은 500이 아니라 <b>상한의 조용한 무력화</b>다. 만료를 밀리초로 넘기므로 {@code PEXPIRE key 0}이 카운터를 곧바로 지우고,
     * Redis는 만료 0을 오류로 거부하지 않는다.
     */
    @Test
    @DisplayName("창 길이가 1초 미만이거나 24시간을 넘으면 만들 수 없다")
    void quotaRejectsWindowOutOfRange() {
        assertThatThrownBy(() -> new Quota(100, Duration.ofMillis(600)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
        assertThatThrownBy(() -> new Quota(100, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Quota(100, ONE_DAY.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 창을 초 단위로 카운터 키에 담으므로 {@code 1h}와 {@code 3600}은 같은 키다. 그 설정은 한 번의 발송이 같은 카운터를 두 번 올려 실효 상한을
     * 절반으로 떨어뜨리는데, 기동도 성공하고 로그도 남지 않는다.
     */
    @Test
    @DisplayName("같은 창을 두 번 넣으면 만들 수 없다")
    void rejectsDuplicatedWindow() {
        assertThatThrownBy(
                        () ->
                                new MailSendBudgetProperties(
                                        List.of(
                                                new Quota(100, ONE_HOUR),
                                                new Quota(300, Duration.ofSeconds(3600)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.mail.send-budget.windows")
                .hasMessageContaining("3600");
    }

    /** 앞 창에서 막히면 뒤 창의 카운터를 올리지 않으므로 목록 순서가 정책을 바꾼다. 어떤 순서로 적어도 짧은 창이 먼저 평가돼야 한다. */
    @Test
    @DisplayName("창은 어떤 순서로 적어도 짧은 창이 앞에 온다")
    void windowsAreSortedByLength() {
        MailSendBudgetProperties properties =
                new MailSendBudgetProperties(
                        List.of(new Quota(300, ONE_DAY), new Quota(100, ONE_HOUR)));

        assertThat(properties.windows())
                .containsExactly(new Quota(100, ONE_HOUR), new Quota(300, ONE_DAY));
    }

    /** 설정 블록이 없는 상태는 상한이 없다는 뜻이다. 기동을 막을 이유는 아니고, 기동 로그가 그 사실을 알린다. */
    @Test
    @DisplayName("창 목록이 비어 있거나 없으면 빈 목록이 된다")
    void missingWindowsBindToEmptyList() {
        assertThat(new MailSendBudgetProperties(null).windows()).isEmpty();
        assertThat(new MailSendBudgetProperties(List.of()).windows()).isEmpty();
    }
}
