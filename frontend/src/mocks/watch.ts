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

/**
 * 세션이 시작될 때 이미 있는 감상 기록 — 7.7 평가하지 않은 영화 3편과 8.1 감상 확인 1건.
 *
 * 와이어프레임 7.7의 날짜와 OTT를 옮겼다(`8. 26 · 넷플릭스에서 시청` 등). 7.7은
 * 다섯 줄을 그리지만 영화 목업을 늘리지 않으려고 세 줄만 둔다.
 *
 * **기준 목업이지만 세션 저장소에 넣어 시작한다**(mocks/sessionStore.ts `emptyState`).
 * 7.7 ⇢ 2.5에서 평가하면 이 기록이 `RATED`로 바뀌어야 7.7에서 빠진다. 기준 목업에
 * 따로 두면 그 변화를 기록할 곳이 없어 평가한 뒤에도 목록에 남는다.
 *
 * 저장소가 기록을 고치므로 매번 새 객체를 만든다. 번호는 새로 만드는 기록(5001~)과
 * 겹치지 않게 4001부터다. 시각은 정오 기준이다(mocks/frames.ts와 같은 이유).
 */
export function createSeedWatchRecords(): StoredWatchRecord[] {
  const seed = (watchRecordId: number, movieId: number, ottId: number, watchedDay: string) => ({
    watchRecordId,
    movieId,
    status: 'WATCHED_UNRATED' as const,
    ...mockWatchDeadlines(new Date(`2026-08-${Number(watchedDay) - 1}T12:00:00Z`)),
    ottId,
    watchedAt: `2026-08-${watchedDay}T12:00:00Z`,
    ratingId: null,
  });

  /*
   * 8.1 첫 알림 `영화 잘 보셨나요?`가 가리키는 기록(업무 -308). OTT 링크만 누르고 감상을
   * 확인하지 않은 상태라 `LINK_CLICKED`다. 2.4-A가 이 번호로 찾는다(mocks/notifications.ts).
   * 104는 7.5의 지난 파티 추천이다 — 추천받고 링크를 눌렀다는 흐름이 이어진다.
   */
  const linkClicked: StoredWatchRecord = {
    watchRecordId: 4004,
    movieId: 104,
    status: 'LINK_CLICKED',
    ...mockWatchDeadlines(new Date('2026-09-10T12:00:00Z')),
    ottId: 1,
    watchedAt: null,
    ratingId: null,
  };

  // 목록은 `clickedAt` 순으로 정렬되므로 넣는 순서는 상관없다
  return [
    seed(4001, 411, 1, '26'),
    seed(4002, 412, 2, '20'),
    seed(4003, 413, 3, '14'),
    linkClicked,
  ];
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
