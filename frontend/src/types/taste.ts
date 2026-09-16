/*
 * 취향 분석 타입. API-040 취향 분석·키워드 조회
 *
 * 홈(2.1)의 `내 취향 요약`은 장르 집계 1위와 키워드 1개만 쓴다. 국가·시대·감독
 * 집계는 취향 필름(3.1)과 MY 화면에서 필요해질 때 추가한다. 3.1은 키워드만 쓰므로
 * 아직 장르 집계만 둔다.
 *
 * 필름(프레임)은 API-042 · API-043이 같은 도메인 문서(docs/api/taste.md)에 있어
 * 이 파일에 함께 둔다.
 */
import type { Visibility } from './enums';

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

/**
 * API-041 팝콘 버킷 조회 `flavors[]`
 *
 * `isNewFlavor`는 두지 않는다. 계약이 감상량 임계값과 임계값 기반 `isNewFlavor`를 쓰지
 * 않기로 했고(API-041 처리 규칙) 응답에도 없다.
 */
export type PopcornBucketFlavor = {
  flavorCode: string;
  displayName: string;
  color: string;
  popcornCount: number;
  averageScore: number | null;
  /** 확장 기능 FR-32용 예약 필드. 1차에서는 null 가능 */
  discoveredAt: string | null;
};

/** API-041 팝콘 버킷 조회 */
export type PopcornBucket = {
  /** 팝콘 알 총 수 */
  totalCount: number;
  /**
   * 계약은 8개 맛을 항상 모두 포함한다고 정하지만, 기준정보가 적재되기 전에는 빈
   * 배열이 온다(S15P21E106-392). 화면은 비어 있는 경우를 견뎌야 한다.
   */
  flavors: PopcornBucketFlavor[];
};

/** API-042 내 필름(프레임 목록) 조회 `items[]` */
export type FrameSummary = {
  frameId: number;
  movie: {
    movieId: number;
    title: string;
    posterUrl: string | null;
  };
  score: number;
  /**
   * 영화의 대표 맛이다. **대표 맛이 배정되지 않은 영화는 `null`이다** — 백엔드가
   * `popcorn_flavors`를 LEFT JOIN한다. 계약 예시에는 null이 없어 불일치로 남긴다
   * (S15P21E106-392).
   */
  flavorCode: string | null;
  createdAt: string;
};

/**
 * API-042 내 필름(프레임 목록) 조회
 *
 * 프레임은 평가와 1:1이며 최신순이다. 목록은 cursor 방식이므로 `hasNext`가 true면
 * `nextCursor`로 이어 받는다. 3.1이 스트립에서 몇 개까지 보여주는지는 미확정이다
 * (screen-spec.md 3.1 미확정).
 */
export type FrameList = {
  /** 누적 프레임 수 */
  totalCount: number;
  items: FrameSummary[];
  nextCursor: string | null;
  hasNext: boolean;
};

/**
 * API-043 프레임 상세 조회
 *
 * 감상 기록 없이 평가한 경우 `watchRecord`의 `ott` · `clickedAt`이 `null`이다
 * (API-043 처리 규칙). `watchRecord` 자체는 항상 온다.
 */
export type FrameDetail = {
  frameId: number;
  movie: {
    movieId: number;
    title: string;
    posterUrl: string | null;
    /** 개봉일이 없는 영화가 있어 `null`이 온다 */
    releaseYear: number | null;
    /** 장르명 문자열이다. API-030 상세의 `{ code, name }`과 형태가 다르다 */
    genres: string[];
    directors: string[];
  };
  /** 대표 맛이 배정되지 않은 영화는 `null`이다. `FrameSummary.flavorCode` 참고 */
  flavorCode: string | null;
  rating: {
    ratingId: number;
    score: number;
    comment: string | null;
    visibility: Visibility;
  };
  watchRecord: {
    ott: { code: string; name: string } | null;
    clickedAt: string | null;
    watchedAt: string | null;
    ratedAt: string | null;
  };
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
