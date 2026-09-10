/*
 * 영화 검색 타입.
 * 계약의 사본이며 계약에 없는 필드를 넣지 않는다(frontend-structure.md 8.3).
 */

/** API-028 `items[]` */
export type MovieSearchItem = {
  movieId: number;
  title: string;
  originalTitle: string;
  posterUrl: string | null;
  releaseYear: number;
  genres: string[];
  averageRating: number;
  /** 제공 OTT 코드 목록. 표시 이름은 API-025 기준정보에서 찾는다 */
  ottProviders: string[];
  /** 로그인했을 때만 채워진다 */
  watched: boolean;
};

/** API-028 영화 검색 응답 */
export type MovieSearchResult = {
  items: MovieSearchItem[];
  nextCursor: string | null;
  hasNext: boolean;
};

/** API-028 `sort` */
export type MovieSearchSort = 'RELEVANCE' | 'RATING' | 'RELEASE_DATE';

/**
 * API-028 질의.
 *
 * 계약은 이것들을 Query Parameter로 받는다. 화면이 필터 상태를 한 덩어리로 들고
 * 다니므로 객체로 모아 둔다. 값이 없는 필드는 보내지 않는 것과 같다.
 */
export type MovieSearchQuery = {
  /** 영화명 · 배우 · 감독 */
  q?: string;
  /** 장르 코드. 복수는 OR */
  genre?: string[];
  /** ISO 3166-1 alpha-2. 복수는 OR */
  country?: string[];
  yearFrom?: number;
  yearTo?: number;
  /** ottId. 복수는 OR */
  ott?: number[];
  sort?: MovieSearchSort;
};

/** API-029 `genres[]` · `countries[]` */
export type FilterOption = {
  code: string;
  name: string;
};

/** API-029 검색 필터 옵션 조회 응답 */
export type FilterOptions = {
  genres: FilterOption[];
  countries: FilterOption[];
  yearRange: { min: number; max: number };
  otts: { ottId: number; code: string; name: string }[];
};
