package com.feelm.backend.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** API-019 검색 커서 해독. 클라이언트가 고쳐 보낸 값이 500이 되지 않는지 본다. */
@DisplayName("API-019 사용자 검색 커서")
class UserSearchCursorTest {

    @Test
    @DisplayName("T24 정렬 키를 인코딩한 뒤 해독하면 같은 값으로 돌아온다")
    void roundTripsSortKey() {
        UserSearchCursor cursor = new UserSearchCursor("영화광", 2048);

        UserSearchCursor decoded = UserSearchCursor.decode(cursor.encode());

        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    @DisplayName("T25 형식이 깨진 커서는 VALIDATION_FAILED다")
    void rejectsBrokenValues() {
        String[] broken = {
            "not-base64!!",
            "",
            forged("nickname-without-separator"),
            forged("nickname:not-a-number"),
            forged("nickname:0"),
            forged("nickname:-1"),
            forged(":1"),
            forged("nickname:"),
            forged("n".repeat(21) + ":1"),
            forged("m\0o:1"),
            forged("mo\7:1"),
            "A".repeat(129)
        };

        for (String cursor : broken) {
            assertThatThrownBy(() -> UserSearchCursor.decode(cursor))
                    .isInstanceOf(BusinessException.class)
                    .extracting(exception -> ((BusinessException) exception).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
    }

    @Test
    @DisplayName("T26 커서가 없으면 첫 페이지를 뜻하는 null이다")
    void returnsNullForAbsentCursor() {
        assertThat(UserSearchCursor.decode(null)).isNull();
    }

    /** 서버가 만들지 않은 커서를 흉내낸다. 인코딩 형식만 맞추고 값은 임의로 넣는다. */
    private static String forged(String raw) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
