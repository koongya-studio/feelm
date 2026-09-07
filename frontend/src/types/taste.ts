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

/**
 * 팝콘 맛 기준정보. API-044 팝콘 맛 기준정보 조회
 *
 * `code`는 불변이고 `displayName`·`color`는 바뀔 수 있다(FR-06). 화면은 코드로
 * 매칭하고 표시명·색상을 하드코딩하지 않는다.
 */
export type PopcornFlavor = {
  code: string;
  displayName: string;
  color: string;
  representativeKeywords: string[];
  sortOrder: number;
};

/** API-041 팝콘 버킷 조회 `flavors[]` */
export type PopcornBucketFlavor = {
  flavorCode: string;
  displayName: string;
  color: string;
  popcornCount: number;
  averageScore: number | null;
  isNewFlavor: boolean;
  discoveredAt: string | null;
};

/** API-041 팝콘 버킷 조회 */
export type PopcornBucket = {
  /** 팝콘 알 총 수 */
  totalCount: number;
  newFlavorThreshold: number;
  /** 8개 맛을 항상 모두 포함한다 */
  flavors: PopcornBucketFlavor[];
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
