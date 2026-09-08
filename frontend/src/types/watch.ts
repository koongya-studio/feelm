/*
 * 감상 기록과 평가 타입.
 * 대응 명세는 docs/api/watch.md(API-033 · 035 · 036 · 037)와
 * docs/api/social.md(API-032)이다.
 *
 * 점수는 0.5~5.0의 0.5점 단위다. 계약도 같은 규칙이며(API-036 `multipleOf: 0.5`,
 * FR-19, `erd.md`) 2026-09-07 팀 합의가 계약에 반영됐다.
 */
import type { Visibility, WatchStatus } from './enums';

/** API-033 OTT 링크 클릭 기록 */
export type WatchRecord = {
  watchRecordId: number;
  movieId: number;
  status: WatchStatus;
  clickedAt: string;
  /** 감상 확인을 물어보는 시점 */
  confirmDueAt: string;
  expiresAt: string;
};

/**
 * API-034 감상 기록 목록 `items[]`
 *
 * API-033 응답(`WatchRecord`)보다 넓다. 어느 OTT로 이동했는지(`ott`), 언제 봤는지
 * (`watchedAt`), 어떤 평가로 이어졌는지(`ratingId`)가 함께 온다. 2.4-A와 2.5가
 * 이 값들을 쓴다.
 */
export type WatchRecordListItem = {
  watchRecordId: number;
  movie: { movieId: number; title: string; posterUrl: string | null };
  status: WatchStatus;
  ott: { ottId: number; code: string };
  clickedAt: string;
  confirmDueAt: string;
  expiresAt: string;
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
