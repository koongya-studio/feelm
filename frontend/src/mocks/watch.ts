/*
 * 감상 기록과 평가 목업. API-033 · API-035 · API-036
 *
 * 여기에는 값을 만드는 함수만 둔다. 만들어진 감상 기록과 평가는 mocks/sessionStore.ts에
 * 쌓이고, `src/api/`가 기준 목업과 합쳐 응답을 만든다(업무 -262).
 *
 * 프레임 수와 팝콘 알 수는 더 이상 여기 고정값으로 두지 않는다. 기준값은
 * mocks/user.ts · mocks/frames.ts · mocks/popcornBucket.ts의 100이고, 현재 수치는
 * 거기에 이번 세션의 평가를 더해 계산한다.
 */
import { findMockMovie } from './movies';
import { mockOtts } from './otts';
import type { StoredWatchRecord } from './sessionStore';
import type { Rating, WatchConfirmResult, WatchRecordListItem } from '../types/watch';
import type { Visibility } from '../types/enums';

/**
 * 이번 주에 추가된 프레임 수의 기준값. **계약에 대응 필드가 없다**
 * (types/unspecified.ts의 `weeklyFrameDelta`). 와이어프레임 2.6의 `이번 주 +3`이다.
 */
export const BASE_WEEKLY_FRAME_DELTA = 3;

let nextWatchRecordId = 5001;
let nextRatingId = 91;
let nextFrameId = 77;

/** 클릭 시각 기준의 감상 확인 시점 · 만료 시점 (API-033 응답 예시: 2일 · 7일) */
export function mockWatchDeadlines(clickedAt: Date) {
  const addDays = (days: number) =>
    new Date(clickedAt.getTime() + days * 24 * 60 * 60 * 1000).toISOString();

  return { clickedAt: clickedAt.toISOString(), confirmDueAt: addDays(2), expiresAt: addDays(7) };
}

export function createMockWatchRecord(movieId: number, ottId: number): StoredWatchRecord {
  nextWatchRecordId += 1;

  return {
    watchRecordId: nextWatchRecordId,
    movieId,
    status: 'LINK_CLICKED',
    ...mockWatchDeadlines(new Date()),
    ottId,
    watchedAt: null,
    ratingId: null,
  };
}

/**
 * 저장소의 기록을 API-034 목록 항목 모양으로 만든다.
 *
 * 영화 표시값은 mocks/movies.ts 한 곳에서만 오고(frontend-structure.md 7.3),
 * OTT는 기준정보(mocks/otts.ts)에서 코드를 찾는다. 계약의 `ott`에는 `ottId`와 `code`만
 * 있어 표시명은 화면이 API-025로 따로 조회한다.
 */
export function toMockWatchRecordListItem(record: StoredWatchRecord): WatchRecordListItem {
  const movie = findMockMovie(record.movieId);
  const ott = mockOtts.find((candidate) => candidate.ottId === record.ottId);

  return {
    watchRecordId: record.watchRecordId,
    movie: { movieId: movie.movieId, title: movie.title, posterUrl: movie.posterUrl },
    status: record.status,
    ott: { ottId: record.ottId, code: ott?.code ?? '' },
    clickedAt: record.clickedAt,
    confirmDueAt: record.confirmDueAt,
    expiresAt: record.expiresAt,
    watchedAt: record.watchedAt,
    ratingId: record.ratingId,
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
