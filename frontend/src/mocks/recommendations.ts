/*
 * 추천 세트 목업. API-045 기본 추천 조회 · API-047 관심없음 표시
 *
 * 구성은 계약을 따라 TASTE 2 + DISCOVERY 1 = 3편이다(API-045 처리 규칙,
 * screen-spec.md 8.1 #3). 와이어프레임 2.2는 3 + 2 = 5장이지만 계약이 기준이다.
 *
 * `matchRate`는 계약에 없는 필드다(CR-13). 와이어프레임의 수치를 그대로 옮겼다.
 * 2.2 첫 카드 `일치 92%` · 둘째 `일치 88%` · 취향 발견 `연결 72%` ·
 * 2.3-A 교체 후보 `일치 87%`
 */
import { findMockMovie } from './movies';
import type { RecommendationItemView } from '../types/unspecified';

const items: RecommendationItemView[] = [
  {
    recommendationItemId: 3301,
    type: 'TASTE',
    movie: findMockMovie(101),
    flavorCode: 'FLAVOR_CAPER',
    reason: {
      // 2.1이 표시하는 문구. 2.2 카드의 `추천 이유:` 자리에도 같은 값을 쓴다
      summary: '좋아한 케이퍼 무비와 가까워요',
      factors: [{ type: 'GENRE', label: '케이퍼' }],
    },
    matchRate: 92,
  },
  {
    recommendationItemId: 3302,
    type: 'TASTE',
    movie: findMockMovie(102),
    flavorCode: 'FLAVOR_THRILL',
    reason: {
      summary: '스릴러를 높게 평가하셨어요',
      factors: [{ type: 'GENRE', label: '스릴러' }],
    },
    matchRate: 88,
  },
  {
    recommendationItemId: 3303,
    type: 'DISCOVERY',
    movie: findMockMovie(103),
    flavorCode: 'FLAVOR_NOIR',
    reason: {
      summary: '좋아하는 스릴러와 연결되는 첫 느와르 영화예요',
      factors: [{ type: 'NEW_FLAVOR', label: '느와르' }],
    },
    matchRate: 72,
  },
];

/** 관심없음 처리 시 같은 유형의 다음 후보로 승격되는 버퍼 (API-047 처리 규칙) */
const replacementBuffer: RecommendationItemView[] = [
  {
    recommendationItemId: 3305,
    type: 'TASTE',
    movie: findMockMovie(104),
    flavorCode: 'FLAVOR_CRIME',
    reason: {
      summary: '범죄 드라마를 자주 보셨어요',
      factors: [{ type: 'GENRE', label: '범죄' }],
    },
    matchRate: 87,
  },
];

export const mockRecommendationSet = {
  recommendationSetId: 7001,
  generatedAt: '2026-09-07T00:00:00Z',
  items,
};

/**
 * 같은 유형의 교체 후보 1편. 없으면 null이며 계약도 `replacement: null`을 200으로
 * 반환한다(API-047).
 */
export function findMockReplacement(type: RecommendationItemView['type']) {
  return replacementBuffer.find((item) => item.type === type) ?? null;
}
