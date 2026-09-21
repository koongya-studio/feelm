package com.feelm.backend.domain.recommendation.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 추천 버퍼 프리페치 설정이다(S15P21E106-708).
 *
 * <p>{@code prefetchThreshold}는 현재 세트의 미노출 유효 후보 수가 이 값 <b>이하</b>로 내려갔을 때 다음 회차를 미리 만든다는 뜻이다. 판단값은
 * TASTE와 DISCOVERY의 합이다. 타입별로 나누면 DISCOVERY 후보가 원래 적은 사용자에서 상시 트리거되고, 서빙이 미소진 최저 회차부터 쓰기 때문에 미리 만든
 * 회차는 현재 회차가 완전히 소진되기 전까지 쓰이지 않는다. 프리페치가 대비하는 시점은 소진이고 소진은 두 종류가 모두 0일 때 일어난다.
 */
@ConfigurationProperties(prefix = "feelm.recommendation.buffer")
public record RecommendationPrefetchProperties(@DefaultValue("5") int prefetchThreshold) {

    /**
     * 미노출 유효 후보 수가 임계값 이하인지다.
     *
     * <p>임계값이 음수면 프리페치를 끈다. 배경 최적화라 문제가 생기면 통째로 멈출 수 있어야 하고, 꺼도 소진 시점의 동기 재생성(S15P21E106-219)이 그대로
     * 동작한다.
     */
    public boolean needsPrefetch(int unexposedValidCount) {
        return prefetchThreshold >= 0 && unexposedValidCount <= prefetchThreshold;
    }
}
