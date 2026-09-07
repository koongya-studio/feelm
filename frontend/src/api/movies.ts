/*
 * 영화 데이터 접근 함수.
 * 대응 명세는 docs/api/movies-search.md · recommendation.md · social.md이다.
 */
import { findMockMovieDetail } from '../mocks/movieDetails';
import { findMockSimilarMovies } from '../mocks/similarMovies';
import { mockReviews } from '../mocks/reviews';
import { respond, respondError } from './mockTransport';
import type { MovieDetail, SimilarMovie } from '../types/movie';
import type { PublicReview } from '../types/watch';

/**
 * API-030 영화 상세 조회 · GET /api/v1/movies/{movieId}
 *
 * `recommendationItemId`가 있으면 추천 이유를 함께 내려준다. 다른 사용자의 추천 항목이면
 * 계약도 `recommendation`을 생략하고 200을 반환한다.
 */
export async function getMovieDetail(
  movieId: number,
  recommendationItemId?: number,
): Promise<MovieDetail> {
  const detail = findMockMovieDetail(movieId);

  if (!detail) {
    return respondError(404, 'RESOURCE_NOT_FOUND', '영화를 찾을 수 없습니다.');
  }

  if (recommendationItemId === undefined) {
    return respond(detail);
  }

  return respond({
    ...detail,
    recommendation: {
      recommendationItemId,
      type: 'TASTE' as const,
      // 와이어프레임 2.4의 추천 이유 문구
      reason: { summary: '좋아한 케이퍼 무비와 가까워요 · 속도감 있는 전개' },
    },
  });
}

/** API-031 유사 영화 추천 · GET /api/v1/movies/{movieId}/similar */
export async function getSimilarMovies(movieId: number): Promise<SimilarMovie[]> {
  return respond(findMockSimilarMovies(movieId));
}

/** API-032 공개 한줄평 목록 · GET /api/v1/movies/{movieId}/reviews */
export async function getMovieReviews(movieId: number): Promise<PublicReview[]> {
  return respond(mockReviews[movieId] ?? []);
}
