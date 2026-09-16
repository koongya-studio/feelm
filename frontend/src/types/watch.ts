/*
 * 감상 기록과 평가 타입.
 * 대응 명세는 docs/api/watch.md(API-033 ~ 039)와
 * docs/api/social.md(API-032)이다.
 *
 * 점수는 0.5~5.0의 0.5점 단위다. 계약도 같은 규칙이며(API-036 `multipleOf: 0.5`,
 * FR-19, `erd.md`) 2026-09-07 팀 합의가 계약에 반영됐다.
 */
import type { Visibility, WatchStatus } from './enums';

/**
 * API-033 OTT 링크 클릭 기록
 *
 * 시각 셋은 **`null`일 수 있다.** 클릭 없이 평가로 만들어진 기록(`WATCHED_UNRATED` ·
 * `RATED`)은 링크를 눌러도 상태와 시각을 바꾸지 않고 그대로 돌아오기 때문이다
 * (API-033 처리 규칙).
 */
export type WatchRecord = {
  watchRecordId: number;
  movieId: number;
  status: WatchStatus;
  clickedAt: string | null;
  /** 감상 확인을 물어보는 시점 */
  confirmDueAt: string | null;
  expiresAt: string | null;
};

/**
 * API-034 감상 기록 목록 `items[]`
 *
 * API-033 응답(`WatchRecord`)보다 넓다. 어느 OTT로 이동했는지(`ott`), 언제 봤는지
 * (`watchedAt`), 어떤 평가로 이어졌는지(`ratingId`)가 함께 온다. 2.4-A와 2.5가
 * 이 값들을 쓴다.
 *
 * **클릭 이력이 없으면 `ott`와 시각 셋이 `null`이다.** 평가가 먼저 만들어 낸 기록이
 * 그렇다(API-036). 화면은 OTT 이름과 날짜를 그 경우에도 그릴 수 있어야 한다.
 */
export type WatchRecordListItem = {
  watchRecordId: number;
  movie: { movieId: number; title: string; posterUrl: string | null };
  status: WatchStatus;
  ott: { ottId: number; code: string } | null;
  clickedAt: string | null;
  confirmDueAt: string | null;
  expiresAt: string | null;
  watchedAt: string | null;
  ratingId: number | null;
};

/** API-034 응답 */
export type WatchRecordList = {
  items: WatchRecordListItem[];
  nextCursor: string | null;
  hasNext: boolean;
};

/** API-035 감상 확인 응답 */
export type WatchConfirmResult = {
  watchRecordId: number;
  status: WatchStatus;
  watchedAt: string | null;
};

/** API-036 요청 본문 */
export type RatingInputValue = {
  /** 0.5~5.0, 0.5점 단위 */
  score: number;
  comment?: string;
  /** `comment`가 있으면 필수 */
  visibility?: Visibility;
};

/** API-036 · API-037 응답 */
export type Rating = {
  ratingId: number;
  movieId: number;
  score: number;
  comment: string | null;
  visibility: Visibility;
  /** 최초 저장 시 생성된 프레임. 수정 시에는 기존 값 */
  frame: { frameId: number; createdAt: string };
  popcorn: { flavorCode: string; isNewFlavor: boolean; flavorCount: number };
  watchStatus: WatchStatus;
  createdAt: string;
};

/**
 * API-037 요청 본문. 세 필드를 모두 보낸다.
 * 한줄평을 지우려면 `comment: null`이다(API-037 설명).
 */
export type RatingUpdateInput = {
  score: number;
  comment: string | null;
  visibility: Visibility;
};

/** API-039 내 평가 목록 `items[]` */
export type MyRatingListItem = {
  ratingId: number;
  movie: { movieId: number; title: string; posterUrl: string | null };
  score: number;
  comment: string | null;
  visibility: Visibility;
  frameId: number;
  createdAt: string;
};

/** API-039 응답 */
export type MyRatingList = {
  items: MyRatingListItem[];
  nextCursor: string | null;
  hasNext: boolean;
};

/** API-032 공개 한줄평 목록 `items[]` */
export type PublicReview = {
  ratingId: number;
  user: { userId: number; nickname: string };
  score: number;
  comment: string;
  /** 본인 한줄평이면 true. `PRIVATE`이어도 포함된다 */
  mine: boolean;
  createdAt: string;
};
