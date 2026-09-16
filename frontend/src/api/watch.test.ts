/*
 * 감상 기록 (업무 S15P21E106-477).
 *
 * 화면에서는 드러나지 않는 것을 고정한다.
 * - **상태 질의를 같은 이름의 반복으로 싣는 것.** 계약이 그렇게 정했다(API-034).
 *   인덱스 표기(`status[0]=RATED`)는 서버가 **거절하지 않고 무시해** 거른 적 없는 전체
 *   목록이 온다. 한 값만 실려도 목록은 그럴듯해 보이므로 화면으로는 알 수 없다
 * - **`confirmDue`는 정확한 소문자 `true` · `false`여야 한다.** 다른 표기는 400이다
 * - **기록 하나를 찾을 때 커서를 같은 조건으로 되돌려 준다.** 조건이 섞인 커서는 400이고,
 *   조건을 빼먹으면 남의 필터에서 찾게 된다
 * - **훑기는 끝이 있다.** `hasNext`가 거짓이면 멈추고, 그래도 못 찾으면 장수 상한에서
 *   멈춘다. 상한이 없으면 기록이 많은 계정에서 목록을 끝까지 당긴다
 * - **감상 정보 줄은 `watchedAt`이 있는 기록만 쓴다.** 평가가 먼저 만든 기록에는 클릭
 *   이력도 감상 시각도 없다(API-034 `ott` · `clickedAt`이 `null`)
 *
 * 재클릭 규칙(`200` · `201`)과 상태 전이는 이제 서버가 판정한다. 목업 시절 이 계층이
 * 들고 있던 규칙이라 backend 쪽 시험으로 옮겨 갔다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  confirmWatch,
  findLinkClickedRecord,
  findWatchRecord,
  findWatchedRecord,
  getWatchRecords,
  recordOttClick,
} from './watch';
import { resetSessionForTest, startSession } from './session';
import type { WatchRecordListItem } from '../types/watch';

const fetchMock = vi.fn();

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function requestedUrl(index = 0): URL {
  return new URL((fetchMock.mock.calls[index] as [string, RequestInit])[0], 'http://test');
}

function requestedInit(index = 0): RequestInit {
  return (fetchMock.mock.calls[index] as [string, RequestInit])[1];
}

function record(overrides: Partial<WatchRecordListItem> = {}): WatchRecordListItem {
  return {
    watchRecordId: 5001,
    movie: { movieId: 550, title: '파이트 클럽', posterUrl: null },
    status: 'LINK_CLICKED',
    ott: { ottId: 1, code: 'NETFLIX' },
    clickedAt: '2026-08-30T05:21:00Z',
    confirmDueAt: '2026-09-01T05:21:00Z',
    expiresAt: '2026-09-06T05:21:00Z',
    watchedAt: null,
    ratingId: null,
    ...overrides,
  };
}

function page(items: WatchRecordListItem[], nextCursor: string | null = null) {
  return { items, nextCursor, hasNext: nextCursor !== null };
}

beforeEach(() => {
  resetSessionForTest();
  startSession('token');
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('API-033 OTT 링크 클릭 기록', () => {
  it('영화 경로에 `ottId`만 실어 보낸다', async () => {
    fetchMock.mockResolvedValue(
      json(201, {
        watchRecordId: 5001,
        movieId: 550,
        status: 'LINK_CLICKED',
        clickedAt: '2026-08-30T05:21:00Z',
        confirmDueAt: '2026-09-01T05:21:00Z',
        expiresAt: '2026-09-06T05:21:00Z',
      }),
    );

    const created = await recordOttClick(550, 3);

    expect(requestedUrl().pathname).toBe('/api/v1/movies/550/ott-clicks');
    expect(requestedInit().method).toBe('POST');
    expect(requestedInit().body).toBe(JSON.stringify({ ottId: 3 }));
    expect(created.watchRecordId).toBe(5001);
  });

  it('재클릭의 `200`도 새 기록과 똑같이 돌려준다', async () => {
    // 감상이 확정된 영화를 다시 누르면 상태를 바꾸지 않고 기존 기록이 온다(API-033)
    fetchMock.mockResolvedValue(
      json(200, {
        watchRecordId: 4002,
        movieId: 550,
        status: 'RATED',
        clickedAt: null,
        confirmDueAt: null,
        expiresAt: null,
      }),
    );

    await expect(recordOttClick(550, 1)).resolves.toEqual(
      expect.objectContaining({ watchRecordId: 4002, status: 'RATED', clickedAt: null }),
    );
  });
});

describe('API-034 감상 기록 목록', () => {
  it('상태 여럿은 같은 이름을 반복해 싣는다', async () => {
    fetchMock.mockResolvedValue(json(200, page([])));

    await getWatchRecords({ status: ['WATCHED_UNRATED', 'RATED'] });

    const params = requestedUrl().searchParams;

    expect(requestedUrl().pathname).toBe('/api/v1/watch-records');
    expect(params.getAll('status')).toEqual(['WATCHED_UNRATED', 'RATED']);
  });

  it('`confirmDue`는 소문자 `true`로 싣고, 빈 조건은 아무것도 싣지 않는다', async () => {
    // 한 번 읽은 Response는 다시 읽을 수 없어 호출마다 새로 만든다
    fetchMock.mockImplementation(() => Promise.resolve(json(200, page([]))));

    await getWatchRecords({ confirmDue: true });
    expect(requestedUrl().searchParams.get('confirmDue')).toBe('true');

    await getWatchRecords();
    expect(requestedUrl(1).search).toBe('');
  });
});

describe('기록 하나 찾기', () => {
  it('첫 장에 없으면 같은 조건에 커서만 더해 다음 장을 받는다', async () => {
    fetchMock
      .mockResolvedValueOnce(json(200, page([record({ watchRecordId: 5002 })], 'cursor-2')))
      .mockResolvedValueOnce(json(200, page([record({ watchRecordId: 5001 })])));

    const found = await findWatchRecord(5001);

    expect(found?.watchRecordId).toBe(5001);
    expect(requestedUrl(0).searchParams.get('cursor')).toBeNull();
    expect(requestedUrl(0).searchParams.get('size')).toBe('50');
    expect(requestedUrl(1).searchParams.get('cursor')).toBe('cursor-2');
    expect(requestedUrl(1).searchParams.get('size')).toBe('50');
  });

  it('다음 장이 없으면 더 받지 않고 null이다', async () => {
    fetchMock.mockResolvedValue(json(200, page([record({ watchRecordId: 5002 })])));

    await expect(findWatchRecord(5001)).resolves.toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('끝없이 이어지는 목록도 네 장에서 멈춘다', async () => {
    fetchMock.mockImplementation(() =>
      Promise.resolve(json(200, page([record({ watchRecordId: 5002 })], 'cursor-next'))),
    );

    await expect(findWatchRecord(5001)).resolves.toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it('2.4는 `LINK_CLICKED`만 훑는다', async () => {
    fetchMock.mockResolvedValue(json(200, page([record({ movie: movie(550) })])));

    const found = await findLinkClickedRecord(550);

    expect(found?.watchRecordId).toBe(5001);
    expect(requestedUrl().searchParams.getAll('status')).toEqual(['LINK_CLICKED']);
  });

  it('2.5는 감상을 확인한 기록만 고른다', async () => {
    // 평가가 먼저 만든 기록에는 클릭 이력도 `watchedAt`도 없다
    fetchMock.mockResolvedValue(
      json(
        200,
        page([
          record({
            watchRecordId: 4100,
            movie: movie(550),
            status: 'WATCHED_UNRATED',
            ott: null,
            clickedAt: null,
            confirmDueAt: null,
            expiresAt: null,
          }),
          record({
            watchRecordId: 4101,
            movie: movie(550),
            status: 'WATCHED_UNRATED',
            watchedAt: '2026-09-08T11:00:00Z',
          }),
        ]),
      ),
    );

    const found = await findWatchedRecord(550);

    expect(found?.watchRecordId).toBe(4101);
    expect(requestedUrl().searchParams.getAll('status')).toEqual(['WATCHED_UNRATED', 'RATED']);
  });
});

describe('API-035 감상 확인 응답', () => {
  it('`watched`를 JSON boolean으로 보낸다', async () => {
    fetchMock.mockResolvedValue(
      json(200, {
        watchRecordId: 5001,
        status: 'WATCHED_UNRATED',
        watchedAt: '2026-08-30T05:21:00Z',
      }),
    );

    const result = await confirmWatch(5001, true);

    expect(requestedUrl().pathname).toBe('/api/v1/watch-records/5001/confirm');
    expect(requestedInit().method).toBe('POST');
    expect(requestedInit().body).toBe(JSON.stringify({ watched: true }));
    expect(result.status).toBe('WATCHED_UNRATED');
  });

  it('`보지 않을래요`는 `false`를 보낸다', async () => {
    fetchMock.mockResolvedValue(
      json(200, { watchRecordId: 5001, status: 'EXPIRED', watchedAt: null }),
    );

    await confirmWatch(5001, false);

    expect(requestedInit().body).toBe(JSON.stringify({ watched: false }));
  });
});

function movie(movieId: number) {
  return { movieId, title: '영화', posterUrl: null };
}
