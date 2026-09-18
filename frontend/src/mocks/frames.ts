/*
 * 필름(프레임) 목업.
 *
 * **프레임 목록(API-042) · 프레임 상세(API-043) · 내 평가 목록(API-039)은 실제 API를
 * 부른다**(S15P21E106-392 · -611). 여기 남은 `mockFrameList`는 아직 목업인 7.2-A · 7.2-B
 * 공개 프로필(API-062 미구현)의 필름 칸이다.
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
import type { FrameList, FrameSummary } from '../types/taste';

function summary(
  frameId: number,
  movieId: number,
  score: number,
  flavorCode: string,
  createdAt: string,
): FrameSummary {
  const movie = findMockMovie(movieId);

  return {
    frameId,
    movie: { movieId: movie.movieId, title: movie.title, posterUrl: movie.posterUrl },
    score,
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
    summary(501, 401, 4.5, 'FLAVOR_D', '2026-09-06T12:30:00Z'),
    summary(502, 402, 4.0, 'FLAVOR_A', '2026-08-24T12:00:00Z'),
    summary(503, 403, 5.0, 'FLAVOR_C', '2026-08-18T12:00:00Z'),
  ],
  nextCursor: 'mock-cursor-2',
  hasNext: true,
};
