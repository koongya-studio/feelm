/*
 * 취향 분석 타입. API-040 취향 분석·키워드 조회
 *
 * 홈(2.1)의 `내 취향 요약`은 장르 집계 1위와 키워드 1개만 쓴다. 국가·시대·감독
 * 집계는 취향 필름(3.1)과 MY 화면에서 필요해질 때 추가한다.
 */

export type TasteGenre = {
  code: string;
  name: string;
  count: number;
  averageScore: number;
  /** 비율. 0~1 */
  share: number;
};

export type TasteKeyword = {
  code: string;
  label: string;
};

export type TasteAnalysis = {
  ratingCount: number;
  /** 평가가 기준 미만이면 true이고 `keywords`가 빈 배열이다 */
  insufficientData: boolean;
  analysis: {
    genres: TasteGenre[];
  };
  keywords: TasteKeyword[];
  analyzedAt: string;
};
