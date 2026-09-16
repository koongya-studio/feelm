package com.feelm.backend.domain.watch.dto.request;

import com.feelm.backend.domain.watch.model.RatingVisibility;
import java.math.BigDecimal;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/** API-036의 선택적 spoiler에서 필드 생략과 명시적 null을 구분한다. */
public final class RatingCreateRequestDeserializer extends ValueDeserializer<RatingCreateRequest> {

    @Override
    public RatingCreateRequest deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            return context.reportInputMismatch(
                    RatingCreateRequest.class, "평가 생성 요청 본문은 JSON 객체여야 합니다.");
        }

        BigDecimal score = null;
        String comment = null;
        RatingVisibility visibility = null;
        boolean spoiler = false;
        JsonToken token;
        while ((token = parser.nextToken()) == JsonToken.PROPERTY_NAME) {
            String field = parser.currentName();
            token = parser.nextToken();
            if (token == null) {
                return context.reportInputMismatch(
                        RatingCreateRequest.class, "평가 생성 요청 본문이 완전하지 않습니다.");
            }
            switch (field) {
                case "score" -> {
                    if (token == JsonToken.VALUE_NUMBER_INT
                            || token == JsonToken.VALUE_NUMBER_FLOAT) {
                        score = parser.getDecimalValue();
                    } else {
                        score = read(context, parser, BigDecimal.class);
                    }
                }
                case "comment" -> comment = read(context, parser, String.class);
                case "visibility" -> visibility = read(context, parser, RatingVisibility.class);
                case "spoiler" -> {
                    if (token != JsonToken.VALUE_TRUE && token != JsonToken.VALUE_FALSE) {
                        return context.reportInputMismatch(
                                RatingCreateRequest.class, "spoiler는 true 또는 false여야 합니다.");
                    }
                    spoiler = token == JsonToken.VALUE_TRUE;
                }
                default -> parser.skipChildren();
            }
        }
        if (token != JsonToken.END_OBJECT) {
            return context.reportInputMismatch(
                    RatingCreateRequest.class, "평가 생성 요청 본문이 완전하지 않습니다.");
        }
        return new RatingCreateRequest(score, comment, visibility, spoiler);
    }

    private <T> T read(DeserializationContext context, JsonParser parser, Class<T> type) {
        JsonNode value = context.readTree(parser);
        return value.isNull() ? null : context.readTreeAsValue(value, type);
    }
}
