/*
 * 감상 기록과 평가 접근 함수.
 * 대응 명세는 docs/api/watch.md이다.
 *
 * 업무 S15P21E106-477에서 감상 기록 계열(API-033 링크 클릭 · API-034 목록 ·
 * API-035 감상 확인)을 실제 호출로 바꿨다. 평가 계열은 목업으로 남는다.
 *
 * **평가가 목업이라 감상 기록은 `WATCHED_UNRATED`에 머문다.** 목업 평가는 세션 저장소에만
 * 남아 서버 기록을 바꾸지 못하므로, 2.5에서 평가해도 7.7 `안 남긴 영화`에 그대로 남는다.
 * 평가 생성(API-036)은 백엔드에 들어와 있고(업무 -474) 연동하면 서버가 같은 트랜잭션에서
 * 기록을 `RATED`로 옮기므로 이 어긋남은 그 후속 업무에서 사라진다. API-037 수정과
 * API-039 내 평가 목록은 백엔드에도 아직 없다.
 */
import { mockFrameList, mockMyRatings } from '../mocks/frames';
import { findMockMovie } from '../mocks/movies';
import {
  getRatings,
  saveRating,
  saveRatingEdit,
  updateSessionRating,
  withRatingEdit,
} from '../mocks/sessionStore';
import { BASE_WEEKLY_FRAME_DELTA, createMockRating } from '../mocks/watch';
import { getMyFrameCount, getMyPopcornCount } from './taste';
import { request } from './client';
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
  WatchRecordListItem,
} from '../types/watch';

/** API-034 질의. 화면이 쓰는 것만 편다 */
export type WatchRecordQuery = {
  /** 반복 지정된다(`status=LINK_CLICKED&status=RATED`). 중복은 서버가 제거한다 */
  status?: WatchStatus[];
  /** `true`면 확인 시각이 지났고 만료 전인 `LINK_CLICKED`만 온다 */
  confirmDue?: boolean;
  /** 직전 응답의 `nextCursor`를 **같은 조건으로** 되돌려 줄 때만 쓴다 */
  cursor?: string;
  /** 1~50, 기본 20 */
  size?: number;
};

/**
 * 기록 하나를 찾을 때 한 장에 받아 오는 수. 계약의 최대치다(API-034 `size`).
 *
 * 계약에 **단건 조회 API가 없어** 목록에서 찾는다. 한 장을 크게 받아 요청 수를 줄인다.
 */
const LOOKUP_PAGE_SIZE = 50;

/**
 * 찾을 때 훑는 최대 장수. 50 × 4 = 200건까지 본다.
 *
 * `[!]` 그보다 오래된 기록은 찾지 못한다. 목록은 `clickedAt` 내림차순이고 찾는 대상은
 * 방금 누른 링크나 확인을 기다리는 기록이라 앞쪽에 있지만, 감상 기록이 200건을 넘긴
 * 사용자에게는 한계가 된다. 단건 조회 계약이 생기면 이 훑기가 없어진다.
 */
const LOOKUP_PAGE_LIMIT = 4;

/**
 * API-033 OTT 링크 클릭 기록 · POST /api/v1/movies/{movieId}/ott-clicks
 *
 * 링크 클릭은 감상 확정이 아니며 프레임을 만들지 않는다. 재클릭 처리도 서버가 한다 —
 * 활성 `LINK_CLICKED`는 시각과 OTT만 갱신해 `200`, `WATCHED_UNRATED` · `RATED`는 그대로
 * 둔 채 `200`, `EXPIRED`뿐이면 새 기록을 만들어 `201`이다(API-033 처리 규칙).
 * 화면은 상태 코드를 가리지 않고 돌아온 기록만 쓴다.
 *
 * 영화에 그 OTT가 연결되어 있지 않으면 `404 RESOURCE_NOT_FOUND`다.
 */
export async function recordOttClick(movieId: number, ottId: number): Promise<WatchRecord> {
  return request<WatchRecord>(`/movies/${movieId}/ott-clicks`, {
    method: 'POST',
    body: { ottId },
  });
}

/**
 * API-034 감상 기록 목록 · GET /api/v1/watch-records
 *
 * 정렬(`clickedAt` 내림차순 · `null`은 뒤)과 만료 반영은 서버가 한다. 목록을 읽는 것만으로
 * 만료된 `LINK_CLICKED`가 `EXPIRED`로 넘어가므로, 7.7이나 2.4-A를 열면 상태가 최신이다.
 *
 * `cursor`는 **같은 조건으로만** 되돌려 준다. 조건이 섞인 커서는 `400`이다.
 */
