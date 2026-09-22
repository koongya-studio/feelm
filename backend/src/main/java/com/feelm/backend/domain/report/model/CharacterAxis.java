package com.feelm.backend.domain.report.model;

/**
 * 캐릭터 카드의 네 축(API-060 처리 규칙). 축 순서가 코드의 글자 순서다.
 *
 * <p>{@code head}가 앞글자이고 <b>값이 중앙값 이상이면 앞글자</b>가 기본 규칙이다. 축마다 예외가 하나씩 있고 그 판정은 {@code
 * CharacterCardSelector}가 한다.
 */
public enum CharacterAxis {
    /** 새로운 맛 편수. 중앙값이 0이면 그때만 1개 이상을 `E`로 본다 */
    EXPLORATION("E", "탐험", "R", "정착"),
    /** 밝은 맛 비중. 분모가 0이면 `L`을 기본값으로 둔다 */
    TONE("L", "빛", "D", "그림자"),
    /** 평균 별점. 중앙값과 같으면 평점 표준편차가 작은 쪽을 `H`로 둔다 */
    STANCE("H", "가슴", "M", "머리"),
    /** 감상 편수 */
    VOLUME("V", "다작", "S", "정독");

    private final String headValue;
    private final String headLabel;
    private final String tailValue;
    private final String tailLabel;

    CharacterAxis(String headValue, String headLabel, String tailValue, String tailLabel) {
        this.headValue = headValue;
        this.headLabel = headLabel;
        this.tailValue = tailValue;
        this.tailLabel = tailLabel;
    }

    public String value(boolean head) {
        return head ? headValue : tailValue;
    }

    public String label(boolean head) {
        return head ? headLabel : tailLabel;
    }
}
