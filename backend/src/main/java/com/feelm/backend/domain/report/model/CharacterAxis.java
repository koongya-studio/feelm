package com.feelm.backend.domain.report.model;

/**
 * 캐릭터 카드의 네 축(API-060 처리 규칙). 축 순서가 코드의 글자 순서다.
 *
 * <p>{@code head}가 앞글자이고 <b>값이 중앙값 이상이면 앞글자</b>가 기본 규칙이다. 축마다 예외가 하나씩 있고 그 판정은 {@code
 * CharacterCardSelector}가 한다.
 *
 * <p><b>표시명은 그 축이 재는 값을 그대로 부르는 한 낱말이다</b>(업무 -814). 화면은 축 이름 없이 양 끝 두 낱말만 보이므로 낱말이 곧 축 설명이어야 하고, 축
 * 막대 양 끝에 놓이므로 짧아야 한다. 앞의 {@code 탐험 / 정착} · {@code 빛 / 그림자} · {@code 가슴 / 머리} · {@code 다작 / 정독}은
 * 무엇을 재는지 읽히지 않았다. 표시명은 기준정보라 바뀔 수 있고 계약은 {@code value} 한 글자다.
 */
public enum CharacterAxis {
    /** 새로운 맛 편수. 중앙값이 0이면 그때만 1개 이상을 `E`로 본다 */
    EXPLORATION("E", "새로움", "R", "익숙함"),
    /** 밝은 맛 비중. 분모가 0이면 `L`을 기본값으로 둔다 */
    TONE("L", "밝음", "D", "어두움"),
    /** 평균 별점. 중앙값과 같으면 평점 표준편차가 작은 쪽을 `H`로 둔다 */
    STANCE("H", "후함", "M", "박함"),
    /** 감상 편수 */
    VOLUME("V", "많음", "S", "적음");

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
