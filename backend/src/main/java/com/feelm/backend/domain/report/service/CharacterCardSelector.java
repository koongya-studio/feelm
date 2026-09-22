package com.feelm.backend.domain.report.service;

import com.feelm.backend.domain.report.model.CharacterAxis;
import com.feelm.backend.domain.report.model.CharacterAxisMedians;
import com.feelm.backend.domain.report.model.CharacterAxisMetrics;
import com.feelm.backend.domain.report.model.CharacterCard;
import com.feelm.backend.domain.report.model.FlavorTone;
import com.feelm.backend.domain.report.model.ReportContent.AxisJudgement;
import com.feelm.backend.domain.report.model.ReportContent.CharacterBasis;
import com.feelm.backend.domain.report.model.ReportContent.CharacterCardContent;
import com.feelm.backend.domain.report.model.ReportSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 캐릭터 카드 16종 중 하나를 배정한다(API-060 처리 규칙, 업무 -618).
 *
 * <p>판정은 <b>그 반기에 리포트가 생성된 사용자 전체</b>를 모집단으로 한 상대 판정이다. 기본 규칙은 값이 중앙값 이상이면 앞글자(E · L · H · V)이고, 축별
 * 예외가 셋 있다.
 *
 * <ul>
 *   <li>EXPLORATION — 중앙값이 0이면 상대 판정이 전원 E가 된다. 그때만 새 맛 1개 이상을 E로 내린다
 *   <li>TONE — 분모가 0이면(중립 맛만 감상) L을 기본값으로 둔다
 *   <li>STANCE — 값이 중앙값과 같으면 평점 표준편차가 작은 쪽을 H로 둔다
 * </ul>
 *
 * <p>모집단이 30명 미만이면 중앙값이 한두 명에 흔들리므로 사전 정의한 고정 임계값으로 떨어진다.
 *
 * <p>조립기가 아니라 별도 클래스로 둔 이유가 있다. {@code ReportContentAssembler}를 업무 -774가 동시에 크게 고치는 중이라(플랜 5장) 판정
 * 로직을 여기에 모아 두면 그쪽과 겹치는 줄이 호출 한 줄로 줄어든다.
 */
public final class CharacterCardSelector {

    /** 모집단이 이 수 미만이면 중앙값 대신 고정 임계값을 쓴다 */
    private static final int MIN_SUBJECTS = 30;

    private static final BigDecimal FIXED_NEW_FLAVOR_COUNT = BigDecimal.ONE;
    private static final BigDecimal FIXED_BRIGHT_RATIO = new BigDecimal("0.5");
    private static final BigDecimal FIXED_AVERAGE_SCORE = new BigDecimal("3.8");
    private static final BigDecimal FIXED_RATING_COUNT = BigDecimal.TEN;

    /** 프레임이 이 편수 미만이면 소량 표본으로 알린다 */
    private static final int LOW_SAMPLE_FRAMES = 10;

    private static final int RATIO_SCALE = 4;

    private CharacterCardSelector() {}

