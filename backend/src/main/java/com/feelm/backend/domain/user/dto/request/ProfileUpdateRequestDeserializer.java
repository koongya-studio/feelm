package com.feelm.backend.domain.user.dto.request;

import com.feelm.backend.domain.user.entity.Gender;
import com.feelm.backend.domain.user.entity.Visibility;
import java.time.LocalDate;
import java.util.List;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * {@link ProfileUpdateRequest}를 읽으면서 명시적 {@code null}을 거절한다.
 *
 * <p>API-015 처리 규칙이 "{@code nickname}·{@code birthDate}·{@code gender}는 가입 시 필수값이므로 {@code null}로
 * 되돌릴 수 없다. {@code null}을 보내면 {@code 400 VALIDATION_FAILED}"라고 정한다. 그러려면 <b>필드 부재</b>(변경 없음)와
 * <b>명시적 null</b>(잘못된 요청)을 구분해야 하는데, record에 값을 바인딩하고 나면 둘 다 {@code null}이라 구분이 사라진다. 그래서 바인딩 전에 원본
 * JSON을 본다.
 *
 * <p>{@code visibility}는 계약의 문장에 없지만 같은 규칙을 적용한다. 기본값이 있는 NOT NULL 컬럼이라 비우는 요청이 성립하지 않는 것은 나머지 셋과
 * 같고, 필드마다 null의 의미가 달라지면 클라이언트가 규칙을 외워야 한다.
 *
 * <p>Jackson의 {@code @JsonSetter(nulls = Nulls.FAIL)}로 대신할 수 없다. 그 설정은 부재 값도 같은 provider로 처리해 아무
 * 필드도 보내지 않은 요청까지 거절한다(부분 수정이 불가능해진다).
 */
public class ProfileUpdateRequestDeserializer extends ValueDeserializer<ProfileUpdateRequest> {

    private static final List<String> NON_NULLABLE_FIELDS =
            List.of("nickname", "birthDate", "gender", "visibility");

    @Override
    public ProfileUpdateRequest deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode body = context.readTree(parser);
        rejectNonObject(context, body);
        rejectExplicitNulls(context, body);
        return new ProfileUpdateRequest(
                read(context, body, "nickname", String.class),
                read(context, body, "birthDate", LocalDate.class),
                read(context, body, "gender", Gender.class),
                read(context, body, "visibility", Visibility.class));
    }

    /**
     * 본문이 JSON 객체가 아니면 거절한다.
     *
     * <p>직접 트리를 읽으면서 record 기본 역직렬화가 해 주던 형태 검사가 사라졌다. 배열·문자열·숫자 본문에서는 {@code get(String)}이 모두
     * {@code null}이라 네 필드가 "부재"로 해석되어 200 무변경이 나갔다(리뷰 A-8·B-7, 종합 단계에서 실행 확인).
     */
    private void rejectNonObject(DeserializationContext context, JsonNode body) {
        if (!body.isObject()) {
            context.reportInputMismatch(ProfileUpdateRequest.class, "요청 본문은 JSON 객체여야 합니다.");
        }
    }

    private void rejectExplicitNulls(DeserializationContext context, JsonNode body) {
        for (String field : NON_NULLABLE_FIELDS) {
            JsonNode value = body.get(field);
            if (value != null && value.isNull()) {
                // 항상 예외를 던진다. Spring이 400 VALIDATION_FAILED로 옮긴다.
                context.reportInputMismatch(
                        ProfileUpdateRequest.class, "%s은(는) null로 되돌릴 수 없습니다.", field);
            }
        }
    }

    /**
     * 필드가 없으면 {@code null}(변경 없음)이다. 값이 있으면 평소의 변환 규칙을 그대로 쓴다.
     *
     * <p>공백만 있는 문자열까지 거절한다. Jackson이 날짜·enum의 빈 문자열을 {@code null}로 강제 변환해, 명시적 {@code null}은 400인데
     * 빈 문자열은 조용히 무시되는 비대칭이 생긴다(종합 리뷰 3장 추가 발견). 변환 전에 값을 {@code trim}하므로 공백만 있는 문자열도 같은 경로를 타며, 그래서
     * {@code isEmpty}가 아니라 {@code isBlank}로 본다(MR !173 리뷰).
     */
    private <T> T read(DeserializationContext context, JsonNode body, String field, Class<T> type) {
        JsonNode value = body.get(field);
        if (value == null) {
            return null;
        }
        if (value.isString() && value.stringValue().isBlank()) {
            context.reportInputMismatch(
                    ProfileUpdateRequest.class, "%s에 빈 문자열이나 공백만 있는 값을 보낼 수 없습니다.", field);
        }
        return context.readTreeAsValue(value, type);
    }
}
