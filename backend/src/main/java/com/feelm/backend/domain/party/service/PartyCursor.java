package com.feelm.backend.domain.party.service;

import com.feelm.backend.global.response.CursorCodec;
import java.time.Instant;

/**
 * API-049 파티 목록의 불투명 커서. 정렬 키인 {@code (createdAt, id)}를 그대로 담는다.
 *
 * <p>인코딩 형식과 방어 규칙은 {@link CursorCodec}이 정한다. 같은 형식을 쓰는 다른 도메인 커서와 타입은 공유하지 않는다. 도메인 내부 타입을 도메인 밖으로
 * 노출하지 않기 위해서다(컨벤션 11장).
 */
record PartyCursor(Instant createdAt, long partyId) {

    String encode() {
        return CursorCodec.encode(createdAt, partyId);
    }

    static PartyCursor decode(String encoded) {
        return CursorCodec.decodeInstant(encoded, PartyCursor::new);
    }
}
