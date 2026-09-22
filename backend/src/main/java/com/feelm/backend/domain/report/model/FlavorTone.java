package com.feelm.backend.domain.report.model;

import java.util.Set;

/**
 * 팝콘 맛의 빛 · 그림자. 캐릭터 카드 `TONE` 축의 분자 · 분모를 가른다(API-060 처리 규칙).
 *
 * <p>배정을 <b>표시명이 아니라 코드로</b> 건다. 표시명은 바뀔 수 있는 기준정보이고 실제로 마이그레이션에서 값이 옮겨간 적이 있다 — {@code 다크초코}가
 * {@code V202609161115}의 {@code FLAVOR_D}에서 {@code V202609161836}의 {@code FLAVOR_B}로 갔다. 표시명으로 걸면
 * 축이 반대로 갈린다.
 *
 * <p>배정의 근거는 두 마이그레이션이 바꾸지 않은 {@code popcorn_flavors.representative_keywords}다. 계약이 코드로 고정했으므로 조회하지
 * 않고 상수로 둔다.
 */
public enum FlavorTone {
    /** 관계 · 로맨스 · 코미디 · 가족 · 애니메이션 · 판타지 */
    BRIGHT,
    /** 공포 · 미스터리 · 스릴러 · 범죄 · 전쟁 */
    DARK,
    /** 다큐 · 역사 / 액션 · SF. 분자와 분모에서 모두 뺀다 */
    NEUTRAL;

    private static final Set<String> BRIGHT_CODES = Set.of("FLAVOR_C", "FLAVOR_F", "FLAVOR_G");
    private static final Set<String> DARK_CODES = Set.of("FLAVOR_B", "FLAVOR_D", "FLAVOR_E");

    /** 맛 코드의 빛 · 그림자. 모르는 코드와 맛이 없는 프레임은 중립으로 둔다 */
    public static FlavorTone of(String flavorCode) {
        if (flavorCode == null) {
            return NEUTRAL;
        }
        if (BRIGHT_CODES.contains(flavorCode)) {
            return BRIGHT;
        }
        return DARK_CODES.contains(flavorCode) ? DARK : NEUTRAL;
    }
}
