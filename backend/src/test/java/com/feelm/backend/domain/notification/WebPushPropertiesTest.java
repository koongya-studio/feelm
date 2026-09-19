package com.feelm.backend.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feelm.backend.domain.notification.config.WebPushProperties;
import com.feelm.backend.domain.notification.config.WebPushProperties.Mode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebPushPropertiesTest {

    @Test
    void rejectsIncompleteVapidConfiguration() {
        assertThatThrownBy(() -> properties(Mode.VAPID, null, "private", "mailto:a@b.test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(Mode.VAPID, "public", null, "mailto:a@b.test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(Mode.VAPID, "public", "private", "ftp://bad"))
                .isInstanceOf(IllegalArgumentException.class);

        WebPushProperties disabled = properties(Mode.DISABLED, null, null, null);
        assertThat(disabled.enabled()).isFalse();
    }

    private static WebPushProperties properties(
            Mode mode, String publicKey, String privateKey, String subject) {
        return new WebPushProperties(
                mode,
                publicKey,
                privateKey,
                subject,
                List.of("fcm.googleapis.com"),
                25,
                5,
                Duration.ofMinutes(1),
                Duration.ofHours(1),
                Duration.ofMinutes(2),
                Duration.ofSeconds(15));
    }
}
