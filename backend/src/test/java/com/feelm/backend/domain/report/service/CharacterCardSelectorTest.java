package com.feelm.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.domain.report.model.CharacterAxisMedians;
import com.feelm.backend.domain.report.model.CharacterAxisMetrics;
import com.feelm.backend.domain.report.model.ReportContent.CharacterCardContent;
import com.feelm.backend.domain.report.model.ReportSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 캐릭터 카드 4축 판정 (업무 S15P21E106-779).
 *
 * <p>기본 규칙은 값이 중앙값 이상이면 앞글자이고 축별 예외가 셋이다(API-060 처리 규칙). 예외 셋과 30명 미만 폴백이 이 시험의 핵심이다.
 */
class CharacterCardSelectorTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-07-01T00:00:00Z");

    @Test
    @DisplayName("네 축이 모두 중앙값과 같으면 앞글자를 받아 ELHV다")
    void picksHeadLettersWhenEqualToMedian() {
        CharacterAxisMedians medians = medians(new BigDecimal("2"), "0.5", "3.5", "0.5", "20");
        CharacterAxisMetrics metrics = metrics(2, "0.5", "3.5", "0.4", 20);

        assertThat(CharacterCardSelector.select(metrics, medians, 20).code()).isEqualTo("ELHV");
    }

    @Test
    @DisplayName("네 축이 모두 중앙값 미만이면 뒷글자를 받아 RDMS다")
    void picksTailLettersBelowMedian() {
        CharacterAxisMedians medians = medians(new BigDecimal("2"), "0.5", "3.5", "0.5", "20");
        CharacterAxisMetrics metrics = metrics(1, "0.2", "3.0", "0.4", 5);

        assertThat(CharacterCardSelector.select(metrics, medians, 5).code()).isEqualTo("RDMS");
    }

    @Test
    @DisplayName("탐험 중앙값이 0이면 새 맛 1개 이상을 E로 본다")
    void treatsAnyNewFlavorAsExploringWhenMedianIsZero() {
        // 중앙값 0이면 `이상` 규칙으로는 전원 E가 된다. 그때만 1개 이상을 기준으로 내린다
        CharacterAxisMedians medians = medians(BigDecimal.ZERO, "0.5", "3.5", "0.5", "20");

        assertThat(
                        CharacterCardSelector.select(
                                        metrics(1, "0.5", "3.5", "0.4", 20), medians, 20)
                                .code())
                .startsWith("E");
        assertThat(
                        CharacterCardSelector.select(
                                        metrics(0, "0.5", "3.5", "0.4", 20), medians, 20)
                                .code())
                .startsWith("R");
    }

    @Test
    @DisplayName("밝은 맛 분모가 0이면 중앙값과 무관하게 L이다")
    void defaultsToBrightWhenNoTonedFlavor() {
        // 중립 맛만 감상한 사용자다. 비중이 정의되지 않으므로 L을 기본값으로 둔다
        CharacterAxisMedians medians = medians(new BigDecimal("2"), "0.9", "3.5", "0.5", "20");
        CharacterAxisMetrics metrics =
                CharacterCardSelector.metrics(
                        List.of(frame(1, "4.0", "FLAVOR_A"), frame(2, "4.0", "FLAVOR_H")),
                        START,
                        END);

        assertThat(metrics.brightRatio()).isNull();
        assertThat(CharacterCardSelector.select(metrics, medians, 2).code()).contains("L");
    }

    @Test
    @DisplayName("평점이 중앙값과 같으면 표준편차가 작은 쪽이 H다")
    void breaksStanceTieByLowerDeviation() {
        CharacterAxisMedians medians = medians(new BigDecimal("2"), "0.5", "3.5", "0.5", "20");

        CharacterCardContent steady =
                CharacterCardSelector.select(metrics(2, "0.5", "3.5", "0.2", 20), medians, 20);
        CharacterCardContent swinging =
                CharacterCardSelector.select(metrics(2, "0.5", "3.5", "0.9", 20), medians, 20);

        assertThat(steady.code().charAt(2)).isEqualTo('H');
        assertThat(swinging.code().charAt(2)).isEqualTo('M');
    }

    @Test
    @DisplayName("중립 맛은 분자와 분모에서 모두 뺀다")
    void excludesNeutralFlavorsFromBothSides() {
        // 밝음 1 · 어두움 1 · 중립 8이면 비중은 8/10이 아니라 1/2다
        List<ReportSource.Frame> frames = new ArrayList<>();
        frames.add(frame(1, "4.0", "FLAVOR_C"));
        frames.add(frame(2, "4.0", "FLAVOR_B"));
        for (int index = 0; index < 8; index++) {
            frames.add(frame(10 + index, "4.0", index % 2 == 0 ? "FLAVOR_A" : "FLAVOR_H"));
        }

        CharacterAxisMetrics metrics = CharacterCardSelector.metrics(frames, START, END);

        assertThat(metrics.brightRatio()).isEqualByComparingTo("0.5");
        assertThat(metrics.ratingCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("대상자 30명 미만이면 고정 임계값을 쓴다")
    void fallsBackToFixedThresholdsBelowThirtySubjects() {
        // 중앙값이 임계값과 어긋나게 쏠린 모집단이어도 29명이면 고정 임계값으로 판정한다
        List<CharacterAxisMetrics> population = new ArrayList<>();
        for (int index = 0; index < 29; index++) {
            population.add(metrics(9, "0.95", "4.9", "0.1", 99));
        }

        CharacterAxisMedians medians = CharacterCardSelector.medians(population);

        assertThat(medians.subjectCount()).isEqualTo(29);
        assertThat(medians.fixedThreshold()).isTrue();
        assertThat(medians.averageScore()).isEqualByComparingTo("3.8");
        assertThat(medians.ratingCount()).isEqualByComparingTo("10");
        assertThat(medians.brightRatio()).isEqualByComparingTo("0.5");
        assertThat(medians.newFlavorCount()).isEqualByComparingTo("1");

        // 쏠린 중앙값(4.9 · 99편)이라면 M · S였을 사용자가 고정 임계값에서는 H · V가 된다
        assertThat(
                        CharacterCardSelector.select(
                                        metrics(2, "0.6", "4.0", "0.3", 20), medians, 20)
                                .code())
                .isEqualTo("ELHV");
    }

    @Test
    @DisplayName("대상자 30명이면 중앙값을 쓴다")
    void usesMedianAtThirtySubjects() {
        List<CharacterAxisMetrics> population = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            population.add(metrics(9, "0.95", "4.9", "0.1", 99));
        }

        CharacterAxisMedians medians = CharacterCardSelector.medians(population);

        assertThat(medians.subjectCount()).isEqualTo(30);
        assertThat(medians.fixedThreshold()).isFalse();
        assertThat(medians.averageScore()).isEqualByComparingTo("4.9");
        assertThat(medians.ratingCount()).isEqualByComparingTo("99");

        // 같은 사용자가 이번에는 중앙값 미만이라 뒷글자를 받는다
        assertThat(
                        CharacterCardSelector.select(
                                        metrics(2, "0.6", "4.0", "0.3", 20), medians, 20)
                                .code())
                .isEqualTo("RDMS");
    }

    @Test
    @DisplayName("고정 임계값 폴백에서는 평점 동값을 H로 둔다")
    void keepsHeartOnTieUnderFixedThreshold() {
        /*
         * 폴백에는 표준편차 기준값이 없다. 기준값 자리에 0을 넣고 비교하면 평점이 정확히 3.8인
         * 사용자가 표준편차 0이 아닌 한 전부 M이 되어 기본 규칙과 어긋난다.
         */
        List<CharacterAxisMetrics> population = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            population.add(metrics(2, "0.5", "3.8", "0.5", 20));
        }
        CharacterAxisMedians medians = CharacterCardSelector.medians(population);

        assertThat(medians.fixedThreshold()).isTrue();
        assertThat(
                        CharacterCardSelector.select(
                                        metrics(2, "0.5", "3.8", "0.9", 20), medians, 20)
                                .code()
                                .charAt(2))
                .isEqualTo('H');
    }

    @Test
    @DisplayName("밝은 맛 비중이 없는 사용자는 TONE 중앙값 모집단에서 뺀다")
    void excludesUndefinedBrightRatioFromTonePopulation() {
        // 중립 맛만 본 사용자는 넣을 값이 없다(플랜 7장). 나머지 값으로만 중앙값을 낸다
        List<CharacterAxisMetrics> population = new ArrayList<>();
        for (int index = 0; index < 15; index++) {
            population.add(metrics(2, "0.8", "3.5", "0.5", 20));
        }
        for (int index = 0; index < 15; index++) {
            population.add(metrics(2, null, "3.5", "0.5", 20));
        }

        CharacterAxisMedians medians = CharacterCardSelector.medians(population);

        assertThat(medians.fixedThreshold()).isFalse();
        assertThat(medians.brightRatio()).isEqualByComparingTo("0.8");
    }

    @Test
    @DisplayName("축 판정 결과를 코드 글자 순서대로 네 개 내린다")
    void reportsFourAxesInCodeOrder() {
        CharacterAxisMedians medians = medians(new BigDecimal("2"), "0.5", "3.5", "0.5", "20");

        CharacterCardContent card =
                CharacterCardSelector.select(metrics(1, "0.2", "3.0", "0.4", 5), medians, 5);

        assertThat(card.axes())
                .extracting("axis", "value", "label")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("EXPLORATION", "R", "정착"),
                        org.assertj.core.groups.Tuple.tuple("TONE", "D", "그림자"),
                        org.assertj.core.groups.Tuple.tuple("STANCE", "M", "머리"),
                        org.assertj.core.groups.Tuple.tuple("VOLUME", "S", "정독"));
        assertThat(card.code()).isEqualTo("RDMS");
        assertThat(card.name()).isEqualTo("금고지기");
    }

    @Test
    @DisplayName("프레임 10편 미만이면 소량 표본으로 알린다")
    void marksLowSampleBelowTenFrames() {
        CharacterAxisMedians medians = medians(new BigDecimal("2"), "0.5", "3.5", "0.5", "20");
        CharacterAxisMetrics metrics = metrics(2, "0.5", "3.5", "0.4", 7);

        assertThat(CharacterCardSelector.select(metrics, medians, 7).basis().frameCount())
                .isEqualTo(7);
        assertThat(CharacterCardSelector.select(metrics, medians, 7).basis().lowSample()).isTrue();
        assertThat(CharacterCardSelector.select(metrics, medians, 10).basis().lowSample())
                .isFalse();
    }

    @Test
    @DisplayName("새로운 맛은 반기 이전 프레임에 없던 맛만 센다")
    void countsOnlyFlavorsAbsentBeforeThePeriod() {
        List<ReportSource.Frame> frames =
                List.of(
                        // 반기 이전에 이미 본 맛
                        frameAt(1, "4.0", "FLAVOR_C", Instant.parse("2025-11-01T00:00:00Z")),
                        frame(2, "4.0", "FLAVOR_C"),
                        frame(3, "4.0", "FLAVOR_B"),
                        frame(4, "4.0", "FLAVOR_G"));

        CharacterAxisMetrics metrics = CharacterCardSelector.metrics(frames, START, END);

        assertThat(metrics.newFlavorCount()).isEqualTo(2);
        assertThat(metrics.ratingCount()).isEqualTo(3);
    }

    private static CharacterAxisMedians medians(
            BigDecimal newFlavorCount,
            String brightRatio,
            String averageScore,
            String scoreStdDev,
            String ratingCount) {
        return new CharacterAxisMedians(
                30,
                false,
                newFlavorCount,
                new BigDecimal(brightRatio),
                new BigDecimal(averageScore),
                new BigDecimal(scoreStdDev),
                new BigDecimal(ratingCount));
    }

    private static CharacterAxisMetrics metrics(
            int newFlavorCount,
            String brightRatio,
            String averageScore,
            String scoreStdDev,
            int ratingCount) {
        return new CharacterAxisMetrics(
                newFlavorCount,
                brightRatio == null ? null : new BigDecimal(brightRatio),
                new BigDecimal(averageScore),
                new BigDecimal(scoreStdDev),
                ratingCount);
    }

    private static ReportSource.Frame frame(long frameId, String score, String flavorCode) {
        return frameAt(frameId, score, flavorCode, Instant.parse("2026-03-01T00:00:00Z"));
    }

    private static ReportSource.Frame frameAt(
            long frameId, String score, String flavorCode, Instant createdAt) {
        return new ReportSource.Frame(
                frameId, 1L, frameId, new BigDecimal(score), createdAt, 2020, flavorCode, null);
    }
}
