package com.feelm.backend.domain.notification.service;

import com.feelm.backend.domain.notification.repository.WebPushDeliveryRepository.ClaimedDelivery;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class WebPushPayloadFactory {

    private final ObjectMapper objectMapper;

    public String create(ClaimedDelivery delivery) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of(
                            "notificationId", delivery.notificationId(),
                            "type", delivery.type().name(),
                            "title", delivery.title(),
                            "body", delivery.body(),
                            "targetType", delivery.targetType().name(),
                            "targetId", delivery.targetId()));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Web Push payload cannot be serialized", exception);
        }
    }
}
