package com.feelm.backend.infrastructure.webpush;

import com.feelm.backend.domain.notification.config.WebPushProperties;
import com.feelm.backend.domain.notification.push.WebPushGateway;
import com.interaso.webpush.VapidKeys;
import com.interaso.webpush.WebPush;
import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WebPushProperties.class)
class WebPushConfig {

    @Bean
    @ConditionalOnProperty(name = "feelm.notification.web-push.mode", havingValue = "vapid")
    WebPushGateway webPushGateway(WebPushProperties properties) {
        VapidKeys keys =
                VapidKeys.fromUncompressedBytes(properties.publicKey(), properties.privateKey());
        WebPush webPush = new WebPush(properties.subject(), keys);
        HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(properties.requestTimeout()).build();
        return new VapidWebPushGateway(
                webPush, httpClient, properties.requestTimeout(), properties::allows);
    }
}
