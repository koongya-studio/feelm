package com.feelm.backend.infrastructure.oauth;

import com.feelm.backend.domain.auth.entity.OAuthProvider;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.util.EnumMap;
import java.util.Map;

/**
 * 설정된 제공자별 {@link OAuthClient} 레지스트리다.
 *
 * <p>자격 증명이 없는 제공자는 아예 등록되지 않는다. 기동을 막는 대신 호출 시점에 {@code UNSUPPORTED_PROVIDER}로 응답하기로 했기 때문이다(D-5).
 * 자격 증명이 아직 발급되지 않은 제공자가 하나라도 있으면 서비스 전체가 뜨지 못하는 상황을 피하려는 선택이다.
 */
public class OAuthClients {

    private final Map<OAuthProvider, OAuthClient> clients;

    public OAuthClients(Map<OAuthProvider, OAuthClient> clients) {
        // EnumMap(Map)은 빈 Map을 받으면 키 타입을 알 수 없어 실패한다. 설정된 제공자가 하나도 없는
        // 환경(로컬·테스트)에서도 기동해야 하므로 키 타입을 명시해 만들고 복사한다.
        this.clients = new EnumMap<>(OAuthProvider.class);
        this.clients.putAll(clients);
    }

    /**
     * @throws BusinessException 지원하지 않거나 설정되지 않은 제공자는 {@code UNSUPPORTED_PROVIDER}
     */
    public OAuthClient get(OAuthProvider provider) {
        OAuthClient client = clients.get(provider);
        if (client == null) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PROVIDER);
        }
        return client;
    }
}
