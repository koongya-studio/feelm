package com.feelm.backend.domain.watch.dto.request;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/** API-033의 ottId를 강제 형변환 없이 JSON 정수로만 읽는다. */
final class OttIdDeserializer extends ValueDeserializer<Long> {

    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode value = context.readTree(parser);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            return context.reportInputMismatch(Long.class, "ottId는 정수여야 합니다.");
        }
        return value.longValue();
    }
}
