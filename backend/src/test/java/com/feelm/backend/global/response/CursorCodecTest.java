package com.feelm.backend.global.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 커서 해독 공통 규칙. 클라이언트가 고쳐 보낸 값이 500이 되지 않는지 본다. */
@DisplayName("커서 인코딩·해독 공통 규칙")
class CursorCodecTest {

    private record TimeCursor(Instant createdAt, long id) {}

    private record TextCursor(String head, long id) {}

    private static TimeCursor decodeTime(String encoded) {
        return CursorCodec.decodeInstant(encoded, TimeCursor::new);
    }

    private static TextCursor decodeText(String encoded) {
        return CursorCodec.decode(encoded, TextCursor::new);
    }

    @Test
    @DisplayName("T1 시각과 식별자를 인코딩한 뒤 해독하면 같은 값으로 돌아온다")
    void roundTripsInstantAndId() {
        Instant createdAt = Instant.parse("2026-09-13T01:02:03Z");

        String encoded = CursorCodec.encode(createdAt, 2048);

        // 인코딩 형식 자체를 고정한다. 왕복만 보면 구분자나 패딩을 바꿔도 테스트가 통과하고,
        // 형식이 이 클래스 하나로 모인 지금은 그 변경이 이미 발급된 모든 도메인의 커서를
        // 한꺼번에 무효화한다.
        assertThat(encoded).isEqualTo("MjAyNi0wOS0xM1QwMTowMjowM1o6MjA0OA");
        assertThat(decodeTime(encoded)).isEqualTo(new TimeCursor(createdAt, 2048));
    }

    @Test
    @DisplayName("T2 문자열과 식별자를 인코딩한 뒤 해독하면 같은 값으로 돌아온다")
    void roundTripsTextAndId() {
        TextCursor decoded = decodeText(CursorCodec.encode("영화광", 7));

        assertThat(decoded).isEqualTo(new TextCursor("영화광", 7));
    }

    @Test
    @DisplayName("T3 길이 상한을 넘는 커서는 해독하기 전에 VALIDATION_FAILED다")
    void rejectsTooLongInput() {
        // 해독에도 성공하고 형식도 맞는 값이라야 상한이 실제로 동작하는지 알 수 있다.
        // 형식이 깨진 긴 값은 상한이 없어도 base64 해독이나 구분자 검사에서 걸린다.
        String longButWellFormed = forged("x".repeat(200) + ":1");

        assertThat(longButWellFormed.length()).isGreaterThan(128);
        assertRejected(longButWellFormed);
    }

    @Test
    @DisplayName("T4 base64url이 아닌 커서는 VALIDATION_FAILED다")
    void rejectsNonBase64() {
        assertRejected("not-base64!!");
    }

    @Test
    @DisplayName("T5 형식이 깨진 커서는 VALIDATION_FAILED다")
    void rejectsMalformedPayload() {
        String[] broken = {
            "",
            forged("head-without-separator"),
            forged(":1"),
            forged("head:"),
            forged("head:0"),
            forged("head:-1"),
            forged("head:abc")
        };

        for (String cursor : broken) {
            assertRejected(cursor);
        }
    }

    @Test
    @DisplayName("T6 저장할 수 없는 시각을 담은 커서는 VALIDATION_FAILED다")
    void rejectsUnstorableInstant() {
        String[] outOfRange = {
            forged("+999999999-12-31T23:59:59Z:1"),
            forged("+0500000-01-01T00:00:00Z:1"),
            forged("1900-01-01T00:00:00Z:1"),
            forged("not-a-time:1")
        };

        for (String cursor : outOfRange) {
            assertThatThrownBy(() -> decodeTime(cursor))
                    .isInstanceOf(BusinessException.class)
                    .extracting(exception -> ((BusinessException) exception).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
    }

    @Test
    @DisplayName("T7 도메인 규칙 위반도 VALIDATION_FAILED로 모은다")
    void wrapsMapperRejection() {
        assertThatThrownBy(
                        () ->
                                CursorCodec.decode(
                                        forged("head:1"),
                                        (head, id) -> {
                                            throw new IllegalArgumentException("domain rule");
                                        }))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("T8 커서가 없으면 첫 페이지를 뜻하는 null이다")
    void returnsNullForAbsentCursor() {
        assertThat(decodeText(null)).isNull();
    }

    private static void assertRejected(String cursor) {
        assertThatThrownBy(() -> decodeText(cursor))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    /** 서버가 만들지 않은 커서를 흉내낸다. 인코딩 형식만 맞추고 값은 임의로 넣는다. */
    private static String forged(String raw) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
