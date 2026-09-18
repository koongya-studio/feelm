package com.feelm.backend.infrastructure.webpush;

import com.feelm.backend.domain.notification.push.WebPushGateway;
import com.feelm.backend.domain.notification.push.WebPushMessage;
import com.feelm.backend.domain.notification.push.WebPushSendResult;
import com.interaso.webpush.WebPush;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.function.Predicate;

final class VapidWebPushGateway implements WebPushGateway {

    private static final int MAX_TTL_SECONDS = 86_400;

    private final WebPush webPush;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final Predicate<URI> endpointAllowed;

    VapidWebPushGateway(
            WebPush webPush,
            HttpClient httpClient,
            Duration requestTimeout,
            Predicate<URI> endpointAllowed) {
        this.webPush = webPush;
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
        this.endpointAllowed = endpointAllowed;
    }

    @Override
    public WebPushSendResult send(WebPushMessage message) {
        try {
            URI endpoint = URI.create(message.endpoint());
            if (!endpointAllowed.test(endpoint)) {
                return WebPushSendResult.permanent(null, "UNTRUSTED_ENDPOINT");
            }
            int ttl = ttl(message.expiresAt());
            byte[] body =
                    webPush.getBody(
                            message.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            decode(message.p256dh()),
                            decode(message.auth()));
            HttpRequest.Builder request =
                    HttpRequest.newBuilder(endpoint)
                            .timeout(requestTimeout)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            webPush.getHeaders(
                            message.endpoint(),
                            ttl,
                            "n" + message.notificationId(),
                            WebPush.Urgency.Normal)
                    .forEach(request::header);
            int status =
                    httpClient
                            .send(request.build(), HttpResponse.BodyHandlers.discarding())
                            .statusCode();
            return classify(status);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return WebPushSendResult.retryable(null, "INTERRUPTED");
        } catch (IOException exception) {
            return WebPushSendResult.retryable(null, "NETWORK_ERROR");
        } catch (RuntimeException exception) {
            return WebPushSendResult.permanent(null, "INVALID_SUBSCRIPTION_MATERIAL");
        }
    }

    private static WebPushSendResult classify(int status) {
        if (status >= 200 && status < 300) {
            return WebPushSendResult.sent(status);
        }
        if (status == 404 || status == 410) {
            return WebPushSendResult.expired(status);
        }
        if (status == 408 || status == 425 || status == 429 || status >= 500) {
            return WebPushSendResult.retryable(status, "PUSH_SERVICE_TRANSIENT");
        }
        return WebPushSendResult.permanent(status, "PUSH_SERVICE_REJECTED");
    }

    private static int ttl(Instant expiresAt) {
        long seconds = ChronoUnit.SECONDS.between(Instant.now(), expiresAt);
        return (int) Math.max(0, Math.min(seconds, MAX_TTL_SECONDS));
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
