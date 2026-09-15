/*
 * 영화 타입.
 * 계약의 사본이며 계약에 없는 필드를 넣지 않는다(frontend-structure.md 8.3).
 */
import type { RecommendationType } from './enums';
import type { WatchStatus, Visibility } from './enums';

/**
 * 추천 응답에 포함되는 영화 요약. API-045 `items[].movie`
 *
 * `genres`는 문자열 배열이다. API-030 영화 상세의 `genres`는
 * `{ code, name }` 객체 배열이므로 형태가 다르다.
 */
export type RecommendedMovie = {
  movieId: number;
  title: string;
  posterUrl: string | null;
  releaseYear: number;
  genres: string[];
  averageRating: number;
  /** 제공 OTT 코드 목록. 표시 이름은 API-025 기준정보에서 찾는다 */
  ottProviders: string[];
};

export type MovieCode = {
  code: string;
  name: string;
};

export type MoviePerson = {
  personId: number;
  name: string;
  /** 배우일 때만 있다 */
  character?: string;
};

/** API-030 `ottProviders[]` */
export type MovieOttProvider = {
  ottId: number;
  code: string;
  name: string;
  watchUrl: string | null;
  /** 로그인 시에만 채워진다. 구독 중인 OTT를 우선 정렬한다(FR-16) */
  subscribed: boolean;
};

/** API-030 `myRating` */
export type MyRating = {
  ratingId: number;
  /** 0.5~5.0, 0.5점 단위 */
  score: number;
  comment: string | null;
  visibility: Visibility;
};

/** API-030 `recommendation`. `recommendationItemId`로 진입했을 때만 있다 */
export type MovieRecommendation = {
  recommendationItemId: number;
  type: RecommendationType;
  reason: { summary: string };
};

/** API-030 영화 상세 조회 */
export type MovieDetail = {
  movieId: number;
  title: string;
  synopsis: string;
  releaseDate: string;
  runtimeMinutes: number;
  genres: MovieCode[];
  countries: MovieCode[];
  directors: MoviePerson[];
  cast: MoviePerson[];
  posterUrl: string | null;
  backdropUrl: string | null;
  flavorCode: string;
  averageRating: number;
  ratingCount: number;
  ottProviders: MovieOttProvider[];
  myRating: MyRating | null;
  myWatchStatus: WatchStatus | null;
  recommendation: MovieRecommendation | null;
};

/** API-031 유사 영화 추천 `items[]` */
export type SimilarMovie = {
  movieId: number;
  title: string;
  posterUrl: string | null;
  releaseYear: number;
  averageRating: number;
  /** 0~1 */
  similarity: number;
  reason: { summary: string };
};
