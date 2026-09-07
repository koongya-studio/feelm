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
 * | weeklyFrameDelta | 2.6 `99프레임 · 이번 주 +3` · 2.7 `100프레임 · 이번 주 +4` | 주간 증가 수를 내려주는 필드가 없음. API-014 `stats.frameCount`와 API-042 `totalCount`는 누적 수만 준다. CR 미등록 |
 * | TasteFlowView.flowSentence | 3.1 `스릴러에서 느와르로 넓어지고 있어요` | CR-16 결정 대기. 대응 필드가 없음 |
 * | TasteKeywordView.rationale | 3.1 `계획형 캐릭터 영화 12편에 ★4 이상` 등 4개 | CR-16 결정 대기. API-040 `keywords`는 `code`·`label`만 있음 |
 * | FrameDetailView.movie.runtimeMinutes | 3.1-A `2013 · 케이퍼 · 115분` | API-043 `movie`에 없음. runtimeMinutes와 같은 항목이므로 CR 미등록 |
 */
import type { FrameDetail } from './taste';
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

/** 2.6 · 2.7의 `이번 주 +N` */
export type WeeklyFrameDelta = {
  /** 이번 주에 추가된 프레임 수 */
  weeklyFrameDelta: number;
};

/**
 * 3.1 취향 키워드 한 개.
 *
 * `label`은 API-040 `keywords[].label`에 대응하지만, 2.1은 같은 키워드를
 * `계획형 안티히어로를 좋아해요`라는 문장으로 보여주고 3.1은 `계획형 안티히어로`라는
 * 명사형 칩으로 보여준다. 계약에는 `label` 하나뿐이라 한 배열로 두 표기를 담을 수
 * 없으므로, 근거 문장(계약에 없음)과 함께 3.1 전용 값으로 분리했다(팀 결정 2026-09-07).
 * API-040의 키워드 사전이 확정되면 두 화면이 `keywords`를 함께 읽도록 정리한다.
 */
export type TasteKeywordView = {
  code: string;
  /** 칩에 표시하는 명사형 라벨 */
  label: string;
  /** 라벨 옆 근거 문장 */
  rationale: string;
};

/** 3.1 `요즘 취향 흐름` 카드 */
export type TasteFlowView = {
  /** 흐름 한 문장 */
  flowSentence: string;
  /** 와이어프레임은 4개다. API-040 `keywords`는 3~5개 */
  keywords: TasteKeywordView[];
};

/** 프레임 상세 + 상영 시간 */
export type FrameDetailView = Omit<FrameDetail, 'movie'> & {
  movie: FrameDetail['movie'] & UnspecifiedMovieFields;
};
