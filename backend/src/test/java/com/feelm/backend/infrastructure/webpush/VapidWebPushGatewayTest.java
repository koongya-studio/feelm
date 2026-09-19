package com.feelm.backend.infrastructure.webpush;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.domain.notification.push.WebPushMessage;
import com.feelm.backend.domain.notification.push.WebPushSendResult;
import com.interaso.webpush.VapidKeys;
import com.interaso.webpush.WebPush;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VapidWebPushGatewayTest {

    private final AtomicInteger responseStatus = new AtomicInteger(201);
    private HttpServer server;
    private VapidWebPushGateway gateway;
    private String subscriptionPublicKey;

    @BeforeEach
    void setUp() throws Exception {
        VapidKeys vapidKeys = VapidKeys.generate();
        VapidKeys subscriptionKeys = VapidKeys.generate();
        subscriptionPublicKey =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(subscriptionKeys.getApplicationServerKey());
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/push",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(responseStatus.get(), -1);
                    exchange.close();
                });
        server.start();
        gateway =
                new VapidWebPushGateway(
                        new WebPush("mailto:test@feelm.local", vapidKeys),
                        HttpClient.newHttpClient(),
                        Duration.ofSeconds(5),
                        ignored -> true);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void encryptsAndSendsThePayloadWithJdkHttpClient() {
        WebPushSendResult result = gateway.send(message());

        assertThat(result.kind()).isEqualTo(WebPushSendResult.Kind.SENT);
        assertThat(result.httpStatus()).isEqualTo(201);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 410})
    void classifiesMissingOrGoneSubscriptionAsExpired(int status) {
        responseStatus.set(status);

        WebPushSendResult result = gateway.send(message());

        assertThat(result.kind()).isEqualTo(WebPushSendResult.Kind.EXPIRED);
        assertThat(result.httpStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 425, 429, 500, 503})
    void classifiesTransientStatusForRetry(int status) {
        responseStatus.set(status);

        WebPushSendResult result = gateway.send(message());

        assertThat(result.kind()).isEqualTo(WebPushSendResult.Kind.RETRYABLE_FAILURE);
        assertThat(result.httpStatus()).isEqualTo(status);
    }

    @Test
    void classifiesNetworkFailureForRetry() {
        WebPushMessage message = message();
        server.stop(0);

        WebPushSendResult result = gateway.send(message);

        assertThat(result.kind()).isEqualTo(WebPushSendResult.Kind.RETRYABLE_FAILURE);
        assertThat(result.httpStatus()).isNull();
    }

    @Test
    void rejectsAnEndpointThatFailsTheEgressAllowlist() {
        VapidKeys keys = VapidKeys.generate();
        gateway =
                new VapidWebPushGateway(
                        new WebPush("mailto:test@feelm.local", keys),
                        HttpClient.newHttpClient(),
                        Duration.ofSeconds(5),
                        ignored -> false);

        WebPushSendResult result = gateway.send(message());

        assertThat(result.kind()).isEqualTo(WebPushSendResult.Kind.PERMANENT_FAILURE);
        assertThat(result.failureCode()).isEqualTo("UNTRUSTED_ENDPOINT");
    }

    private WebPushMessage message() {
        String endpoint =
                "http://"
                        + server.getAddress().getHostString()
                        + ":"
                        + server.getAddress().getPort()
                        + "/push";
        return new WebPushMessage(
                1,
                2,
                endpoint,
                subscriptionPublicKey,
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]),
                "{\"title\":\"테스트\"}",
                Instant.now().plusSeconds(60));
    }
}
