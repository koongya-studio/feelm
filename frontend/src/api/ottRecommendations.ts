/*
 * OTT 구독 추천(EXT-01 ~ EXT-05) 접근 함수. 대응 명세는 docs/api/recommendation.md API-068이다.
 *
 * 목업을 걷어내고 실제 API를 부른다(업무 S15P21E106-564). 계약이 주는 값은
 * `matchCount` · `subscribed` · `topMovies`가 전부다. 와이어프레임의 일치도 % · 추천 이유 ·
 * 항목별 일치도와 EXT-01 분석 요약은 화면에서 뺐으므로(업무 S15P21E106-563 · 지시서 CR-28)
 * 그 값을 만들던 `getOttAnalysisBasis`와 목업 입력 `?mock=ott-insufficient`도 함께 사라졌다.
 * EXT-01이 쓰던 `평가 N개`는 API-014 `stats.ratingCount`라 `api/users.ts`가 맡는다.
 */
import { ApiError } from './apiError';
import { request } from './client';
import type { OttRecommendation, OttRecommendationItem } from '../types/ott';

/**
 * API-068 OTT 구독 추천 · GET /api/v1/recommendations/otts
 *
 * 순위와 구독 여부 모두 서버가 정한 값이다. 목업 시절에는 구독 여부만 API-026으로 따로
 * 채웠는데, 계약이 `subscribed`를 내려주므로 화면은 응답을 그대로 그린다.
 *
 * 정렬도 다시 하지 않는다. 계약이 `matchCount` 내림차순·`ottId` 오름차순으로 정해 두었고,
 * 화면이 한 번 더 정렬하면 동점일 때 순서가 서버와 갈린다.
 *
 * 후보가 없거나 Redis를 읽지 못하면 `503 RECOMMENDATION_UNAVAILABLE`이다.
 */
export async function getOttRecommendations(): Promise<OttRecommendation> {
  return request<OttRecommendation>('/recommendations/otts');
}

/**
 * EXT-04 한 곳. **계약에 단건 조회가 없어 목록에서 찾는다.**
 *
 * 단건 계약을 새로 만들지 않기로 했다(업무 S15P21E106-563). 목록이 OTT 다섯 종뿐이고,
 * EXT-04는 EXT-03에서 들어오는 화면이라 같은 응답을 한 번 더 받는 비용이 작다.
 *
 * 없는 `ottId`로 들어오면 계약 공통 규약의 `404 RESOURCE_NOT_FOUND` 모양으로 만들어
 * 던진다. 서버가 준 오류가 아니지만 화면은 실패를 한 가지 방법으로만 다루면 된다.
 */
export async function getOttRecommendation(ottId: number): Promise<OttRecommendationItem> {
  const { items } = await getOttRecommendations();
  const item = items.find((candidate) => candidate.ott.ottId === ottId);

  if (!item) {
    throw new ApiError(404, 'RESOURCE_NOT_FOUND', 'OTT 추천을 찾을 수 없습니다.');
  }

  return item;
}
