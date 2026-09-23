package com.feelm.backend.domain.report.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 캐릭터 카드 16종 기준정보 (업무 S15P21E106-779) */
class CharacterCardTest {

    @Test
    @DisplayName("16종이 네 축의 조합을 모두 덮고 문구가 비어 있지 않다")
    void sixteenCardsCoverEveryAxisCombination() {
        // 네 축의 글자를 한 번씩 쓴 조합이 16가지다. 그래서 후보 없음이 생기지 않는다(API-060)
        List<String> combinations = List.of("");
        for (CharacterAxis axis : CharacterAxis.values()) {
            List<String> next = new ArrayList<>();
            for (String prefix : combinations) {
                next.add(prefix + axis.value(true));
                next.add(prefix + axis.value(false));
            }
            combinations = next;
        }

        assertThat(CharacterCard.values()).hasSize(16);
        assertThat(CharacterCard.values())
                .extracting(CharacterCard::code)
                .containsExactlyInAnyOrderElementsOf(combinations);

        for (CharacterCard card : CharacterCard.values()) {
            assertThat(card.cardName()).as(card.code()).isNotBlank();
            assertThat(card.tagline()).as(card.code()).isNotBlank();
            assertThat(card.description()).as(card.code()).isNotBlank();
        }
    }

    @Test
    @DisplayName("코드로 카드를 찾고 계약 밖의 코드는 거부한다")
    void findsCardByCode() {
        assertThat(CharacterCard.byCode("RDMV").cardName()).isEqualTo("안락의자 탐정");
        assertThat(CharacterCard.byCode("ELHV").cardName()).isEqualTo("길 위의 낙천가");

        assertThatThrownBy(() -> CharacterCard.byCode("XXXX"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("네 축의 판정 결과가 코드의 글자 순서로 이어진다")
    void buildsCodeFromAxisOrder() {
        // 축 순서는 새로움·익숙함 -> 밝음·어두움 -> 후함·박함 -> 많음·적음이다
        assertThat(CharacterCard.of(true, true, true, true).code()).isEqualTo("ELHV");
        assertThat(CharacterCard.of(false, false, false, false).code()).isEqualTo("RDMS");
        assertThat(CharacterCard.of(false, false, false, true).code()).isEqualTo("RDMV");
    }

    @Test
    @DisplayName("맛의 밝고 어두움을 표시명이 아니라 코드로 가른다")
    void assignsFlavorToneByCode() {
        // 표시명은 마이그레이션에서 옮겨간 적이 있다 — 다크초코가 FLAVOR_D에서 FLAVOR_B로 갔다
        assertThat(FlavorTone.of("FLAVOR_C")).isEqualTo(FlavorTone.BRIGHT);
        assertThat(FlavorTone.of("FLAVOR_F")).isEqualTo(FlavorTone.BRIGHT);
        assertThat(FlavorTone.of("FLAVOR_G")).isEqualTo(FlavorTone.BRIGHT);

        assertThat(FlavorTone.of("FLAVOR_B")).isEqualTo(FlavorTone.DARK);
        assertThat(FlavorTone.of("FLAVOR_D")).isEqualTo(FlavorTone.DARK);
        assertThat(FlavorTone.of("FLAVOR_E")).isEqualTo(FlavorTone.DARK);

        assertThat(FlavorTone.of("FLAVOR_A")).isEqualTo(FlavorTone.NEUTRAL);
        assertThat(FlavorTone.of("FLAVOR_H")).isEqualTo(FlavorTone.NEUTRAL);

        // 모르는 코드와 맛이 없는 프레임은 분자·분모에 넣지 않는다
        assertThat(FlavorTone.of("FLAVOR_Z")).isEqualTo(FlavorTone.NEUTRAL);
        assertThat(FlavorTone.of(null)).isEqualTo(FlavorTone.NEUTRAL);
    }
}
