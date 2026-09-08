/*
 * 감상 기록과 평가 접근 함수.
 * 대응 명세는 docs/api/watch.md이다.
 *
 * 만들어진 감상 기록과 평가는 세션 저장소에 남는다(mocks/sessionStore.ts). 그래서
 * 2.4 → 2.4-A → 2.5 → 2.6 · 2.7 흐름을 걸어도 값이 이어지고, 평가한 영화는 3.1 필름과
 * 3.2 팝콘에도 반영된다.
 */
import {
  addWatchRecord,
  findWatchRecord,
  getRatings,
  saveRating,
  updateWatchRecord,
} from '../mocks/sessionStore';
import { BASE_WEEKLY_FRAME_DELTA, createMockRating, createMockWatchRecord } from '../mocks/watch';
import { getMyFrameCount, getMyPopcornCount } from './taste';
import { respond, respondError } from './mockTransport';
import type { Rating, RatingInputValue, WatchConfirmResult, WatchRecord } from '../types/watch';

/**
 * API-033 OTT 링크 클릭 기록 · POST /api/v1/movies/{movieId}/ott-clicks
 *
 * 링크 클릭은 감상 확정이 아니며 프레임을 만들지 않는다. 클라이언트는 응답과 무관하게
 * 외부 시청 페이지를 열지만, 목업에는 실제 주소가 없어 이 단계에서는 열지 않는다.
 */
export async function recordOttClick(movieId: number, ottId: number): Promise<WatchRecord> {
  void ottId;

  const record = createMockWatchRecord(movieId);

  addWatchRecord(record);

  return respond(record);
}

/**
 * 2.4-A가 표시할 감상 기록 조회.
 *
 * 계약에 단건 조회 API가 없다. 2.4-A는 지금까지 목업 값을 새로 만들어 썼는데, 세션
 * 저장소가 생기면서 실제로 만들어진 기록을 읽을 수 있게 됐다. 대응 API가 정해지면
 * 이 함수만 교체한다.
 */
export async function getWatchRecord(watchRecordId: number): Promise<WatchRecord> {
  const record = findWatchRecord(watchRecordId);

  if (!record) {
    return respondError(404, 'RESOURCE_NOT_FOUND', '감상 기록을 찾을 수 없습니다.');
  }

  return respond(record);
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

  // API-033 응답에는 `watchedAt`이 없다. 기록에는 상태만 반영한다
  updateWatchRecord(watchRecordId, { status });

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

  return respond(rating);
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
