package com.feelm.backend.domain.user.service;

import com.feelm.backend.domain.user.NicknamePolicy;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * API-019 사용자 검색의 불투명 커서. 정렬 키인 {@code (정규화 닉네임, userId)}를 그대로 담는다.
 *
 * <p>차단 목록 커서({@link UserBlockCursor})와 형식만 같고 타입은 공유하지 않는다. 정렬 키가 다르고, 도메인 내부 타입을 서로의 계약으로 끌어올리지
 * 않기 위해서다(컨벤션 11장).
 *
 * <p>커서는 클라이언트가 고쳐 보낼 수 있는 입력이다. 해독에 실패하는 모든 경우를 {@code VALIDATION_FAILED}로 모아, 위조된 값이 500 응답이 되지
 * 않게 한다.
 */
record UserSearchCursor(String nickname, long userId) {

    /**
     * 해독 전 문자열 길이 상한. 정규화 닉네임은 최대 {@code users.nickname} 길이(20자)이고 한글은 UTF-8에서 3바이트라 60바이트, 구분자와
     * {@code long} 최대 자릿수를 더해도 Base64로 128자를 넘지 않는다.
     */
    private static final int MAX_ENCODED_LENGTH = 128;

    /** 해독한 닉네임 길이 상한. {@code users.nickname} 컬럼 길이와 같다. */
    private static final int MAX_NICKNAME_LENGTH = 20;

    private static final char SEPARATOR = ':';

    String encode() {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((nickname + SEPARATOR + userId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 커서를 해독한다. 값이 없으면 첫 페이지를 뜻하는 {@code null}을 돌려준다.
     *
     * <p>닉네임에 구분자가 들어갈 수 없도록 막는 대신 마지막 구분자를 기준으로 나눈다. 뒤쪽은 항상 숫자인 id라 앞쪽이 무엇이든 경계가 흔들리지 않는다.
     *
     * @throws BusinessException 형식이 깨졌거나 값이 저장 가능한 범위를 벗어나면 {@code VALIDATION_FAILED}
     */
    static UserSearchCursor decode(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            if (encoded.length() > MAX_ENCODED_LENGTH) {
                throw new IllegalArgumentException("cursor too long");
            }
            String value =
                    new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = value.lastIndexOf(SEPARATOR);
            if (separator < 1 || separator == value.length() - 1) {
                throw new IllegalArgumentException("invalid cursor");
            }
            String nickname = value.substring(0, separator);
            if (nickname.length() > MAX_NICKNAME_LENGTH) {
                throw new IllegalArgumentException("nickname too long");
            }
            // 서버가 발급한 커서에는 저장된 닉네임만 들어간다. 제어문자가 보이면 손댄 값이므로
            // 질의에 넘기지 않는다. NUL은 PostgreSQL text가 담지 못해 그대로 넘기면 500이 된다.
            if (NicknamePolicy.containsControlCharacter(nickname)) {
                throw new IllegalArgumentException("nickname has control character");
            }
            long userId = Long.parseLong(value.substring(separator + 1));
            if (userId < 1) {
                throw new IllegalArgumentException("invalid user id");
            }
            return new UserSearchCursor(nickname, userId);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
