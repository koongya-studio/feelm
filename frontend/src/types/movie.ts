/*
 * 영화 타입.
 * 계약의 사본이며 계약에 없는 필드를 넣지 않는다(frontend-structure.md 8.3).
 */

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
