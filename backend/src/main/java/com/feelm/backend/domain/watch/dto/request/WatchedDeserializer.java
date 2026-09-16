package com.feelm.backend.domain.watch.dto.request;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/** API-035의 watched를 JSON boolean으로만 읽는다. */
public final class WatchedDeserializer extends ValueDeserializer<Boolean> {

    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode value = context.readTree(parser);
        if (!value.isBoolean()) {
            return context.reportInputMismatch(Boolean.class, "watched는 true 또는 false여야 합니다.");
        }
        return value.booleanValue();
    }
}
