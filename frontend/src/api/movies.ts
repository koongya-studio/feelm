/*
 * 영화 데이터 접근 함수.
 * 대응 명세는 docs/api/movies-search.md · recommendation.md · social.md이다.
 */
import { findMockMovieDetail } from '../mocks/movieDetails';
import { findMockSimilarMovies } from '../mocks/similarMovies';
import { mockReviews } from '../mocks/reviews';
import { findLatestWatchRecordByMovie, findRatingByMovie } from '../mocks/sessionStore';
import { getMyOtts } from './otts';
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

  /*
   * 이번 세션에 평가했거나 OTT 링크를 눌렀으면 그 상태를 얹는다. 2.4의 `내 별점 남기기`와
   * `감상평`이 방금 남긴 값을 보여주게 된다.
   */
  /*
   * OTT 목록에는 구독 여부를 채우고 구독 중인 것을 앞에 둔다(FR-16). 구독 목록은
   * 사용자가 1.2에서 고른 값이므로(API-026) 목업이 아니라 여기서 정한다.
   * `sort`는 안정 정렬이라 같은 묶음 안의 순서는 계약이 준 그대로다.
   */
  const subscribedOttIds = (await getMyOtts()).map((ott) => ott.ottId);
  const ottProviders = detail.ottProviders
    .map((ott) => ({ ...ott, subscribed: subscribedOttIds.includes(ott.ottId) }))
    .sort((a, b) => Number(b.subscribed) - Number(a.subscribed));

  const rating = findRatingByMovie(movieId);
  const watchRecord = findLatestWatchRecordByMovie(movieId);
  const withSession = {
    ...detail,
    ottProviders,
    myRating: rating
      ? {
          ratingId: rating.ratingId,
          score: rating.score,
          comment: rating.comment,
          visibility: rating.visibility,
        }
      : detail.myRating,
    myWatchStatus: rating ? ('RATED' as const) : (watchRecord?.status ?? detail.myWatchStatus),
  };

  if (recommendationItemId === undefined) {
    return respond(withSession);
  }

  return respond({
    ...withSession,
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
