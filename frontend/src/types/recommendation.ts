/*
 * 추천 타입. API-045 기본 추천 조회 · API-047 관심없음 표시
 * 계약의 사본이며 계약에 없는 필드를 넣지 않는다(frontend-structure.md 8.3).
 */
import type { RecommendationType } from './enums';
import type { RecommendedMovie } from './movie';

/** 추천 이유 근거. API-045 `reason.factors[]` */
export type RecommendationFactor = {
  type: 'GENRE' | 'DIRECTOR' | 'ACTOR' | 'COUNTRY' | 'ERA' | 'SIMILAR_MOVIE' | 'NEW_FLAVOR';
  label: string;
  movieId?: number;
};

export type RecommendationReason = {
  summary: string;
  factors: RecommendationFactor[];
};

export type RecommendationItem = {
  recommendationItemId: number;
  type: RecommendationType;
  movie: RecommendedMovie;
  /** 팝콘 맛 코드. 표시명과 색상은 API-044로 조회한다 */
  flavorCode: string;
  reason: RecommendationReason;
};

export type RecommendationSet = {
  recommendationSetId: number;
  generatedAt: string;
  items: RecommendationItem[];
};

/** API-047 응답. 교체 후보가 없으면 `replacement`가 null이다 */
export type DismissResult = {
  movieId: number;
  replacement: RecommendationItem | null;
};