    /**
     * 한 사용자의 네 축 지표. {@code frames}는 {@code end} 이전의 프레임 전체이고, 이 중 {@code [start, end)} 구간이 그 반기다.
     * 새로운 맛은 {@code start} 이전에 없던 맛을 뜻하므로 과거 프레임도 함께 받는다.
     */
    public static CharacterAxisMetrics metrics(
            List<ReportSource.Frame> frames, Instant start, Instant end) {
        List<ReportSource.Frame> current =
                frames.stream()
                        .filter(
                                frame ->
                                        !frame.createdAt().isBefore(start)
                                                && frame.createdAt().isBefore(end))
                        .toList();
        Set<String> historical =
                frames.stream()
                        .filter(frame -> frame.createdAt().isBefore(start))
                        .map(ReportSource.Frame::flavorCode)
                        .filter(code -> code != null)
                        .collect(Collectors.toSet());

        Set<String> newFlavors = new HashSet<>();
        int bright = 0;
        int dark = 0;
        for (ReportSource.Frame frame : current) {
            if (frame.flavorCode() != null && !historical.contains(frame.flavorCode())) {
                newFlavors.add(frame.flavorCode());
            }
            switch (FlavorTone.of(frame.flavorCode())) {
                case BRIGHT -> bright++;
                case DARK -> dark++;
                case NEUTRAL -> {}
            }
        }

        int toned = bright + dark;
        // 중립 맛만 봤으면 비중이 정의되지 않는다. 판정은 L을 받고 중앙값 모집단에서는 빠진다
        BigDecimal brightRatio =
                toned == 0
                        ? null
                        : BigDecimal.valueOf(bright)
                                .divide(
                                        BigDecimal.valueOf(toned),
                                        RATIO_SCALE,
                                        RoundingMode.HALF_UP);

        return new CharacterAxisMetrics(
                newFlavors.size(),
                brightRatio,
                averageScore(current),
                scoreStdDev(current),
                current.size());
    }

    /**
     * 모집단의 축별 기준값. 30명 미만이면 고정 임계값을 돌려준다.
     *
     * <p>TONE은 분모가 0인 사용자를 모집단에서 뺀다 — 넣을 값이 없다. 빼고 나서 아무도 남지 않으면 그 축만 고정 임계값을 쓴다(플랜 7장).
     */
    public static CharacterAxisMedians medians(Collection<CharacterAxisMetrics> population) {
        int subjectCount = population.size();
        if (subjectCount < MIN_SUBJECTS) {
            return new CharacterAxisMedians(
                    subjectCount,
                    true,
                    FIXED_NEW_FLAVOR_COUNT,
                    FIXED_BRIGHT_RATIO,
                    FIXED_AVERAGE_SCORE,
                    // 폴백에는 표준편차 기준값이 없다. heart()가 이 값을 쓰지 않고 동값을 H로 둔다
                    BigDecimal.ZERO,
                    FIXED_RATING_COUNT);
        }

        List<BigDecimal> brightRatios =
                population.stream()
                        .map(CharacterAxisMetrics::brightRatio)
                        .filter(ratio -> ratio != null)
                        .sorted()
                        .toList();

        return new CharacterAxisMedians(
                subjectCount,
                false,
                median(population, metrics -> BigDecimal.valueOf(metrics.newFlavorCount())),
                brightRatios.isEmpty() ? FIXED_BRIGHT_RATIO : median(brightRatios),
                median(population, CharacterAxisMetrics::averageScore),
                median(population, CharacterAxisMetrics::scoreStdDev),
                median(population, metrics -> BigDecimal.valueOf(metrics.ratingCount())));
    }

    /** 네 축을 판정해 카드 한 장과 응답 조각을 만든다. {@code frameCount}는 그 반기 프레임 편수다 */
    public static CharacterCardContent select(
            CharacterAxisMetrics metrics, CharacterAxisMedians medians, int frameCount) {
        boolean exploring = exploring(metrics, medians);
        boolean bright = bright(metrics, medians);
        boolean heart = heart(metrics, medians);
        boolean voluminous =
                atLeast(BigDecimal.valueOf(metrics.ratingCount()), medians.ratingCount());

        CharacterCard card = CharacterCard.of(exploring, bright, heart, voluminous);
        List<AxisJudgement> axes =
                List.of(
                        judgement(CharacterAxis.EXPLORATION, exploring),
                        judgement(CharacterAxis.TONE, bright),
                        judgement(CharacterAxis.STANCE, heart),
                        judgement(CharacterAxis.VOLUME, voluminous));

        return new CharacterCardContent(
                card.code(),
                card.cardName(),
                card.tagline(),
                card.description(),
                axes,
                new CharacterBasis(frameCount, frameCount < LOW_SAMPLE_FRAMES));
    }

