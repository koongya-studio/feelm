/*
 * 감상 기록과 평가 목업. API-033 · API-035 · API-036
 *
 * 이번 단계는 세션 동안 상태를 유지하지 않는다(업무 -262에서 다룬다). 여기서는
 * 화면이 한 번 흐름을 완주할 수 있을 만큼의 값만 만들어 돌려준다.
 *
 * 2.6 · 2.7의 `99프레임`과 `팝콘 버킷 99알 → 100알`은 와이어프레임 수치를 기준으로
 * 잡았다. `이번 주 +3`은 계약에 대응 필드가 없어 목업에만 둔다
 * (types/unspecified.ts의 `weeklyFrameDelta`).
 */
import type { Rating, WatchConfirmResult, WatchRecord } from '../types/watch';
import type { Visibility } from '../types/enums';

/** 평가 전 기준 수치. 와이어프레임 2.6의 `99프레임`과 `99알`에 맞춘다 */
export const mockCountsBeforeRating = {
  frameCount: 99,
  popcornCount: 99,
  /** 계약에 없음 (types/unspecified.ts) */
  weeklyFrameDelta: 3,
};

let nextWatchRecordId = 5001;
let nextRatingId = 91;
let nextFrameId = 77;

export function createMockWatchRecord(movieId: number): WatchRecord {
  const now = new Date();
  const addDays = (days: number) =>
    new Date(now.getTime() + days * 24 * 60 * 60 * 1000).toISOString();

  nextWatchRecordId += 1;

  return {
    watchRecordId: nextWatchRecordId,
    movieId,
    status: 'LINK_CLICKED',
    clickedAt: now.toISOString(),
    // 클릭 2일 후 감상 확인, 7일 후 만료 (API-033 응답 예시)
    confirmDueAt: addDays(2),
    expiresAt: addDays(7),
  };
}

export function createMockWatchConfirm(
  watchRecordId: number,
  watched: boolean,
): WatchConfirmResult {
  return {
    watchRecordId,
    status: watched ? 'WATCHED_UNRATED' : 'EXPIRED',
    watchedAt: watched ? new Date().toISOString() : null,
  };
}

export function createMockRating(
  movieId: number,
  score: number,
  comment: string | null,
  visibility: Visibility,
  flavorCode: string,
): Rating {
  const now = new Date().toISOString();

  nextRatingId += 1;
  nextFrameId += 1;

  return {
    ratingId: nextRatingId,
    movieId,
    score,
    comment,
    visibility,
    frame: { frameId: nextFrameId, createdAt: now },
    // 평가 하나가 프레임 1개와 팝콘 1알을 만든다(FR-07, FR-09)
    popcorn: { flavorCode, isNewFlavor: false, flavorCount: 1 },
    watchStatus: 'RATED',
    createdAt: now,
  };
}