export async function getWatchRecords(query: WatchRecordQuery = {}): Promise<WatchRecordList> {
  const { status, confirmDue, cursor, size } = query;

  return request<WatchRecordList>('/watch-records', {
    query: { status, confirmDue, cursor, size },
  });
}

/** 조건에 맞는 첫 기록. 계약에 단건 조회가 없어 목록을 커서로 훑는다 */
async function findRecord(
  query: WatchRecordQuery,
  match: (record: WatchRecordListItem) => boolean,
): Promise<WatchRecordListItem | null> {
  let cursor: string | undefined;

  for (let page = 0; page < LOOKUP_PAGE_LIMIT; page += 1) {
    const list = await getWatchRecords({ ...query, cursor, size: LOOKUP_PAGE_SIZE });
    const found = list.items.find(match);

    if (found) {
      return found;
    }

    if (!list.hasNext || !list.nextCursor) {
      return null;
    }

    cursor = list.nextCursor;
  }

  return null;
}

/**
 * 2.4-A가 URL의 번호로 찾는 기록.
 *
 * **상태로 거르지 않는다.** 이미 답한 기록도 찾아야 화면이 `없는 기록`과 `이미 답한 기록`을
 * 가려 말할 수 있다.
 */
export async function findWatchRecord(watchRecordId: number): Promise<WatchRecordListItem | null> {
  return findRecord({}, (record) => record.watchRecordId === watchRecordId);
}

/**
 * 2.4가 감상 확인으로 보낼 기록. 확인을 기다리는 상태는 `LINK_CLICKED`뿐이다.
 *
 * 목록이 최신순이라 첫 항목이 가장 최근 클릭이다.
 */
export async function findLinkClickedRecord(movieId: number): Promise<WatchRecordListItem | null> {
  return findRecord({ status: ['LINK_CLICKED'] }, (record) => record.movie.movieId === movieId);
}

/**
 * 2.5 감상 정보 줄(`9월 8일 · 티빙에서 시청`)이 읽는 기록.
 *
 * `봤어요`로 감상이 확정된 기록만 쓴다. 평가로 만들어진 기록에는 클릭 이력도 `watchedAt`도
 * 없어(API-034 `ott` · `clickedAt`이 `null`) 줄을 만들 값이 없다.
 */
export async function findWatchedRecord(movieId: number): Promise<WatchRecordListItem | null> {
  return findRecord(
    { status: ['WATCHED_UNRATED', 'RATED'] },
    (record) => record.movie.movieId === movieId && record.watchedAt !== null,
  );
}

/**
 * API-035 감상 확인 응답 · POST /api/v1/watch-records/{watchRecordId}/confirm
 *
 * `watched: true` → `WATCHED_UNRATED`, `false` → `EXPIRED`.
 *
 * 클릭 후 2일이 지나지 않아도 받아들인다. 2일은 확인 질문을 노출하는 기준이지 상태 전이
 * 제한이 아니다(API-035 처리 규칙). 이미 답했거나 만료된 기록은 `409 INVALID_WATCH_STATUS`다.
 */
export async function confirmWatch(
  watchRecordId: number,
  watched: boolean,
): Promise<WatchConfirmResult> {
  return request<WatchConfirmResult>(`/watch-records/${watchRecordId}/confirm`, {
    method: 'POST',
    body: { watched },
  });
}

/**
 * API-036 평가 생성 · POST /api/v1/movies/{movieId}/ratings
 *
 * **목업이다.** 백엔드에는 들어와 있으나(업무 -474) 연동은 이 업무(-477) 범위 밖이라
 * 후속으로 남겼다. 평가 하나가 프레임 1개와 팝콘 1알을 만드는 계산(FR-07, FR-09)도
 * 목업이 한다.
 *
 * `score`는 0.5~5.0의 0.5 배수여야 한다. 계약은 4.49 같은 값을 반올림하지 않고 거절하므로
 * 화면에서 0.5 단위 값만 만들어 보낸다. `comment`가 있으면 `visibility`가 필수다.
 *
 * **감상 기록을 `RATED`로 옮기지 못한다.** 기록은 이제 서버에 있고(API-034) 목업 평가에는
 * 그것을 바꿀 길이 없다. 그래서 평가한 영화가 7.7에 남는다 — 이 파일 머리말의 한계다.
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
 * API-037 평가 수정 · PUT /api/v1/ratings/{ratingId}
 *
 * **목업이다.** 백엔드에 아직 없다(S15P21E106-213).
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
 * **목업이다.** 백엔드에 아직 없다(S15P21E106-214).
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