    /** 중앙값이 0이면 상대 판정이 전원 E가 된다. 그때만 새 맛 1개 이상을 E로 내린다 */
    private static boolean exploring(CharacterAxisMetrics metrics, CharacterAxisMedians medians) {
        if (medians.newFlavorCount().signum() == 0) {
            return metrics.newFlavorCount() >= 1;
        }
        return atLeast(BigDecimal.valueOf(metrics.newFlavorCount()), medians.newFlavorCount());
    }

    /** 분모가 0이면(중립 맛만 감상) L을 기본값으로 둔다 */
    private static boolean bright(CharacterAxisMetrics metrics, CharacterAxisMedians medians) {
        return metrics.brightRatio() == null
                || atLeast(metrics.brightRatio(), medians.brightRatio());
    }

    /**
     * 값이 중앙값과 같으면 평점 표준편차가 작은 쪽을 H로 둔다.
     *
     * <p>고정 임계값 폴백에는 표준편차 기준값이 없다. 계약이 폴백에 정한 값은 평점 3.8 · 편수 10 · 비중 0.5 · 새 맛 1개뿐이다. 기준값 자리에 0을
     * 넣으면 평점이 정확히 3.8인 사용자가 표준편차 0이 아닌 한 전부 M으로 떨어져 <b>기본 규칙(중앙값 이상이면 앞글자)과 어긋난다.</b> 그래서 폴백에서는 동값을
     * 기본 규칙대로 H로 둔다.
     */
    private static boolean heart(CharacterAxisMetrics metrics, CharacterAxisMedians medians) {
        int compared = metrics.averageScore().compareTo(medians.averageScore());
        if (compared != 0) {
            return compared > 0;
        }
        if (medians.fixedThreshold()) {
            return true;
        }
        return metrics.scoreStdDev().compareTo(medians.scoreStdDev()) <= 0;
    }

    private static AxisJudgement judgement(CharacterAxis axis, boolean head) {
        return new AxisJudgement(axis.name(), axis.value(head), axis.label(head));
    }

    private static boolean atLeast(BigDecimal value, BigDecimal median) {
        return value.compareTo(median) >= 0;
    }

    private static BigDecimal median(
            Collection<CharacterAxisMetrics> population,
            Function<CharacterAxisMetrics, BigDecimal> extractor) {
        return median(
                population.stream().map(extractor).sorted(Comparator.naturalOrder()).toList());
    }

    /** 정렬된 값의 중앙값. 개수가 짝수면 가운데 둘의 평균이다 */
    private static BigDecimal median(List<BigDecimal> sorted) {
        int size = sorted.size();
        int middle = size / 2;
        if (size % 2 == 1) {
            return sorted.get(middle);
        }
        return sorted.get(middle - 1)
                .add(sorted.get(middle))
                .divide(BigDecimal.valueOf(2), RATIO_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal averageScore(List<ReportSource.Frame> frames) {
        if (frames.isEmpty()) {
            return BigDecimal.ZERO.setScale(1);
        }
        BigDecimal sum =
                frames.stream()
                        .map(ReportSource.Frame::score)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(frames.size()), 1, RoundingMode.HALF_UP);
    }

    /** 모집단 표준편차. 동값 판정에만 쓰므로 표본 보정을 하지 않는다 */
    private static BigDecimal scoreStdDev(List<ReportSource.Frame> frames) {
        if (frames.size() < 2) {
            return BigDecimal.ZERO.setScale(RATIO_SCALE);
        }
        BigDecimal mean =
                frames.stream()
                        .map(ReportSource.Frame::score)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(
                                BigDecimal.valueOf(frames.size()),
                                RATIO_SCALE,
                                RoundingMode.HALF_UP);
        BigDecimal variance = BigDecimal.ZERO;
        for (ReportSource.Frame frame : frames) {
            BigDecimal deviation = frame.score().subtract(mean);
            variance = variance.add(deviation.multiply(deviation));
        }
        variance =
                variance.divide(
                        BigDecimal.valueOf(frames.size()), RATIO_SCALE, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue()))
                .setScale(RATIO_SCALE, RoundingMode.HALF_UP);
    }
}
