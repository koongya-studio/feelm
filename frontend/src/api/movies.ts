/*
 * 영화 데이터 접근 함수.
 * 대응 명세는 docs/api/movies-search.md · recommendation.md · social.md이다.
 *
 * 영화 상세(API-030), 유사 영화(API-031), 공개 한줄평(API-032)은 실제 API를 부른다.
 */
import { request } from './client';
import type { MovieDetail, SimilarMovie } from '../types/movie';
import type { PublicReviewList } from '../types/watch';

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
 * 본인 평가는 상세 응답의 `myRating`을 그대로 사용한다. 화면은 별도 세션 데이터를
 * 합치지 않으므로 로컬과 배포 모두 현재 인증 사용자의 DB 평가만 보인다.
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
 * 선택 인증 API다. 현재 계약은 로그인 여부와 관계없이 같은 콘텐츠 임베딩 결과를
 * 반환하며, 응답 객체의 `items`만 화면에 넘긴다.
 */
export async function getSimilarMovies(movieId: number): Promise<SimilarMovie[]> {
  const response = await request<{ baseMovieId: number; items: SimilarMovie[] }>(
    `/movies/${movieId}/similar`,
    { auth: 'optional', query: { size: 10 } },
  );
  return response.items;
}

/**
 * API-032 공개 한줄평 목록 · GET /api/v1/movies/{movieId}/reviews
 *
 * 회원 API다. 본인의 비공개 한줄평과 타인의 공개 한줄평을 최신순으로 받으며, 차단 관계와
 * 작성자 상태는 서버가 거른다. `cursor`는 직전 응답 값을 그대로 보내고 첫 페이지에서는
 * 생략한다.
 */
export async function getMovieReviews(
  movieId: number,
  cursor?: string,
  size?: number,
): Promise<PublicReviewList> {
  return request<PublicReviewList>(`/movies/${movieId}/reviews`, {
    query: { cursor, size },
  });
}
