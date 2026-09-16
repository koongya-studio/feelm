/*
 * 영화 검색 타입.
 * 계약의 사본이며 계약에 없는 필드를 넣지 않는다(frontend-structure.md 8.3).
 */

/**
 * API-028 `items[]`
 *
 * **원천 값이 없는 필드는 `null`이다.** 수집 데이터에 원제나 개봉일이 없는 영화가 있어
 * `originalTitle` · `releaseYear`가 비어 온다(업무 -476). `averageRating`은 평가가
 * 없어도 `0`이 들어오는 NOT NULL 집계 값이라 `null`이 되지 않는다.
 */
export type MovieSearchItem = {
  movieId: number;
  title: string;
  originalTitle: string | null;
  posterUrl: string | null;
  releaseYear: number | null;
  genres: string[];
  averageRating: number;
  /** 제공 OTT 코드 목록. 표시 이름은 API-025 기준정보에서 찾는다 */
  ottProviders: string[];
  /** 로그인했을 때만 채워진다 */
  watched: boolean;
};

/** API-028 영화 검색 응답 */
export type MovieSearchResult = {
  /**
   * cursor · size를 적용하기 **전** 조건에 맞는 전체 영화 수(API-028 처리 규칙).
   * 4.2의 `검색 결과 N건`과 4.3의 `결과 보기 · N편`이 쓴다. 받아 온 `items`의 길이가
   * 아니라 이 값을 써야 다음 장이 남아 있어도 숫자가 맞는다.
   */
  totalCount: number;
  items: MovieSearchItem[];
  /** 다음 장을 부를 불투명 커서. `hasNext`가 `false`면 `null`이다 */
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
 *
 * `cursor` · `size`는 여기 없다. 검색 **조건**만 모아 두어야 커서가 어떤 조건에 묶인
 * 것인지 헷갈리지 않는다 — 계약도 조건이 바뀐 커서를 400으로 거절한다(API-028 처리
 * 규칙). 다음 장은 `searchMovies(query, cursor)`로 부른다.
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
  /** 개봉일이 있는 영화가 하나도 없으면 두 값 모두 `null`이다(API-029 처리 규칙) */
  yearRange: { min: number | null; max: number | null };
  otts: { ottId: number; code: string; name: string }[];
};
