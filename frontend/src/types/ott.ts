/*
 * OTT 기준정보. API-025 OTT 목록 조회
 *
 * frontend-structure.md 8.1절의 타입 파일 목록에는 없던 파일이다. OTT 기준정보를
 * 온보딩 타입에 섞지 않기 위해 분리했다.
 */

export type Ott = {
  ottId: number;
  /** OTT 코드. 예: NETFLIX */
  code: string;
  /** 표시 이름. 예: 넷플릭스 */
  name: string;
  logoUrl: string | null;
};

/**
 * API-068 OTT 구독 추천 `items[]`. **확장 단계라 아직 없다.**
 *
 * 순위는 `matchCount`(취향에 맞는 미감상 영화 수) 내림차순이다. 와이어프레임 EXT-03 · EXT-04가
 * 그리는 일치도 % · 항목별 일치도 · 이유 문장은 계약에 없다(`types/unspecified.ts`).
 */
export type OttRecommendationItem = {
  ott: { ottId: number; code: string; name: string; logoUrl: string | null };
  matchCount: number;
  subscribed: boolean;
  topMovies: {
    movieId: number;
    title: string;
    posterUrl: string | null;
    releaseYear: number;
    similarity: number;
  }[];
};

/** API-068 응답. `insufficientData`면 `items`가 비어 있다 */
export type OttRecommendation = {
  insufficientData: boolean;
  aggregatedAt: string;
  items: OttRecommendationItem[];
};
