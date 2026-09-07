/*
 * 감상 기록과 평가 접근 함수.
 * 대응 명세는 docs/api/watch.md이다.
 */
import {
  createMockRating,
  createMockWatchConfirm,
  createMockWatchRecord,
  mockCountsBeforeRating,
} from '../mocks/watch';
import { respond } from './mockTransport';
import type { Rating, RatingInputValue, WatchConfirmResult, WatchRecord } from '../types/watch';

/**
 * API-033 OTT 링크 클릭 기록 · POST /api/v1/movies/{movieId}/ott-clicks
 *
 * 링크 클릭은 감상 확정이 아니며 프레임을 만들지 않는다. 클라이언트는 응답과 무관하게
 * 외부 시청 페이지를 열지만, 목업에는 실제 주소가 없어 이 단계에서는 열지 않는다.
 */
export async function recordOttClick(movieId: number, ottId: number): Promise<WatchRecord> {
  void ottId;

  return respond(createMockWatchRecord(movieId));
}

/**
 * API-035 감상 확인 응답 · POST /api/v1/watch-records/{watchRecordId}/confirm
 * `watched: true` → `WATCHED_UNRATED`, `false` → `EXPIRED`
 */
export async function confirmWatch(
  watchRecordId: number,
  watched: boolean,
): Promise<WatchConfirmResult> {
  return respond(createMockWatchConfirm(watchRecordId, watched));
}

/**
 * API-036 평가 생성 · POST /api/v1/movies/{movieId}/ratings
 *
 * `score`는 0.5~5.0의 0.5 배수여야 한다. 계약은 4.49 같은 값을 반올림하지 않고 거절하므로
 * 화면에서 0.5 단위 값만 만들어 보낸다.
 * `comment`가 있으면 `visibility`가 필수다.
 */
export async function createRating(
  movieId: number,
  input: RatingInputValue,
  flavorCode: string,
): Promise<Rating> {
  return respond(
    createMockRating(
      movieId,
      input.score,
      input.comment ?? null,
      input.visibility ?? 'PRIVATE',
      flavorCode,
    ),
  );
}

/**
 * 2.6 · 2.7이 표시하는 평가 전 기준 수치.
 *
 * 계약에는 이 조합을 한 번에 주는 API가 없다. 프레임 수는 API-014 `stats.frameCount`,
 * 팝콘 알 수는 API-041 `totalCount`에서 오고 `이번 주 +N`은 대응 필드가 없다.
 * 업무 -262에서 목업을 하나로 모을 때 정리한다.
 */
export async function getCountsBeforeRating() {
  return respond(mockCountsBeforeRating);
}
