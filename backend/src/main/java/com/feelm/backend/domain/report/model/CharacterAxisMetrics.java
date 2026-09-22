package com.feelm.backend.domain.report.model;

import java.math.BigDecimal;

/**
 * 한 사용자의 네 축 지표. 전부 그 반기 프레임에서 나온다(API-060 처리 규칙).
 *
 * @param newFlavorCount 새로운 맛 편수. {@code summary.newFlavorCount}와 같다
 * @param brightRatio 밝은 맛 비중. 중립 맛만 봐서 분모가 0이면 <b>null</b>이다
 * @param averageScore 평균 별점. {@code summary.averageScore}와 같다
 * @param scoreStdDev 평점 표준편차. {@code STANCE}가 중앙값과 같을 때의 동값 판정에만 쓴다
 * @param ratingCount 감상 편수. {@code summary.ratingCount}와 같다
 */
public record CharacterAxisMetrics(
        int newFlavorCount,
        BigDecimal brightRatio,
        BigDecimal averageScore,
        BigDecimal scoreStdDev,
        int ratingCount) {}
