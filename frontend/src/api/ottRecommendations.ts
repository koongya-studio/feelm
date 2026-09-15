/*
 * OTT 구독 추천(EXT-01 ~ EXT-05) 접근 함수. 대응 명세는 docs/api/recommendation.md API-068이다.
 *
 * **확장 단계라 아직 없다.** 목업으로 응답한다. 계약은 `matchCount`(편수)만 주고, 와이어프레임의
 * 일치도 % · 이유 · 항목별 일치도는 `types/unspecified.ts`에 있다(팀 결정 2026-09-13).
 *
 * 목업 확인용 입력 `?mock=ott-insufficient`는 EXT-05(평가 데이터 부족)를 보여준다.
 * `?mock=error`(2.2-A)와 같은 방식이며 실제 연동 시 제거한다.
 */
import {
  MOCK_INSUFFICIENT_RATING_COUNT,
  MOCK_OTT_AGGREGATED_AT,
  mockOttAnalysisBasis,
  mockOttRecommendationItems,
} from '../mocks/ottRecommendations';
import { getMyOttIds } from './otts';
import { respond, respondError } from './mockTransport';
import type {
  OttAnalysisBasis,
  OttRecommendationItemView,
  OttRecommendationView,
} from '../types/unspecified';

function isInsufficientMock(): boolean {
  if (typeof window === 'undefined') return false;

  return new URLSearchParams(window.location.search).get('mock') === 'ott-insufficient';
}

/**
 * EXT-01 분석 요약 · EXT-05 진행도. **대응 API가 없다.**
 * 평가 수가 `requiredRatingCount`보다 적으면 API-068이 `insufficientData: true`를 준다.
 */
export async function getOttAnalysisBasis(): Promise<OttAnalysisBasis> {
  return respond(
    isInsufficientMock()
      ? { ...mockOttAnalysisBasis, ratingCount: MOCK_INSUFFICIENT_RATING_COUNT }
      : mockOttAnalysisBasis,
  );
}

/**
 * API-068 OTT 구독 추천 · GET /api/v1/recommendations/otts
 *
 * 순위는 계약대로 `matchCount` 내림차순이다. 구독 여부는 API-026(6.1에서 바꾼 값)이다.
 */
export async function getOttRecommendations(): Promise<OttRecommendationView> {
  if (isInsufficientMock()) {
    return respond(
      { insufficientData: true, aggregatedAt: MOCK_OTT_AGGREGATED_AT, items: [] },
      'recommendations',
    );
  }

  const subscribedIds = await getMyOttIds();
  const items: OttRecommendationItemView[] = mockOttRecommendationItems
    .map((item) => ({ ...item, subscribed: subscribedIds.includes(item.ott.ottId) }))
    .sort((a, b) => b.matchCount - a.matchCount);

  return respond(
    { insufficientData: false, aggregatedAt: MOCK_OTT_AGGREGATED_AT, items },
    'recommendations',
  );
}

/** EXT-04 한 곳. API-068 목록에서 찾는다 — 계약에 단건 조회가 없다 */
export async function getOttRecommendation(ottId: number): Promise<OttRecommendationItemView> {
  const { items } = await getOttRecommendations();
  const item = items.find((candidate) => candidate.ott.ottId === ottId);

  if (!item) {
    return respondError(404, 'RESOURCE_NOT_FOUND', 'OTT 추천을 찾을 수 없습니다.');
  }

  return item;
}
