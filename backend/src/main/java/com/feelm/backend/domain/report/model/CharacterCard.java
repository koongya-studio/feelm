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
 *
 * <p><b>문구는 네 축 표시명과 같은 말을 쓴다</b>(업무 -814). {@code description}의 첫 문장이 {@code CharacterAxis}의 네 판정을
 * {@code 새로운 / 익숙한} · {@code 밝은 / 어두운} · {@code 많이 / 적게} · {@code 후했 / 박했}으로 그대로 읽으므로 축 막대와 설명문이 같은
 * 낱말로 읽힌다. 뒤 문장의 비유 하나만 성격을 맡는다. 말투는 앱과 같은 {@code -어요}체다.
 *
 * <p><b>문구에 팝콘 맛을 쓰지 않는다</b>(사용자 요청 2026-09-23). {@code EXPLORATION}의 지표는 새로 생긴 팝콘 맛 수이지만 맛은 앱 안의
 * 개념이라 카드 문구에서는 {@code 새로운 영화} · {@code 익숙한 영화}로 바꿔 부른다. 맛 이름은 같은 화면의 지난 반기 비교가 맡는다. 이름 16종은 업무
 * -781의 그림과 짝이라 그대로 둔다.
 */
public enum CharacterCard {
    ELHV(
            "길 위의 낙천가",
            "처음 보는 영화마다 뛰어들었고, 별점도 아끼지 않았어요",
            "새로운 영화를 찾아 밝은 쪽을 많이 봤고, 별점은 후했어요." + " 돈키호테처럼 보이는 풍차마다 달려들었고 한 번도 실망하지 않았어요"),
    ELHS(
            "토끼굴에 빠진 아이",
            "적게 봤지만 갈 때마다 처음 보는 세계였어요",
            "새로운 영화를 찾아 밝은 쪽을 적게 봤고, 별점은 후했어요." + " 앨리스처럼 몇 안 되는 외출이 전부 낯선 나라였어요"),
    ELMV(
            "세계 일주하는 수집가",
            "새로운 영역을 넓게 훑었고, 별점은 깐깐했어요",
            "새로운 영화를 찾아 밝은 쪽을 많이 봤고, 별점은 박했어요." + " 마르코 폴로처럼 낯선 땅마다 무엇이 있는지 적어 두고 다음으로 넘어갔어요"),
    ELMS(
            "풀숲의 관찰자",
            "낯선 것 몇 편을 오래, 까다롭게 봤어요",
            "새로운 영화를 찾아 밝은 쪽을 적게 봤고, 별점은 박했어요." + " 파브르처럼 남들이 지나치는 풀숲에 쪼그려 앉아 계절을 보냈어요"),
    EDHV(
            "복수자",
            "어두운 쪽으로 계속 갔고, 볼 때마다 별을 아끼지 않았어요",
            "새로운 영화를 찾아 어두운 쪽을 많이 봤고, 별점은 후했어요." + " 몬테크리스토 백작처럼 목록의 이름을 하나씩 지워 나갔어요"),
    EDHS(
            "돌아오지 못한 자",
            "드물게 봤지만 그때마다 깊이 가라앉았어요",
            "새로운 영화를 찾아 어두운 쪽을 적게 봤고, 별점은 후했어요." + " 오르페우스처럼 단 한 번의 여정에 전부를 걸었어요"),
    EDMV(
            "금기를 여는 자",
            "건드리면 안 될 것들을 계속 열어봤고, 별점은 서늘했어요",
            "새로운 영화를 찾아 어두운 쪽을 많이 봤고, 별점은 박했어요." + " 프랑켄슈타인 박사처럼 결과를 알면서 실험을 멈추지 않았어요"),
    EDMS(
            "심연을 들여다본 학자",
            "몇 편을 오래, 차갑게 응시했어요",
            "새로운 영화를 찾아 어두운 쪽을 적게 봤고, 별점은 박했어요." + " 파우스트처럼 대가를 알면서도 서재를 떠나지 않았어요"),
    RLHV(
            "이야기 많은 아이",
            "좋아하는 영화를 실컷 봤고, 별점도 넉넉했어요",
            "익숙한 영화 안에서 밝은 쪽을 많이 봤고, 별점은 후했어요." + " 빨간 머리 앤처럼 같은 마을에 살면서 매일을 이야기로 부풀렸어요"),
    RLHS(
            "무도회를 기다린 사람",
            "익숙한 자리에서 몇 편에 오래 마음을 뒀어요",
            "익숙한 영화 안에서 밝은 쪽을 적게 봤고, 별점은 후했어요." + " 신데렐라처럼 단 하루의 무도회를 아주 오래 기다렸어요"),
    RLMV(
            "동네 꾀돌이",
            "아는 세계를 부지런히 봤고, 품평은 깐깐했어요",
            "익숙한 영화 안에서 밝은 쪽을 많이 봤고, 별점은 박했어요." + " 톰 소여처럼 동네를 안 벗어나면서도 매번 꾀로 해결했어요"),
    RLMS(
            "등대지기",
            "한자리에서 적게, 정확하게 봤어요",
            "익숙한 영화 안에서 밝은 쪽을 적게 봤고, 별점은 박했어요." + " 로빈슨 크루소처럼 날짜를 세며 하루를 기록했어요"),
    RDHV(
            "잠 못 드는 파수꾼",
            "어두운 쪽을 놓지 못하고 계속 봤고, 별점은 후했어요",
            "익숙한 영화 안에서 어두운 쪽을 많이 봤고, 별점은 후했어요." + " 반 헬싱처럼 밤마다 같은 자리를 지켰어요"),
    RDHS(
            "비극의 주인공",
            "몇 편 안 되는 그림자에 마음이 크게 흔들렸어요",
            "익숙한 영화 안에서 어두운 쪽을 적게 봤고, 별점은 후했어요." + " 햄릿처럼 오래 망설이면서도 무대를 떠나지 않았어요"),
    RDMV(
            "안락의자 탐정",
            "익숙한 자리에서 잔뜩 봤고, 별점은 냉정했어요",
            "익숙한 영화 안에서 어두운 쪽을 많이 봤고, 별점은 박했어요." + " 셜록 홈즈처럼 방을 나서지 않고 전부 풀었어요"),
    RDMS(
            "금고지기",
            "적게, 어둡게, 그리고 박하게 봤어요",
            "익숙한 영화 안에서 어두운 쪽을 적게 봤고, 별점은 박했어요." + " 스크루지처럼 하나만 붙들고 나머지는 전부 덮어 두었어요");

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
