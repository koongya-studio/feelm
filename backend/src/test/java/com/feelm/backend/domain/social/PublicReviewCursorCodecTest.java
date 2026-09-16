package com.feelm.backend.domain.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feelm.backend.domain.social.cursor.PublicReviewCursor;
import com.feelm.backend.domain.social.dto.request.PublicReviewCriteria;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("공개 한줄평 cursor codec")
class PublicReviewCursorCodecTest {

    private static final long MOVIE_ID = 161L;
    private static final long USER_ID = 1_061L;

    @ParameterizedTest
    @ValueSource(strings = {"not-base64!!", "eA", "", "ICAg", "eyJ2IjozfQ"})
    @DisplayName("T16 malformed cursor를 공통 validation 오류로 거절한다")
    void rejectsMalformedCursor(String cursor) {
        assertValidationFailed(() -> PublicReviewCursor.decode(cursor, MOVIE_ID, USER_ID));
    }

    @Test
    @DisplayName("T17 필수 구조가 깨진 cursor 변조를 거절한다")
    void rejectsTamperedCursor() {
        PublicReviewCursor original =
                new PublicReviewCursor(Instant.parse("2026-09-16T01:02:03.123456Z"), 91L);
        String encoded = original.encode(MOVIE_ID, USER_ID);
        String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        String tampered =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                decoded.replaceFirst("1\\|", "9|")
                                        .getBytes(StandardCharsets.UTF_8));

        assertValidationFailed(() -> PublicReviewCursor.decode(tampered, MOVIE_ID, USER_ID));
    }

    @Test
    @DisplayName("T18 다른 영화에서 발급한 cursor를 거절한다")
    void rejectsCursorIssuedForAnotherMovie() {
        String cursor =
                new PublicReviewCursor(Instant.parse("2026-09-16T01:02:03Z"), 92L)
                        .encode(MOVIE_ID, USER_ID);

        assertValidationFailed(() -> PublicReviewCursor.decode(cursor, MOVIE_ID + 1, USER_ID));
    }

    @Test
    @DisplayName("T19 다른 사용자에게 발급한 cursor를 거절한다")
    void rejectsCursorIssuedForAnotherViewer() {
        String cursor =
                new PublicReviewCursor(Instant.parse("2026-09-16T01:02:03Z"), 93L)
                        .encode(MOVIE_ID, USER_ID);

        assertValidationFailed(() -> PublicReviewCursor.decode(cursor, MOVIE_ID, USER_ID + 1));
    }

    @Test
    @DisplayName("T20 같은 cursor를 다른 page size와 사용할 수 있다")
    void allowsDifferentSizeWithSameCursor() {
        PublicReviewCursor expected =
                new PublicReviewCursor(Instant.parse("2026-09-16T01:02:03.654321Z"), 94L);
        String encoded = expected.encode(MOVIE_ID, USER_ID);
        PublicReviewCriteria first = PublicReviewCriteria.from(encoded, "1");
        PublicReviewCriteria second = PublicReviewCriteria.from(encoded, "50");

        assertThat(PublicReviewCursor.decode(first.cursor(), MOVIE_ID, USER_ID))
                .isEqualTo(expected);
        assertThat(PublicReviewCursor.decode(second.cursor(), MOVIE_ID, USER_ID))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("T22 PostgreSQL microsecond 시각을 cursor 왕복에서 보존한다")
    void roundTripsPostgresMicrosecondTimestamp() {
        Instant postgresTime = Instant.parse("2026-09-16T01:02:03.123456Z");
        PublicReviewCursor cursor = new PublicReviewCursor(postgresTime, Long.MAX_VALUE);

        PublicReviewCursor decoded =
                PublicReviewCursor.decode(cursor.encode(MOVIE_ID, USER_ID), MOVIE_ID, USER_ID);

        assertThat(decoded).isEqualTo(cursor);
        assertThat(decoded.createdAt().getNano()).isEqualTo(123_456_000);
    }

    private static void assertValidationFailed(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        error ->
                                assertThat(((BusinessException) error).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
