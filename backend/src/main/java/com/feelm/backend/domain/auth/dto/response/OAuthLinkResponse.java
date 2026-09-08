package com.feelm.backend.domain.auth.dto.response;

import com.feelm.backend.domain.auth.entity.OAuthProvider;
import com.feelm.backend.domain.auth.entity.UserAuthProvider;
import java.time.Instant;

/** API-009 소셜 계정 연결 응답. 연결된 수단 목록은 내 프로필(API-014)로 조회한다. */
public record OAuthLinkResponse(OAuthProvider provider, Instant linkedAt) {

    public static OAuthLinkResponse from(UserAuthProvider link) {
        return new OAuthLinkResponse(link.getProvider(), link.getLinkedAt());
    }
}
