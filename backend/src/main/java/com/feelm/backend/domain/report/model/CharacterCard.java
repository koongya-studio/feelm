package com.feelm.backend.domain.report.model;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 반기 리포트 캐릭터 카드 16종(API-060 처리 규칙, 업무 -618).
 *
 * <p>enum 이름이 곧 계약의 {@code code}다. 네 축을 각각 두 값으로 판정한 조합이라 16종이 모든 경우를 덮고, <b>후보 없음이 생기지 않는다.</b>
 *
 * <p>계약은 {@code code}뿐이다. 이름 · 한 줄 · 설명문은 바뀔 수 있는 기준정보이고 서버가 소유자다 — 화면은 이 값을 응답으로 받고 코드로 그림만 찾는다(업무
 * -780).
 *
 * <p>이름은 보통명사다. 퍼블릭 도메인 원형 인물은 설명문 안에서 비유로만 쓴다. 상표 · 2차 창작 리스크를 카드 이름에 걸지 않기 위한 구분이다.
 */
public enum CharacterCard {
    ELHV(
            "길 위의 낙천가",
            "어디든 가고, 무엇이든 좋아했습니다",
            "새로운 맛으로 자주 건너갔고, 밝은 쪽을 많이 봤고, 별도 후하게 줬습니다."
                    + " 돈키호테처럼 보이는 풍차마다 달려들었고 한 번도 실망하지 않았습니다"),
    ELHS(
            "토끼굴에 빠진 아이",
            "적게 봤지만 매번 다른 세계로 떨어졌습니다",
            "편수는 많지 않은데 갈 때마다 전혀 다른 곳에 도착했습니다." + " 앨리스처럼 몇 안 되는 외출이 전부 낯선 나라였습니다"),
    ELMV(
            "세계 일주하는 수집가",
            "새 영역을 표로 정리하며 훑었습니다",
            "넓게, 많이, 그러면서도 차분하게 봤습니다." + " 마르코 폴로처럼 낯선 땅마다 무엇이 있는지 적어 두고 다음으로 넘어갔습니다"),
    ELMS(
            "풀숲의 관찰자",
            "낯선 것 하나를 오래 들여다봤습니다",
            "새로운 영역으로 갔지만 서두르지 않았고 평가도 냉정했습니다." + " 파브르처럼 남들이 지나치는 풀숲에 쪼그려 앉아 계절을 보냈습니다"),
    EDHV(
            "복수자",
            "어두운 쪽으로 계속, 뜨겁게 갔습니다",
            "방향을 한 번 정하고는 멈추지 않았고, 볼 때마다 감정을 실었습니다." + " 몬테크리스토 백작처럼 목록의 이름을 하나씩 지워 나갔습니다"),
    EDHS(
            "돌아오지 못한 자",
            "드물게 봤고, 그때마다 깊이 가라앉았습니다",
            "편수는 적지만 전부 어두운 쪽이었고 매번 마음을 두고 왔습니다." + " 오르페우스처럼 단 한 번의 여정에 전부를 걸었습니다"),
    EDMV(
            "금기를 여는 자",
            "건드리면 안 될 것들을 계속 열어봤습니다",
            "낯설고 불편한 쪽으로 자주 갔고, 그러면서도 평가는 서늘했습니다." + " 프랑켄슈타인 박사처럼 결과를 알면서 실험을 멈추지 않았습니다"),
    EDMS(
            "심연을 들여다본 학자",
            "한 편을 오래, 차갑게 응시했습니다",
            "많이 보지 않았고 후하지도 않았지만, 고른 것들은 전부 깊은 쪽이었습니다." + " 파우스트처럼 대가를 알면서도 서재를 떠나지 않았습니다"),
    RLHV(
            "이야기 많은 아이",
            "익숙한 자리에서, 아주 많이, 후하게",
            "좋아하는 맛을 계속 보면서 별도 아끼지 않았습니다." + " 빨간 머리 앤처럼 같은 마을에 살면서 매일을 이야기로 부풀렸습니다"),
    RLHS(
            "무도회를 기다린 사람",
            "같은 자리에서 조용히, 오래",
            "편수는 적었지만 고른 것마다 마음을 뒀고 밝은 쪽을 놓지 않았습니다." + " 신데렐라처럼 단 하루의 무도회를 아주 오래 기다렸습니다"),
    RLMV(
            "동네 꾀돌이",
            "아는 세계를 많이 보며 품평했습니다",
            "익숙한 맛 안에서 부지런히 봤고 평가는 깐깐했습니다." + " 톰 소여처럼 동네를 안 벗어나면서도 매번 꾀로 해결했습니다"),
    RLMS(
            "등대지기",
            "한자리에서 적게, 정확하게",
            "많이 보지 않았고 좀처럼 높은 별을 주지 않았지만 자리를 지켰습니다." + " 로빈슨 크루소처럼 날짜를 세며 하루를 기록했습니다"),
    RDHV(
            "잠 못 드는 파수꾼",
            "어두운 쪽을 놓지 못하고 계속 봤습니다",
            "같은 그림자 안에서 부지런히, 매번 감정을 실어 봤습니다." + " 반 헬싱처럼 밤마다 같은 자리를 지켰습니다"),
    RDHS(
            "비극의 주인공",
            "결정을 미루며 같은 그림자에 머물렀습니다",
            "편수는 적었지만 어두운 쪽만 골랐고 매번 마음이 크게 움직였습니다." + " 햄릿처럼 오래 망설이면서도 무대를 떠나지 않았습니다"),
    RDMV(
            "안락의자 탐정",
            "움직이지 않고 사건만 잔뜩 봤습니다",
            "익숙한 맛 안에서 아주 많이 봤고 평가는 냉정했습니다." + " 셜록 홈즈처럼 방을 나서지 않고 전부 풀었습니다"),
    RDMS("금고지기", "하나를 끝까지 파고들었습니다", "적게, 어둡게, 그리고 박하게 봤습니다." + " 스크루지처럼 하나만 붙들고 나머지는 전부 덮어 두었습니다");

    private static final Map<String, CharacterCard> BY_CODE =
            Stream.of(values()).collect(Collectors.toMap(Enum::name, Function.identity()));

    private final String cardName;
    private final String tagline;
    private final String description;

    CharacterCard(String cardName, String tagline, String description) {
        this.cardName = cardName;
        this.tagline = tagline;
        this.description = description;
    }

    /** 불변 계약인 4글자 코드. enum 이름과 같다 */
    public String code() {
        return name();
    }

    public String cardName() {
        return cardName;
    }

    public String tagline() {
        return tagline;
    }

    public String description() {
        return description;
    }

    /** 네 축의 판정 결과로 카드를 찾는다. 16종이 모든 조합을 덮으므로 늘 하나가 나온다 */
    public static CharacterCard of(
            boolean exploring, boolean bright, boolean heart, boolean voluminous) {
        String code =
                CharacterAxis.EXPLORATION.value(exploring)
                        + CharacterAxis.TONE.value(bright)
                        + CharacterAxis.STANCE.value(heart)
                        + CharacterAxis.VOLUME.value(voluminous);
        return byCode(code);
    }

    /** 코드로 카드를 찾는다. 계약 밖의 코드는 예외다 */
    public static CharacterCard byCode(String code) {
        CharacterCard card = BY_CODE.get(code);
        if (card == null) {
            throw new IllegalArgumentException("Unknown character card code: " + code);
        }
        return card;
    }
}
