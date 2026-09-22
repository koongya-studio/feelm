package com.feelm.backend.domain.report.model;

import java.math.BigDecimal;

/**
 * 축별 판정 기준 스냅샷. 그 반기에 리포트가 생성된 사용자 전체를 모집단으로 집계 배치가 한 번 구한다.
 *
 * <p>리포트가 생성 시점 스냅샷 계약이므로(API-060 처리 규칙) 조회할 때 다시 계산하지 않는다. 같은 반기의 모든 리포트가 같은 값을 쓴다.
 *
 * <p><b>응답에 내리지 않는다.</b> {@code ReportDetailResponse}가 필드를 하나씩 나열하므로 {@code content}의 이 필드는 응답에 새지
 * 않는다. 덕분에 새 테이블도 마이그레이션도 필요 없다.
 *
 * @param subjectCount 모집단 크기. 30 미만이면 고정 임계값으로 떨어진다
 * @param fixedThreshold 고정 임계값 폴백을 적용했는지
 * @param newFlavorCount {@code EXPLORATION} 기준값
 * @param brightRatio {@code TONE} 기준값. 분모가 0인 사용자는 모집단에서 뺀다
 * @param averageScore {@code STANCE} 기준값
 * @param scoreStdDev {@code STANCE} 동값 판정 기준값
 * @param ratingCount {@code VOLUME} 기준값
 */
public record CharacterAxisMedians(
        int subjectCount,
        boolean fixedThreshold,
        BigDecimal newFlavorCount,
        BigDecimal brightRatio,
        BigDecimal averageScore,
        BigDecimal scoreStdDev,
        BigDecimal ratingCount) {}
