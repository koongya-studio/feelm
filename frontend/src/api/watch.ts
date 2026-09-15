/*
 * 감상 기록과 평가 접근 함수.
 * 대응 명세는 docs/api/watch.md이다.
 *
 * 만들어진 감상 기록과 평가는 세션 저장소에 남는다(mocks/sessionStore.ts). 그래서
 * 2.4 → 2.4-A → 2.5 → 2.6 · 2.7 흐름을 걸어도 값이 이어지고, 평가한 영화는 3.1 필름과
 * 3.2 팝콘에도 반영된다.
 */
import { mockFrameList, mockMyRatings } from '../mocks/frames';
import { findMockMovie } from '../mocks/movies';
import {
  addWatchRecord,
  findLatestWatchRecordByMovie,
  getRatings,
  getWatchRecords as getStoredWatchRecords,
  saveRating,
  saveRatingEdit,
  updateSessionRating,
  updateWatchRecord,
  withRatingEdit,
} from '../mocks/sessionStore';
import {
  BASE_WEEKLY_FRAME_DELTA,
  createMockRating,
  createMockWatchRecord,
  mockWatchDeadlines,
  toMockWatchRecordListItem,
} from '../mocks/watch';
import { getMyFrameCount, getMyPopcornCount } from './taste';
import { respond } from './mockTransport';
import type { WatchStatus } from '../types/enums';
import type {
  MyRatingList,
  Rating,
  RatingInputValue,
  RatingUpdateInput,
  WatchConfirmResult,
  WatchRecord,
  WatchRecordList,
} from '../types/watch';

/**
 * API-033 OTT 링크 클릭 기록 · POST /api/v1/movies/{movieId}/ott-clicks
 *
 * 링크 클릭은 감상 확정이 아니며 프레임을 만들지 않는다. 클라이언트는 응답과 무관하게
 * 외부 시청 페이지를 열지만, 목업에는 실제 주소가 없어 이 단계에서는 열지 않는다.
 *
 * 같은 영화를 다시 누를 때의 처리 규칙도 계약을 따른다(API-033).
 * `LINK_CLICKED` 기록이 있으면 시각만 갱신하고, 이미 `WATCHED_UNRATED` · `RATED`이면
 * 상태를 바꾸지 않고 기존 기록을 돌려준다. `EXPIRED`면 새 기록을 만든다.
 */
export async function recordOttClick(movieId: number, ottId: number): Promise<WatchRecord> {
  const existing = findLatestWatchRecordByMovie(movieId);

  if (existing && existing.status !== 'EXPIRED') {
    const patch =
      existing.status === 'LINK_CLICKED'
        ? { ...mockWatchDeadlines(new Date()), ottId }
        : // 감상이 확정된 기록은 상태도 시각도 건드리지 않는다
          {};

    updateWatchRecord(existing.watchRecordId, patch);

    return respond({ ...existing, ...patch });
  }

  const record = createMockWatchRecord(movieId, ottId);

  addWatchRecord(record);

  return respond(record);
}

/**
 * API-034 감상 기록 목록 · GET /api/v1/watch-records
 *
 * 정렬은 계약의 처리 규칙대로 `clickedAt` 내림차순, 같으면 `watchRecordId` 내림차순이다.
 * 2.4-A가 URL의 기록을 찾고, 2.5가 감상 정보 줄(`9월 8일 · 티빙에서 시청`)을 만들 때 쓴다.
 *
 * `status`는 7.7이 `WATCHED_UNRATED`만 받으려고 쓴다(업무 -306). `confirmDue` · `cursor` ·
 * `size`는 구현하지 않았다. `confirmDue`가 필요한 8.1 알림은 범위 밖이다.
 */
export async function getWatchRecords(
  query: { status?: WatchStatus[] } = {},
): Promise<WatchRecordList> {
  const items = getStoredWatchRecords()
    .filter((record) => !query.status || query.status.includes(record.status))
    .map(toMockWatchRecordListItem)
    .sort((a, b) => b.clickedAt.localeCompare(a.clickedAt) || b.watchRecordId - a.watchRecordId);

  return respond({ items, nextCursor: null, hasNext: false }, 'watch');
}

/**
 * API-035 감상 확인 응답 · POST /api/v1/watch-records/{watchRecordId}/confirm
 * `watched: true` → `WATCHED_UNRATED`, `false` → `EXPIRED`
 */
export async function confirmWatch(
  watchRecordId: number,
  watched: boolean,
): Promise<WatchConfirmResult> {
  const status = watched ? 'WATCHED_UNRATED' : 'EXPIRED';
  const watchedAt = watched ? new Date().toISOString() : null;

  updateWatchRecord(watchRecordId, { status, watchedAt });

  return respond({ watchRecordId, status, watchedAt });
}

/**
 * API-036 평가 생성 · POST /api/v1/movies/{movieId}/ratings
 *
 * `score`는 0.5~5.0의 0.5 배수여야 한다. 계약은 4.49 같은 값을 반올림하지 않고 거절하므로
 * 화면에서 0.5 단위 값만 만들어 보낸다.
 * `comment`가 있으면 `visibility`가 필수다.
 *
 * 평가 하나가 프레임 1개와 팝콘 1알을 만든다(FR-07, FR-09). 저장소에 남기면 3.1 · 3.2와
 * 2.1의 수치가 함께 올라간다.
 */
