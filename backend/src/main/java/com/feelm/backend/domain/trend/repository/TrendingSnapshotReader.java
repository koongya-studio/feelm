package com.feelm.backend.domain.trend.repository;

import com.feelm.backend.domain.trend.model.TrendingSnapshot;
import java.util.Optional;

/** 게시된 VIEW 급상승 스냅샷을 읽는다. 저장 위치는 구현이 감춘다. */
public interface TrendingSnapshotReader {

    /**
     * 최신 스냅샷을, 없으면 마지막 유효 스냅샷을 읽는다.
     *
     * @return 유효한 스냅샷. 게시된 결과가 아직 없으면 빈 값
     * @throws com.feelm.backend.global.error.BusinessException 조회 장애나 손상으로 게시된 결과를 제공할 수 없을
     *     때({@code TREND_UNAVAILABLE}). 초기 고정 목록으로 되돌리면 이미 보여 준 급상승이 사라지므로 오류로 알린다
     */
    Optional<TrendingSnapshot> read();
}
