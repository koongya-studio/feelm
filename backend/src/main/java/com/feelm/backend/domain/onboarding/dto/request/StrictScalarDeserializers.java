package com.feelm.backend.domain.onboarding.dto.request;

import com.feelm.backend.domain.onboarding.entity.PreferenceChoice;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * 초기 취향 제출(API-024) 요청 본문의 스칼라를 계약에 적힌 JSON 타입으로만 읽는다.
 *
 * <p>Jackson의 기본 정책은 스칼라를 너그럽게 강제 변환한다. 그대로 두면 {@code preference: 0}과 {@code preference: "0"}이 enum
 * 선언 순서로 읽혀 {@code LIKE}가 되고, {@code movieId: 101.9}는 소수점 아래가 잘려 101이 되며, {@code skipped: 1}은
 * {@code true}가 된다. 어느 쪽도 클라이언트가 보낸 값과 서버가 저장한 값이 다른데 응답은 200이라, 사용자는 자기가 고르지 않은 영화에 좋아요가 달린 것을
 * 나중에야 본다(MR !214 리뷰 1).
 *
 * <p>공유 {@code ObjectMapper}의 강제 변환 정책은 바꾸지 않는다. 그 정책을 바꾸면 모든 엔드포인트의 입력 계약이 한꺼번에 바뀐다. 검색 cursor도 같은
 * 이유로 공유 mapper를 두고 자기 자리에서만 엄격하게 읽는다({@code MovieSearchCursorCodec}).
 *
 * <p>거절은 본문 파싱 단계의 예외이므로 {@code GlobalExceptionHandler}가 {@code 400 VALIDATION_FAILED}로 옮긴다. 값이 없거나
 * {@code null}이면 이 자리를 거치지 않고 {@code @NotNull}이 판정한다.
 */
public final class StrictScalarDeserializers {

    private StrictScalarDeserializers() {}

    /** {@code movieId}: JSON 정수만 받는다. 소수·지수 표기와 숫자 문자열은 거절한다. */
    public static final class MovieIdDeserializer extends ValueDeserializer<Long> {

        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode value = context.readTree(parser);
            if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                return context.reportInputMismatch(Long.class, "movieId는 정수여야 합니다.");
            }
            return value.longValue();
        }
    }

    /** {@code preference}: 정의된 값의 이름을 담은 문자열만 받는다. */
    public static final class PreferenceDeserializer extends ValueDeserializer<PreferenceChoice> {

        @Override
        public PreferenceChoice deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode value = context.readTree(parser);
            if (!value.isString()) {
                return context.reportInputMismatch(
                        PreferenceChoice.class, "preference는 LIKE 또는 DISLIKE여야 합니다.");
            }
            try {
                return PreferenceChoice.valueOf(value.stringValue());
            } catch (IllegalArgumentException exception) {
                return context.reportInputMismatch(
                        PreferenceChoice.class, "preference는 LIKE 또는 DISLIKE여야 합니다.");
            }
        }
    }

    /** {@code skipped}: JSON boolean만 받는다. 숫자와 문자열은 거절한다. */
    public static final class SkippedDeserializer extends ValueDeserializer<Boolean> {

        @Override
        public Boolean deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode value = context.readTree(parser);
            if (!value.isBoolean()) {
                return context.reportInputMismatch(Boolean.class, "skipped는 true 또는 false여야 합니다.");
            }
            return value.booleanValue();
        }
    }
}
