package com.feelm.backend.domain.search;

import static com.feelm.backend.domain.search.SearchUnitTestReflection.invoke;
import static com.feelm.backend.domain.search.SearchUnitTestReflection.searchType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/** HTTP·DB·Spring context 없이 실제 codec의 encode/decode를 직접 호출한다. */
@DisplayName("MovieSearchCursorCodec — 정렬 cursor 왕복과 손상 값 거부")
class MovieSearchCursorCodecTest {

    private static final String CRITERIA_KEY = "a".repeat(64);

    private Object codec;
    private Class<?> cursorType;
    private Class<?> sortType;
    private Constructor<?> cursorConstructor;
    private Method encode;
    private Method decode;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        Class<?> codecType = searchType("cursor.MovieSearchCursorCodec");
        cursorType = searchType("cursor.MovieSearchCursor");
        sortType = searchType("dto.request.MovieSearchSort");
        codec = codecType.getConstructor(ObjectMapper.class).newInstance(new ObjectMapper());
        cursorConstructor =
                cursorType.getConstructor(
                        sortType, BigDecimal.class, LocalDate.class, long.class, String.class);
        encode = codecType.getMethod("encode", cursorType);
        decode = codecType.getMethod("decode", String.class);
    }

    @ParameterizedTest(name = "{0}: sortValue={1}, releaseDate={2}, movieId={3}")
    @MethodSource("cursorCases")
    @DisplayName("세 정렬의 값·날짜·NULL 날짜·64bit movieId가 encode/decode 후 보존된다")
    void roundTripsSortPosition(
            String sort, BigDecimal sortValue, LocalDate releaseDate, long movieId)
            throws Throwable {
        Object cursor = cursor(sort, sortValue, releaseDate, movieId);

        String encoded = (String) invoke(encode, codec, cursor);

        assertThat(encoded).isNotBlank().matches("[A-Za-z0-9_-]+=*");
        assertThat(Base64.getUrlDecoder().decode(encoded)).isNotEmpty();
        Object decoded = invoke(decode, codec, encoded);
        assertPosition(decoded, sort, sortValue, releaseDate, movieId);
    }

    static Stream<Arguments> cursorCases() {
        return Stream.of(
                Arguments.of(
                        "RELEVANCE", new BigDecimal("0.1234567890123456789"), null, Long.MAX_VALUE),
                Arguments.of("RELEVANCE", BigDecimal.ZERO, null, 1L),
                Arguments.of("RATING", new BigDecimal("4.30"), null, 550L),
                Arguments.of("RATING", new BigDecimal("4.30"), LocalDate.of(2026, 9, 22), 550L),
                Arguments.of("RATING", BigDecimal.ZERO, null, 1L),
                Arguments.of("RATING", new BigDecimal("5.00"), null, Long.MAX_VALUE),
                Arguments.of("RELEVANCE", new BigDecimal("1e-16383"), null, 1L),
                Arguments.of("RELEVANCE", new BigDecimal("1e131071"), null, 1L),
                Arguments.of("RELEASE_DATE", null, LocalDate.of(-4713, 11, 24), 1L),
                Arguments.of("RELEASE_DATE", null, LocalDate.of(5874897, 12, 31), 1L),
                Arguments.of("RELEASE_DATE", null, LocalDate.of(1999, 10, 15), 550L),
                Arguments.of("RELEASE_DATE", null, LocalDate.of(1895, 12, 28), 9007199254740993L),
                Arguments.of("RELEASE_DATE", null, null, Long.MAX_VALUE));
    }

    @ParameterizedTest
    @CsvSource({
        "RELEVANCE,1e-16384",
        "RELEVANCE,1e-1000000",
        "RELEVANCE,1e131072",
        "RELEVANCE,0e-1000000",
        "RATING,1e-16384",
        "RATING,1e-1000000"
    })
    @DisplayName("DB에서 표현할 수 없는 숫자는 정밀도를 줄여 수용하지 않고 거부한다")
    void rejectsUnrepresentableNumbersOnEncodeAndDecode(String sort, String number)
            throws Throwable {
        Object invalid = cursor(sort, new BigDecimal(number), null, 550L);
        assertThatThrownBy(() -> invoke(encode, codec, invalid))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        String issued = (String) invoke(encode, codec, cursor(sort, BigDecimal.ONE, null, 550L));
        String json = new String(Base64.getUrlDecoder().decode(issued), StandardCharsets.UTF_8);
        // JSON 숫자 그대로 바꿔 문자열 coercion이나 다른 필드 오류로 통과하지 않게 한다.
        String tampered = json.replace("\"sortValue\":1", "\"sortValue\":" + number);
        assertThat(tampered).isNotEqualTo(json);
        assertThatThrownBy(() -> invoke(decode, codec, encodeMalformedPayload(tampered)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"+6000000-01-01", "-5000-01-01", "+5874898-01-01", "-4713-11-23"})
    @DisplayName("DB에서 표현할 수 없는 날짜는 encode와 decode 모두 거부한다")
    void rejectsUnrepresentableDatesOnEncodeAndDecode(String date) throws Throwable {
        Object invalid = cursor("RELEASE_DATE", null, LocalDate.parse(date), 550L);
        assertThatThrownBy(() -> invoke(encode, codec, invalid))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        String issued =
                (String)
                        invoke(
                                encode,
                                codec,
                                cursor("RELEASE_DATE", null, LocalDate.of(2020, 1, 1), 550L));
        String json = new String(Base64.getUrlDecoder().decode(issued), StandardCharsets.UTF_8);
        String tampered =
                json.replace("\"releaseDate\":\"2020-01-01\"", "\"releaseDate\":\"" + date + "\"");
        assertThat(tampered).isNotEqualTo(json);
        assertThatThrownBy(() -> invoke(decode, codec, encodeMalformedPayload(tampered)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RELEVANCE", "RATING", "RELEASE_DATE"})
    @DisplayName("같은 정렬값의 서로 다른 movieId를 별도 cursor에 보존한다")
    void preservesTieBreakerAcrossMultipleCursors(String sort) throws Throwable {
        BigDecimal sortValue = "RELEASE_DATE".equals(sort) ? null : new BigDecimal("4.30");
        LocalDate releaseDate = "RELEASE_DATE".equals(sort) ? LocalDate.of(2020, 1, 1) : null;
        String first = (String) invoke(encode, codec, cursor(sort, sortValue, releaseDate, 551L));
        String second = (String) invoke(encode, codec, cursor(sort, sortValue, releaseDate, 550L));

        assertThat(first).isNotEqualTo(second);
        assertPosition(invoke(decode, codec, first), sort, sortValue, releaseDate, 551L);
        assertPosition(invoke(decode, codec, second), sort, sortValue, releaseDate, 550L);
    }

    @ParameterizedTest(name = "cursor={0}")
    @MethodSource("malformedCursors")
    @DisplayName("잘못된 Base64·다른 구조·누락 필드·알 수 없는 정렬은 VALIDATION_FAILED다")
    void rejectsMalformedCursor(String malformed) {
        assertThatThrownBy(() -> invoke(decode, codec, malformed))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    static Stream<String> malformedCursors() {
        return Stream.concat(
                Stream.of("%%%", "a", "not-json", "a+b/"),
                Stream.of(
                                "not a cursor",
                                "{}",
                                "[]",
                                "null",
                                "\"text\"",
                                "{\"sort\":\"UNKNOWN\",\"sortValue\":4.3,\"releaseDate\":null,\"movieId\":550}",
                                "{\"sort\":\"RATING\",\"sortValue\":4.3,\"releaseDate\":null}",
                                "{\"sort\":\"RATING\",\"sortValue\":4.3,\"releaseDate\":null,\"movieId\":[]}")
                        .map(
                                value ->
                                        value.startsWith("{\"sort\"")
                                                ? "{\"criteriaKey\":\""
                                                        + CRITERIA_KEY
                                                        + "\","
                                                        + value.substring(1)
                                                : value)
                        .map(MovieSearchCursorCodecTest::encodeMalformedPayload));
    }

    @Test
    @DisplayName("실제로 encode한 cursor가 한 글자로 잘리면 VALIDATION_FAILED로 거부한다")
    void rejectsTruncatedIssuedCursor() throws Throwable {
        String encoded =
                (String) invoke(encode, codec, cursor("RATING", new BigDecimal("4.3"), null, 550L));
        assertThat(encoded).hasSizeGreaterThan(1);
        String truncated = encoded.substring(0, 1);

        assertThatThrownBy(() -> invoke(decode, codec, truncated))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private Object cursor(String sort, BigDecimal sortValue, LocalDate releaseDate, long movieId)
            throws ReflectiveOperationException {
        Object enumValue =
                Arrays.stream(sortType.getEnumConstants())
                        .filter(value -> ((Enum<?>) value).name().equals(sort))
                        .findFirst()
                        .orElseThrow();
        return cursorConstructor.newInstance(
                enumValue, sortValue, releaseDate, movieId, CRITERIA_KEY);
    }

    private void assertPosition(
            Object decoded, String sort, BigDecimal sortValue, LocalDate releaseDate, long movieId)
            throws Throwable {
        assertThat(decoded).isInstanceOf(cursorType);
        assertThat(((Enum<?>) invoke(cursorType.getMethod("sort"), decoded)).name())
                .isEqualTo(sort);
        BigDecimal decodedValue = (BigDecimal) invoke(cursorType.getMethod("sortValue"), decoded);
        if (sortValue == null) {
            assertThat(decodedValue).isNull();
        } else {
            // 숫자의 의미와 정밀도는 보존하되 BigDecimal의 표시용 scale에는 의존하지 않는다.
            assertThat(decodedValue).isEqualByComparingTo(sortValue);
        }
        assertThat(invoke(cursorType.getMethod("releaseDate"), decoded)).isEqualTo(releaseDate);
        assertThat(invoke(cursorType.getMethod("movieId"), decoded)).isEqualTo(movieId);
        assertThat(invoke(cursorType.getMethod("criteriaKey"), decoded)).isEqualTo(CRITERIA_KEY);
    }

    private static String encodeMalformedPayload(String payload) {
        // 유효 cursor를 대신 발급하지 않는다. decoder에 줄 손상 입력만 Base64로 감싼다.
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }
}
