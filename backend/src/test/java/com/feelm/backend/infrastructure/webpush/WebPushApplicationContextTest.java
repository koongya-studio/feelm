package com.feelm.backend.infrastructure.webpush;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.domain.notification.push.WebPushGateway;
import com.feelm.backend.domain.notification.service.WebPushDeliveryWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WebPushApplicationContextTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(WebPushConfig.class, WebPushDeliveryWorker.class);

    @Test
    void disabledModeDoesNotCreateGatewayOrWorker() {
        contextRunner
                .withPropertyValues("feelm.notification.web-push.mode=disabled")
                .run(
                        context ->
                                assertThat(context)
                                        .doesNotHaveBean(WebPushGateway.class)
                                        .doesNotHaveBean(WebPushDeliveryWorker.class));
    }

    @Test
    void incompleteVapidConfigurationFailsApplicationContext() {
        new ApplicationContextRunner()
                .withUserConfiguration(WebPushConfig.class)
                .withPropertyValues(
                        "feelm.notification.web-push.mode=vapid",
                        "feelm.notification.web-push.public-key=public-only")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
                        });
    }
}
