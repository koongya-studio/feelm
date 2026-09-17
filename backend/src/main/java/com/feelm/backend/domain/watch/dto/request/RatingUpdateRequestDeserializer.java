package com.feelm.backend.domain.watch.dto.request;

import com.feelm.backend.domain.watch.model.RatingVisibility;
import java.math.BigDecimal;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/** API-037의 선택적 spoiler에서 필드 생략과 명시적 null을 구분한다. */
public final class RatingUpdateRequestDeserializer extends ValueDeserializer<RatingUpdateRequest> {

    @Override
    public RatingUpdateRequest deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            return context.reportInputMismatch(
                    RatingUpdateRequest.class, "평가 수정 요청 본문은 JSON 객체여야 합니다.");
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
                        RatingUpdateRequest.class, "평가 수정 요청 본문이 완전하지 않습니다.");
            }
            switch (field) {
                case "score" -> {
                    if (token == JsonToken.VALUE_NUMBER_INT
                            || token == JsonToken.VALUE_NUMBER_FLOAT) {
                        score = parser.getDecimalValue();
                    } else {
                        return context.reportInputMismatch(
                                RatingUpdateRequest.class, "score는 JSON 숫자여야 합니다.");
                    }
                }
                case "comment" -> {
                    if (token == JsonToken.VALUE_NULL) {
                        comment = null;
                    } else if (token == JsonToken.VALUE_STRING) {
                        comment = parser.getText();
                    } else {
                        return context.reportInputMismatch(
                                RatingUpdateRequest.class, "comment는 JSON 문자열이어야 합니다.");
                    }
                }
                case "visibility" -> {
                    if (token == JsonToken.VALUE_NULL) {
                        visibility = null;
                    } else if (token == JsonToken.VALUE_STRING) {
                        visibility = read(context, parser, RatingVisibility.class);
                    } else {
                        return context.reportInputMismatch(
                                RatingUpdateRequest.class, "visibility는 문자열이어야 합니다.");
                    }
                }
                case "spoiler" -> {
                    if (token != JsonToken.VALUE_TRUE && token != JsonToken.VALUE_FALSE) {
                        return context.reportInputMismatch(
                                RatingUpdateRequest.class, "spoiler는 true 또는 false여야 합니다.");
                    }
                    spoiler = token == JsonToken.VALUE_TRUE;
                }
                default -> parser.skipChildren();
            }
        }
        if (token != JsonToken.END_OBJECT) {
            return context.reportInputMismatch(
                    RatingUpdateRequest.class, "평가 수정 요청 본문이 완전하지 않습니다.");
        }
        return new RatingUpdateRequest(score, comment, visibility, spoiler);
    }

    private <T> T read(DeserializationContext context, JsonParser parser, Class<T> type) {
        JsonNode value = context.readTree(parser);
        return value.isNull() ? null : context.readTreeAsValue(value, type);
    }
}
