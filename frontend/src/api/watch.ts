/*
 * 감상 기록과 평가 접근 함수.
 * 대응 명세는 docs/api/watch.md이다.
 *
 * 업무 S15P21E106-477에서 감상 기록 계열(API-033 링크 클릭 · API-034 목록 ·
 * API-035 감상 확인)을, 업무 -491에서 평가 생성(API-036)을 실제 호출로 바꿨다.
 *
 * **평가 생성이 실제 호출이 되어 감상 기록이 `RATED`로 옮겨진다.** 서버가 평가 저장 ·
 * 프레임 생성 · 감상 기록 전이를 한 트랜잭션으로 처리하므로(API-036 처리 규칙) 2.5에서
 * 평가한 영화는 7.7 `안 남긴 영화`에서 빠진다.
 *
 * 평가 수정(API-037)은 업무 -597에서, 내 평가 목록(API-039)은 업무 -611에서 실제 호출로
 * 바꿨다. 이 파일에 목업 응답은 없다 — 남은 것은 2.6의 `이번 주 +N` 기준값 하나다
 * (`getRatingCounts`).
 */
import { BASE_WEEKLY_FRAME_DELTA } from '../mocks/watch';
import { getPopcornBucket } from './taste';
import { getMyProfile } from './users';
import { request } from './client';
import type { WatchStatus } from '../types/enums';
import type {
  MyRatingList,
  MyRatingQuery,
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
 * `status`는 MY의 `안 남긴 영화` 탭이 `WATCHED_UNRATED`만 받으려고 쓴다(업무 -306의 7.7을
 * 업무 -495에서 옮겼다).
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
 * 확인 시각(`confirmDueAt`) 전이어도 받아들인다. 그 시각은 확인 질문을 노출하는 기준이지
 * 상태 전이 제한이 아니다(API-035 처리 규칙). 계약은 클릭 즉시라 둘이 같다(업무 -667). 이미 답했거나 만료된 기록은 `409 INVALID_WATCH_STATUS`다.
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
 * 평가 저장 · 프레임 생성 · 감상 기록 `RATED` 전이를 서버가 한 트랜잭션으로 처리한다
 * (API-036 처리 규칙). 감상 기록이 없거나 `LINK_CLICKED`인 영화도 평가할 수 있고, 그때는
 * 서버가 기록을 `RATED`로 만든다(D-6). 화면이 기록 상태를 미리 가릴 필요가 없다.
 *
 * `score`는 0.5~5.0의 0.5 배수여야 한다. 계약은 4.49 같은 값을 반올림하지 않고 거절하므로
 * 화면에서 0.5 단위 값만 만들어 보낸다. `comment`가 있으면 `visibility`가 필수이며
 * 평가 화면은 사용자가 고른 `spoiler`도 함께 보낸다. 값이 어긋나면 `400 VALIDATION_FAILED`다.
 *
 * 사용자 · 영화 조합마다 평가는 하나다. 이미 있으면 `409 RATING_ALREADY_EXISTS`이고 수정은
 * API-037이다. 2.5는 API-030 `myRating`으로 미리 가르지만, 상세를 읽은 뒤 다른 곳에서
 * 평가가 생겼으면 여기서 만난다.
 *
 * 응답의 `popcorn`은 별도 행이 아니라 현재 집계다. 영화에 대표 맛이 없으면 `flavorCode`가
 * `null`이고 팝콘은 어느 맛에도 담기지 않는다(types/watch.ts `Rating`).
 */
export async function createRating(movieId: number, input: RatingInputValue): Promise<Rating> {
  return request<Rating>(`/movies/${movieId}/ratings`, { method: 'POST', body: input });
}

/**
 * API-037 평가 수정 · PUT /api/v1/ratings/{ratingId}
 *
 * 실제 API를 호출한다. 프레임과 팝콘을 새로 만들지 않으며 기존 평가를 전체 교체한다.
 *
 * 한줄평을 지우려면 `comment: null`을 보낸다. 점수 변경에 따른 팝콘 버킷 집계 갱신도
 * 서버가 처리한다.
 */
export async function updateRating(ratingId: number, input: RatingUpdateInput): Promise<Rating> {
  return request<Rating>(`/ratings/${ratingId}`, { method: 'PUT', body: input });
}

/**
 * API-038 평가 삭제 · DELETE /api/v1/ratings/{ratingId}
 *
 * 평가와 **연결된 프레임이 함께 지워지고** 팝콘 버킷 집계에서도 빠진다. 영화 평균 별점과
 * 평가 수도 서버가 같은 트랜잭션에서 다시 계산한다(API-038 처리 규칙).
 *
 * **감상 기록은 지우지 않는다.** `RATED`에서 `WATCHED_UNRATED`로 되돌아가 `rated_at`만
 * 비워지므로, 지운 영화는 7.1의 `안 남긴 영화`로 간다 — 본 것은 맞고 평가만 없는 상태다.
 * 감상까지 무르는 것은 API-035(`confirmWatch(id, false)`)이고 이 함수와는 별개 요청이다.
 *
 * 없는 평가와 남의 평가는 모두 `404 RESOURCE_NOT_FOUND`다. 지운 평가를 다시 지워도 404이므로
 * 부르는 쪽은 404를 "이미 지워졌다"로 읽어도 된다.
 */
export async function deleteRating(ratingId: number): Promise<void> {
  await request<void>(`/ratings/${ratingId}`, { method: 'DELETE' });
}

/*
 * 내 평가 목록(API-039)은 백엔드에 아직 없다(S15P21E106-214 진행 중). 7.1 MY의 `평가한 영화`는
 * 평가와 1:1인 **내 필름(API-042, `taste.getMyFrames`)** 으로 그린다 — 실제 저장 결과가 곧바로
 * 보이고, 고정 목업이 실제 평가를 가리는 일이 없다(업무 -610). API-039가 열리면 그 함수가
 * 여기에 생기고 7.1이 그리로 옮겨 간다.
 */

/**
 * API-039 내 평가 목록 · GET /api/v1/users/me/ratings
 *
 * 7.1 `평가한 영화` 탭이 부른다(업무 -611). `filter`를 생략하면 서버가 평가만(`RATED`)
 * 최신순으로 준다. 다음 장은 직전 응답의 `nextCursor`를 **같은 `filter` · `sort`로** 되돌려
 * 준다 — 조건이 바뀐 커서는 `400 VALIDATION_FAILED`다.
 *
 * 프레임 없는 과거 평가도 온다(`frameId: null`). 그래서 이 목록의 수는 내 필름(API-042)의
 * 프레임 수보다 많을 수 있다.
 */
export async function getMyRatings(query: MyRatingQuery = {}): Promise<MyRatingList> {
  const { filter, sort, cursor, size } = query;

  return request<MyRatingList>('/users/me/ratings', { query: { filter, sort, cursor, size } });
}

/** 2.6 · 2.7이 보여 주는 프레임 · 팝콘 수치 한 벌 */
export type RatingCounts = {
  frameCount: number;
  popcornCount: number;
  weeklyFrameDelta: number;
};

/**
 * 2.6 · 2.7이 표시하는 프레임 · 팝콘 수치.
 *
 * 계약에는 이 조합을 한 번에 주는 API가 없다. 프레임 수는 API-014 `stats.frameCount`,
 * 팝콘 알 수는 API-041 `totalCount`에서 온다. 둘 다 실제 호출이라 3.1 · 7.1이 보여주는
 * 수치와 같은 값에서 시작한다(업무 -491. 그 전에는 목업 기준값 100에서 셌다).
 *
 * `이번 주 +N`은 대응 필드가 없어 고정값이다(types/unspecified.ts의 `weeklyFrameDelta`).
 *
 * **2.5가 저장 전과 저장 후에 한 번씩 부른다**(업무 -599). 저장 전 값은 2.6이 그리고,
 * 저장 후 값은 2.7이 그린다. 예전에는 저장 전 값에 화면이 `+1`을 더해 저장 후를 지어냈는데,
 * 그 전제는 서버 집계가 평가 한 건에 정확히 한 알만 움직일 때만 참이다. 실제로는 대표 맛
 * 배정 같은 다른 변화가 같은 시간에 팝콘 수를 움직일 수 있고, 그때 화면만 거짓말을 하게 된다.
 *
 * 저장 **전** 호출이 실패하면 저장도 하지 않는다 — 2.6 · 2.7이 추가 전 수치 없이는 성립하지
 * 않고, 저장 전에 멈추면 다시 눌러도 `409`가 나지 않는다. 저장 **후** 호출은 이미 저장이
 * 끝난 뒤라 실패해도 되돌릴 것이 없고, 2.7이 계약이 정한 증가량(프레임 1개 · 팝콘 1알)으로
 * 물러서서 그린다.
 */
export async function getRatingCounts(): Promise<RatingCounts> {
  const [profile, bucket] = await Promise.all([getMyProfile(), getPopcornBucket()]);

  return {
    frameCount: profile.stats.frameCount,
    popcornCount: bucket.totalCount,
    weeklyFrameDelta: BASE_WEEKLY_FRAME_DELTA,
  };
}