export async function createRating(
  movieId: number,
  input: RatingInputValue,
  flavorCode: string,
): Promise<Rating> {
  const rating = createMockRating(
    movieId,
    input.score,
    input.comment ?? null,
    input.visibility ?? 'PRIVATE',
    flavorCode,
  );

  saveRating(rating);

  /*
   * 감상 기록과 평가는 1:1로 이어진다(erd.md). OTT 링크를 눌러 들어온 흐름이면 그
   * 기록을 `RATED`로 옮기고 평가 번호를 붙여, API-034 목록에서 평가 완료로 보이게 한다.
   * 2.4에서 바로 평가한 경우에는 기록이 없어 아무 것도 하지 않는다.
   */
  const record = findLatestWatchRecordByMovie(movieId);

  if (record && record.status !== 'EXPIRED') {
    updateWatchRecord(record.watchRecordId, { status: 'RATED', ratingId: rating.ratingId });
  }

  return respond(rating);
}

/**
 * API-037 평가 수정 · PUT /api/v1/ratings/{ratingId}
 *
 * **프레임과 팝콘을 새로 만들지 않는다**(API-037 처리 규칙). 그래서 `createRating`과
 * 저장 위치가 다르다 — 이번 세션에 만든 평가는 그 항목을 고치고, 기준 목업 평가는
 * 고친 값만 따로 남긴다(mocks/sessionStore.ts `ratingEdits`).
 *
 * 한줄평을 지우려면 `comment: null`을 보낸다. 점수를 바꾸면 팝콘 버킷의 맛별 평균도
 * 바뀌어야 하지만(API-037) 목업은 반영하지 않는다. 평균을 보여주는 화면(3.2)이 수정
 * 경로에 없다.
 */
export async function updateRating(ratingId: number, input: RatingUpdateInput): Promise<void> {
  if (!updateSessionRating(ratingId, input)) {
    saveRatingEdit(ratingId, input);
  }

  return respond(undefined, 'watch');
}

/**
 * API-039 내 평가 목록 · GET /api/v1/users/me/ratings
 *
 * 기준 평가(3.1 프레임과 1:1인 401~403)에 이번 세션의 평가를 더해 최신순으로 준다.
 * 기준 영화를 이번 세션에 다시 평가한 경우는 없다 — 2.5가 이미 평가한 영화는 수정으로
 * 보내기 때문이다(업무 -306).
 *
 * `cursor` · `size`는 구현하지 않았다. 기준 목업은 3편만 두고 `hasNext: true`로 남긴다.
 * 필름(API-042)과 같은 방식이다 — `평가 N`의 N은 필름 프레임 수와 같다(API-039 처리 규칙).
 */
export async function getMyRatings(): Promise<MyRatingList> {
  const session = getRatings().map((rating) => ({
    ratingId: rating.ratingId,
    movie: movieSummary(rating.movieId),
    score: rating.score,
    comment: rating.comment,
    visibility: rating.visibility,
    frameId: rating.frame.frameId,
    createdAt: rating.createdAt,
  }));

  const base = mockFrameList.items.map((frame) => {
    const rating = withRatingEdit(mockMyRatings[frame.movie.movieId]);

    return {
      ratingId: rating.ratingId,
      movie: movieSummary(frame.movie.movieId),
      score: rating.score,
      comment: rating.comment,
      visibility: rating.visibility,
      frameId: frame.frameId,
      createdAt: frame.createdAt,
    };
  });

  const items = [...session, ...base].sort(
    (a, b) => b.createdAt.localeCompare(a.createdAt) || b.ratingId - a.ratingId,
  );

  return respond({ items, nextCursor: 'mock-cursor-2', hasNext: true }, 'watch');
}

function movieSummary(movieId: number) {
  const { title, posterUrl } = findMockMovie(movieId);

  return { movieId, title, posterUrl };
}

/**
 * 2.6 · 2.7이 표시하는 평가 전 기준 수치.
 *
 * 계약에는 이 조합을 한 번에 주는 API가 없다. 프레임 수는 API-014 `stats.frameCount`,
 * 팝콘 알 수는 API-041 `totalCount`에서 오고 `이번 주 +N`은 대응 필드가 없다
 * (types/unspecified.ts의 `weeklyFrameDelta`).
 *
 * 세션 저장소가 생기기 전에는 와이어프레임의 `99프레임 · 99알`을 고정값으로 돌려줬다.
 * 이제는 현재 수치를 계산해 돌려주므로 두 번째 평가부터 값이 이어진다.
 */
export async function getCountsBeforeRating() {
  const [frameCount, popcornCount] = await Promise.all([getMyFrameCount(), getMyPopcornCount()]);

  return respond({
    frameCount,
    popcornCount,
    // 이번 세션에 평가한 만큼 주간 증가량도 함께 올라간다
    weeklyFrameDelta: BASE_WEEKLY_FRAME_DELTA + getRatings().length,
  });
}
