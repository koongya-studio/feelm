package com.feelm.backend.domain.notification.push;

public interface WebPushGateway {

    WebPushSendResult send(WebPushMessage message);
}
