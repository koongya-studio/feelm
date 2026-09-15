package com.feelm.backend.domain.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feelm.backend.domain.search.cursor.MovieSearchCursor;
import com.feelm.backend.domain.search.cursor.MovieSearchCursorCodec;
import com.feelm.backend.domain.search.dto.request.MovieSearchSort;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class MovieSearchCursorValidationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final MovieSearchCursorCodec codec = new MovieSearchCursorCodec(mapper);

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidScalarTypes")
    void rejectsCoercionOfIssuedCursor(String field, String json) {
        ObjectNode payload = issuedPayload();
        payload.set(field, mapper.readTree(json));
        assertInvalid(payload);
    }

    static Stream<Arguments> invalidScalarTypes() {
        return Stream.of(
                Arguments.of("sort", "0"),
                Arguments.of("sort", "1"),
                Arguments.of("sort", "\"0\""),
                Arguments.of("sort", "\"1\""),
                Arguments.of("sortValue", "\"4.30\""),
                Arguments.of("sortValue", "\"\""),
                Arguments.of("sortValue", "true"),
                Arguments.of("movieId", "\"550\""),
                Arguments.of("movieId", "550.5"),
                Arguments.of("movieId", "true"));
    }

    @Test
    void rejectsCursorWithoutSearchConditions() {
        ObjectNode payload = issuedPayload();
        payload.remove("criteriaKey");
        assertInvalid(payload);
    }

    @ParameterizedTest
    @MethodSource("invalidKeys")
    void rejectsMalformedConditionKey(String json) {
        ObjectNode payload = issuedPayload();
        payload.set("criteriaKey", mapper.readTree(json));
        assertInvalid(payload);
    }

    static Stream<String> invalidKeys() {
        return Stream.of("null", "0", "\"\"", "\"not-a-condition-key\"");
    }

    @Test
    void doesNotChangeSharedMapperCoercionPolicy() {
        boolean before = mapper.isEnabled(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        new MovieSearchCursorCodec(mapper);
        assertThat(mapper.isEnabled(MapperFeature.ALLOW_COERCION_OF_SCALARS)).isEqualTo(before);
        assertThat(mapper.readValue("\"550\"", Long.class)).isEqualTo(550L);
    }

    private ObjectNode issuedPayload() {
        String encoded =
                codec.encode(
                        new MovieSearchCursor(
                                MovieSearchSort.RATING,
                                new BigDecimal("4.30"),
                                null,
                                550L,
                                "a".repeat(64)));
        // 먼저 발급된 정상 cursor가 유효함을 확인하여 다른 오류로 인한 거짓 통과를 막는다.
        assertThat(codec.decode(encoded).movieId()).isEqualTo(550L);
        return (ObjectNode) mapper.readTree(Base64.getUrlDecoder().decode(encoded));
    }

    private void assertInvalid(ObjectNode payload) {
        String encoded =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(mapper.writeValueAsBytes(payload));
        assertThatThrownBy(() -> codec.decode(encoded))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
