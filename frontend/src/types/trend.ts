/*
 * 급상승 영화 타입. API-064
 * 계약의 사본이며 계약에 없는 필드를 넣지 않는다(frontend-structure.md 8.3).
 */

/** API-064 `items[]` */
export type TrendingItem = {
  rank: number;
  /** 이전 집계 대비 순위 변동. 양수면 상승, 음수면 하락, 0이면 그대로 */
  rankDelta: number;
  movie: { movieId: number; title: string; posterUrl: string | null };
  ratingCount: number;
  averageScore: number;
  trendScore: number;
};

/** API-064 급상승 영화 응답 */
export type TrendingList = {
  period: { from: string; to: string };
  updatedAt: string;
  items: TrendingItem[];
};
