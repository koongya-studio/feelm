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
 * API-068 OTT 구독 추천 `items[]`.
 *
 * 순위는 `matchCount`(취향에 맞는 미감상 영화 수) 내림차순이다. **계약이 주는 값은 이것이
 * 전부다.** 와이어프레임 EXT-03 · EXT-04의 일치도 % · 항목별 일치도 · 이유 문장은 화면에서
 * 뺐다 — Redis 점수가 상대 순위만 보장해 계약이 유사도나 일치 확률로 응답하지 않기로
 * 정했기 때문이다(업무 S15P21E106-563 · 지시서 CR-28).
 *
 * `topMovies`에 `similarity`를 두었던 것도 같은 이유로 지웠다. 계약에도 백엔드 DTO에도
 * 없는 필드였다.
 */
export type OttRecommendationItem = {
  ott: { ottId: number; code: string; name: string; logoUrl: string | null };
  /** TASTE 후보 최대 500편 중 이 OTT에서 볼 수 있는 미감상·미관심없음 영화 수 */
  matchCount: number;
  /** 내 구독 OTT(API-026) 여부 */
  subscribed: boolean;
  topMovies: {
    movieId: number;
    title: string;
    posterUrl: string | null;
    /** 개봉 연도를 모르는 영화가 있어 `null`이 온다(백엔드 DTO는 `Integer`) */
    releaseYear: number | null;
  }[];
};

/**
 * API-068 응답.
 *
 * `insufficientData`면 `items`가 비어 있다. 다만 계약은 현재 Candidate가 있으면 취향 입력이
 * 0건이어도 `false`를 주므로, 화면은 이 값과 `items`가 빈 경우를 함께 본다(EXT-05).
 */
export type OttRecommendation = {
  insufficientData: boolean;
  aggregatedAt: string;
  items: OttRecommendationItem[];
};
