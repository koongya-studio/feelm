/*
 * 영화 데이터 접근 함수.
 * 대응 명세는 docs/api/movies-search.md · recommendation.md · social.md이다.
 *
 * 영화 상세(API-030)만 실제 API를 부른다(업무 S15P21E106-475). 같은 파일의 유사 영화와
 * 공개 한줄평은 백엔드에 아직 없어 목업으로 남는다 — 각 함수 주석에 사유를 적었다.
 */
import { findMockSimilarMovies } from '../mocks/similarMovies';
import { mockReviews } from '../mocks/reviews';
import { request } from './client';
import { respond } from './mockTransport';
import type { MovieDetail, SimilarMovie } from '../types/movie';
import type { PublicReview } from '../types/watch';

/**
 * API-030 영화 상세 조회 · GET /api/v1/movies/{movieId}
 *
 * **선택 인증이다.** 토큰이 없어도 `200`이며 그때는 `myRating` · `myWatchStatus` ·
 * `recommendation`이 `null`이고 모든 OTT의 `subscribed`가 `false`다. 토큰이 있으면
 * 본인의 평가 · 감상 상태 · 구독이 함께 온다.
 *
 * `recommendationItemId`가 있으면 추천 이유를 함께 내려준다. 영화가 다르거나 열람
 * 권한이 없으면 계약도 `recommendation: null`과 `200`이므로 화면은 추천 이유만
 * 그리지 않는다.
 *
 * 구독 우선 정렬(FR-16)은 서버가 끝내서 준다. 목업 시절에는 이 함수가 API-026 구독
 * 목록을 따로 불러 `subscribed`를 채우고 정렬했는데, 계약은 정렬까지 마친 목록을
 * 주므로 화면이 받은 순서를 그대로 쓴다.
 *
 * 이번 세션의 평가 · 감상 기록을 얹던 것도 함께 걷어냈다. 평가 API(API-036 · API-037)는
 * 아직 목업이라 2.5에서 남긴 평가가 이 화면에 보이지 않는다. 평가 계열이 연동되면
 * (S15P21E106-160 · -213 · -214) 서버가 같은 값을 준다.
 */
export async function getMovieDetail(
  movieId: number,
  recommendationItemId?: number,
): Promise<MovieDetail> {
  return request<MovieDetail>(`/movies/${movieId}`, {
    auth: 'optional',
    query: { recommendationItemId },
  });
}

/**
 * API-031 유사 영화 추천 · GET /api/v1/movies/{movieId}/similar
 *
 * **목업이다.** 백엔드 구현이 S15P21E106-216으로 진행 중이라 부를 API가 없다.
 * 실제 영화 상세(API-030)로 들어와도 이 목록은 목업 영화라, 항목을 누르면 실제 DB에
 * 없는 영화로 이동해 상세가 `404`가 된다(업무 -475 검증 기록).
 */
export async function getSimilarMovies(movieId: number): Promise<SimilarMovie[]> {
  return respond(findMockSimilarMovies(movieId), 'movies');
}

/**
 * API-032 공개 한줄평 목록 · GET /api/v1/movies/{movieId}/reviews
 *
 * **목업이다.** 평가가 있어야 한줄평이 생기는데 평가 API가 아직 없고, 목록 자체도
 * S15P21E106-161로 예정만 되어 있다. 실제 영화에는 목업 한줄평이 없어 빈 목록이다.
 */
export async function getMovieReviews(movieId: number): Promise<PublicReview[]> {
  return respond(mockReviews[movieId] ?? [], 'movies');
}
