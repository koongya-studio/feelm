/*
 * 계약에 대응이 없는 화면 요소용 타입.
 *
 * 와이어프레임에는 있으나 API 계약에 데이터 출처가 없는 값이다. 계약이 다르게
 * 말하는 것이 아니라 아무 말도 하지 않는 경우이므로, 팀 결정(2026-09-07)에 따라
 * 와이어프레임대로 구현하고 여기에 모아 표시한다.
 *
 * 계약 타입(types/movie.ts · recommendation.ts · party.ts)에는 이 필드를 넣지
 * 않는다(frontend-structure.md 8.3). CR 결정이 나오면 이 파일만 보고 정리한다.
 *
 * | 필드 | 화면 | 상태 |
 * | --- | --- | --- |
 * | matchRate | 2.1 `취향 일치 92%` · 2.2 `일치 92%` `연결 72%` · 2.3-A `일치 87%` | CR-13 결정 대기 |
 * | runtimeMinutes | 2.1 `케이퍼 · 범죄 · 128분` | API-045 요약에 없음. API-030 상세에는 `runtimeMinutes`가 있음. CR 미등록 |
 * | AvailableNowMovie | 2.1 `내 OTT에서 지금 볼 수 있어요` 4편 | CR-14 결정 대기. 응답 형태 자체가 미정 |
 * | newPopcornCount | 2.1 `멤버 4명 · 새 팝콘 2개` | API-050에 없음. CR 미등록 |
 */
import type { Party } from './party';
import type { RecommendedMovie } from './movie';
import type { DismissResult, RecommendationItem, RecommendationSet } from './recommendation';

/** 추천 요약에 없는 상영 시간 */
export type UnspecifiedMovieFields = {
  runtimeMinutes: number;
};

export type RecommendedMovieView = RecommendedMovie & UnspecifiedMovieFields;

/** 추천 항목 + 일치도 수치 */
export type RecommendationItemView = Omit<RecommendationItem, 'movie'> & {
  movie: RecommendedMovieView;
  /** 일치도 백분율. TASTE는 `일치`, DISCOVERY는 `연결`로 표시한다 */
  matchRate: number;
};

export type RecommendationSetView = Omit<RecommendationSet, 'items'> & {
  items: RecommendationItemView[];
};

export type DismissResultView = Omit<DismissResult, 'replacement'> & {
  replacement: RecommendationItemView | null;
};

/** 2.1 `내 OTT에서 지금 볼 수 있어요` 목록 항목 */
export type AvailableNowMovie = {
  movieId: number;
  title: string;
  posterUrl: string | null;
  /** 이 영화를 볼 수 있는 구독 중 OTT 코드 하나 */
  ottProvider: string;
};

/** 파티 요약 + 새 팝콘 수 */
export type PartyView = Party & {
  newPopcornCount: number;
};
