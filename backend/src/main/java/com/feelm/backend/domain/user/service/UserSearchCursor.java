package com.feelm.backend.domain.user.service;

import com.feelm.backend.domain.user.NicknamePolicy;
import com.feelm.backend.global.response.CursorCodec;

/**
 * API-019 사용자 검색의 불투명 커서. 정렬 키인 {@code (정규화 닉네임, userId)}를 그대로 담는다.
 *
 * <p>인코딩 형식과 방어 규칙은 {@link CursorCodec}이 정한다. 차단 목록 커서({@link UserBlockCursor})와 형식만 같고 타입은 공유하지
 * 않는다. 정렬 키가 다르고, 도메인 내부 타입을 서로의 계약으로 끌어올리지 않기 위해서다(컨벤션 11장).
 */
record UserSearchCursor(String nickname, long userId) {

    /** 해독한 닉네임 길이 상한. {@code users.nickname} 컬럼 길이와 같다. */
    private static final int MAX_NICKNAME_LENGTH = 20;

    String encode() {
        return CursorCodec.encode(nickname, userId);
    }

    static UserSearchCursor decode(String encoded) {
        return CursorCodec.decode(encoded, (head, id) -> new UserSearchCursor(validated(head), id));
    }

    /** 서버가 발급한 커서에는 저장된 닉네임만 들어간다. 규칙을 벗어난 값이 보이면 손댄 커서다. */
    private static String validated(String nickname) {
        if (nickname.length() > MAX_NICKNAME_LENGTH) {
            throw new IllegalArgumentException("nickname too long");
        }
        // 제어문자가 보이면 손댄 값이므로 질의에 넘기지 않는다. NUL은 PostgreSQL text가 담지 못해
        // 그대로 넘기면 500이 된다.
        if (NicknamePolicy.containsControlCharacter(nickname)) {
            throw new IllegalArgumentException("nickname has control character");
        }
        return nickname;
    }
}
