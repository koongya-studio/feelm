package com.feelm.backend.domain.party.dto.response;

import com.feelm.backend.domain.recommendation.dto.response.RecommendationMovieResponse;
import java.time.OffsetDateTime;

/**
 * API-083 파티 추천 이력의 항목 하나.
 *
 * <p>API-058의 {@link PartyRecommendationItemResponse}에 {@code exposedAt}을 더한 모양이지만 그 레코드를 바꾸지 않고 따로
 * 둔다 — API-058 계약을 건드리지 않기 위해서다. 다시 받기로 한 번에 노출된 항목들은 같은 {@code exposedAt}을 가지므로 화면이 묶음으로 나눌 수 있다.
 *
 * @param movie API-058과 같은 영화 요약. 장르·평점·OTT는 조회 시점 값이며 이력 스냅샷이 아니다
 */
public record PartyRecommendationHistoryItemResponse(
        long recommendationItemId,
        String type,
        OffsetDateTime exposedAt,
        RecommendationMovieResponse movie) {}
