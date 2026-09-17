/*
 * 필름(프레임) 목업.
 *
 * **프레임 목록(API-042)과 프레임 상세(API-043)는 실제 API를 부른다**
 * (S15P21E106-392). 여기 남은 값은 아직 목업인 흐름이 쓴다.
 * - `mockMyRatings`: 7.6 평가 목록(API-039 미구현) · 목업 영화 상세의 내 평가
 * - `mockFrameList`: 7.6 목록의 기준 평가와 7.2-A 공개 프로필 프레임
 *
 * 2.6 · 2.7의 수치는 더 이상 여기서 세지 않는다. 평가 생성(API-036)이 실제 호출이 되면서
 * API-014 · API-041의 실제 수치를 쓴다(업무 -491).
 *
 * 프레임은 평가와 1:1이다(API-042 처리 규칙). 영화 기준값은 mocks/movies.ts에서
 * 가져오고 여기에는 프레임에만 있는 값(별점 · 맛 · 생성 시각)을 둔다.
 *
 * 표시값은 와이어프레임 3.1에 적힌 문자열을 그대로 옮겼다.
 * 3.1 최근 추가된 영화 별점 ★4.5 · ★4.0 · ★5.0
 *
 * `totalCount`는 와이어프레임 3.1의 `필름 100프레임`을 따른다. 목록 항목은 3개만
 * 두고 `hasNext: true`로 남겨 두었다.
 */
import { findMockMovie } from './movies';
import type { MyRating } from '../types/movie';
import type { FrameList, FrameSummary } from '../types/taste';

/**
 * 프레임과 1:1인 내 평가(API-042 처리 규칙). movieId로 찾는다.
 *
 * mocks/movieDetails.ts의 `myRating`도 이 값을 쓴다. 프레임이 있는 영화는 이미 평가한
 * 영화이므로 2.4 영화 상세에도 같은 별점 · 한줄평이 보여야 한다.
 *
 * 402의 한줄평은 와이어프레임 3.1-A 문구 그대로다. 와이어프레임은 같은 문장을 2.4의
 * 다른 사용자 한줄평(`film_daily`)에도 쓴다. 둘 다 와이어프레임 문구라 그대로 두었다.
 */
export const mockMyRatings: Record<number, MyRating> = {
  401: { ratingId: 901, score: 4.5, comment: null, visibility: 'PRIVATE', spoiler: false },
  402: {
    ratingId: 902,
    score: 4.0,
    comment: '마술 트릭 하나하나가 복선이라 두 번 보게 돼요.',
    visibility: 'PUBLIC',
    spoiler: false,
  },
  403: { ratingId: 903, score: 5.0, comment: null, visibility: 'PRIVATE', spoiler: false },
};

function summary(
  frameId: number,
  movieId: number,
  flavorCode: string,
  createdAt: string,
): FrameSummary {
  const movie = findMockMovie(movieId);

  return {
    frameId,
    movie: { movieId: movie.movieId, title: movie.title, posterUrl: movie.posterUrl },
    score: mockMyRatings[movieId].score,
    flavorCode,
    createdAt,
  };
}

/*
 * 시각은 정오 기준으로 두었다. 자정 근처로 두면 보는 사람의 시간대에 따라 화면에 찍히는
 * 날짜가 하루씩 달라져 검증이 흔들린다.
 */
export const mockFrameList: FrameList = {
  totalCount: 100,
  items: [
    summary(501, 401, 'FLAVOR_D', '2026-09-06T12:30:00Z'),
    summary(502, 402, 'FLAVOR_A', '2026-08-24T12:00:00Z'),
    summary(503, 403, 'FLAVOR_C', '2026-08-18T12:00:00Z'),
  ],
  nextCursor: 'mock-cursor-2',
  hasNext: true,
};
