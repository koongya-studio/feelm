package com.feelm.backend.domain.party.service;

import com.feelm.backend.global.response.CursorCodec;
import java.time.Instant;

/**
 * API-053 받은 초대 목록의 불투명 커서. 정렬 키인 {@code (createdAt, id)}를 그대로 담는다.
 *
 * <p>인코딩 형식과 방어 규칙은 {@link CursorCodec}이 정한다. 파티 목록 커서와 형식만 같고 타입은 공유하지 않는다(컨벤션 11장).
 */
record PartyInvitationCursor(Instant createdAt, long invitationId) {

    String encode() {
        return CursorCodec.encode(createdAt, invitationId);
    }

    static PartyInvitationCursor decode(String encoded) {
        return CursorCodec.decodeInstant(encoded, PartyInvitationCursor::new);
    }
}
