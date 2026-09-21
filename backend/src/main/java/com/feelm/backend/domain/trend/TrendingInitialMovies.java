package com.feelm.backend.domain.trend;

import java.util.List;

/**
 * 유효한 VIEW 집계가 한 번도 게시되지 않았을 때 보여 주는 초기 고정 목록 A(API-064).
 *
 * <p>순서까지 사용자가 확정한 값이다(S15P21E106-560 · 561의 2026-09-19 확정 정책). 목록의 위치가 곧 {@code rank}이며 KOBIS 등 외부
 * 순위를 초기 목록에 쓰지 않는다. 첫 유효 집계 B가 게시되면 이 목록은 다시 쓰이지 않는다.
 */
public final class TrendingInitialMovies {

    /** 표시 순서대로의 서비스 {@code movieId}. 1번째 원소가 {@code rank} 1이다. */
    private static final List<Long> MOVIE_IDS =
            List.of(
                    134626L, // 오디세이
                    124813L, // 왕과 사는 남자
                    86995L, // 스파이더맨: 브랜드 뉴 데이
                    80719L, // 프로젝트 헤일메리
                    55230L, // 인턴
                    36815L, // 옵세션
                    17057L, // 타짜
                    79065L, // 스파이더맨: 노 웨이 홈
                    522L, // 트로이
                    68176L // 스파이더맨: 파 프롬 홈
                    );

    private TrendingInitialMovies() {}

    public static List<Long> movieIds() {
        return MOVIE_IDS;
    }
}
