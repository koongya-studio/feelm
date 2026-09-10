package com.feelm.backend.domain.user.service;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * API-022 차단 목록의 불투명 커서. 정렬 키인 {@code (createdAt, id)}를 그대로 담는다.
 *
 * <p>같은 방식을 쓰는 파티 목록 커서와 형식만 같고 타입은 공유하지 않는다. 도메인 내부 타입을 도메인 밖으로 노출하지 않기 위해서다(컨벤션 11장).
 */
record UserBlockCursor(Instant createdAt, long blockId) {

    /**
     * 해독한 {@code createdAt}이 이 범위를 벗어나면 형식이 깨진 커서로 본다.
     *
     * <p>{@code Instant}는 연도 10억까지 담지만 {@code user_blocks.created_at}은 {@code timestamptz}라 훨씬 좁다.
     * 범위를 넘는 값을 그대로 바인딩하면 드라이버나 DB가 거절해 클라이언트 입력이 500 응답이 된다. 커서에는 저장된 시각만 들어오므로 epoch~2100년으로
     * 제한한다.
     */
    private static final Instant MIN_CREATED_AT = Instant.EPOCH;

    private static final Instant MAX_CREATED_AT = Instant.parse("2100-01-01T00:00:00Z");

    String encode() {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((createdAt + ":" + blockId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 커서를 해독한다. 값이 없으면 첫 페이지를 뜻하는 {@code null}을 돌려준다.
     *
     * @throws BusinessException 형식이 깨졌거나 시각이 저장 가능한 범위를 벗어나면 {@code VALIDATION_FAILED}
     */
    static UserBlockCursor decode(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            if (encoded.length() > 128) {
                throw new IllegalArgumentException("cursor too long");
            }
            String value =
                    new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = value.lastIndexOf(':');
            if (separator < 1 || separator == value.length() - 1) {
                throw new IllegalArgumentException("invalid cursor");
            }
            Instant createdAt = Instant.parse(value.substring(0, separator));
            if (createdAt.isBefore(MIN_CREATED_AT) || createdAt.isAfter(MAX_CREATED_AT)) {
                throw new IllegalArgumentException("cursor time out of range");
            }
            long blockId = Long.parseLong(value.substring(separator + 1));
            if (blockId < 1) {
                throw new IllegalArgumentException("invalid block id");
            }
            return new UserBlockCursor(createdAt, blockId);
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
