package com.feelm.backend.domain.trend.model;

/**
 * 급상승 목록의 출처(API-064).
 *
 * <p>{@code DEFAULT}는 유효한 VIEW 집계가 한 번도 게시되지 않았을 때 보여 주는 고정 초기 목록 A이고, {@code VIEW}는 Spark가 게시한 집계
 * 결과 B다. 첫 B 이후에는 A로 되돌아가지 않는다.
 */
public enum TrendingSource {
    DEFAULT,
    VIEW
}
