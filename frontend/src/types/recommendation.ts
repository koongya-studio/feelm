/*
 * 추천 타입. API-045 기본 추천 조회 · API-047 관심없음 표시 · API-048 추천 이력 조회
 * 계약의 사본이며 계약에 없는 필드를 넣지 않는다(frontend-structure.md 8.3).
 */
import type { RecommendationType, WatchStatus } from './enums';
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

/**
 * API-048 추천 이력 `items[].items[]`
 *
 * API-045 항목과 모양이 다르다. 영화는 제목 · 포스터만 오고 이유는 `summary` 하나다.
 * 이력은 생성 시점 스냅샷이라 당시 이유를 그대로 보존한다(API-048 처리 규칙).
 */
export type RecommendationHistoryItem = {
  recommendationItemId: number;
  type: RecommendationType;
  movie: { movieId: number; title: string; posterUrl: string | null };
  reason: { summary: string };
  dismissed: boolean;
  watchStatus: WatchStatus | null;
};

/** API-048 추천 이력 `items[]` — 추천받은 세트 하나 */
export type RecommendationHistorySet = {
  recommendationSetId: number;
  generatedAt: string;
  items: RecommendationHistoryItem[];
};

/** API-048 응답. 페이지는 세트 단위다 */
export type RecommendationHistory = {
  items: RecommendationHistorySet[];
  nextCursor: string | null;
  hasNext: boolean;
};

/** API-047 응답. 교체 후보가 없으면 `replacement`가 null이다 */
export type DismissResult = {
  movieId: number;
  replacement: RecommendationItem | null;
};
