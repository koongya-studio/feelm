/*
 * 감상 기록 (업무 S15P21E106-477) · 평가 생성 (업무 S15P21E106-491).
 *
 * 화면에서는 드러나지 않는 것을 고정한다.
 * - **평가 본문은 계약 필드만 싣는다.** 한줄평이 없으면 `visibility`도 싣지 않는다 —
 *   목업 시절 함께 넘기던 맛 코드는 서버가 정하므로 보내지 않는다(API-036)
 * - **추가 전 수치는 실제 API-014 · API-041에서 온다.** 목업 기준값 100에서 세던 자리다
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
  createRating,
  findLinkClickedRecord,
  findWatchRecord,
  findWatchedRecord,
  getRatingCounts,
  getWatchRecords,
  recordOttClick,
  updateRating,
} from './watch';
import { endSession, resetSessionForTest, startSession } from './session';
import type { Rating, WatchRecordListItem } from '../types/watch';

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

  /*
   * 겹치는 조회 합치기 (2026-09-23).
   *
   * 개발에서는 StrictMode가 effect를 두 번 실행해 같은 조회가 두 번 나갔다. 두 요청의 주소가
   * 같아 브라우저가 둘째를 첫째의 응답까지 붙잡고, 화면은 둘째 응답을 그리므로 2.2의
   * `최근 본 영화`가 카드보다 응답 시간만큼 늦게 찼다.
   */
  it('같은 순간에 같은 조건으로 부르면 요청은 한 번이다', async () => {
    let resolveFirst: ((response: Response) => void) | undefined;

    fetchMock.mockReturnValueOnce(
      new Promise<Response>((resolve) => {
        resolveFirst = resolve;
      }),
    );

    const first = getWatchRecords({ status: ['RATED'], size: 3 });
    const second = getWatchRecords({ status: ['RATED'], size: 3 });

    expect(fetchMock).toHaveBeenCalledTimes(1);

    resolveFirst?.(json(200, page([])));

    const [a, b] = await Promise.all([first, second]);

    expect(a).toEqual(b);

    // 조건이 다르면 합치지 않는다
    fetchMock.mockImplementation(() => Promise.resolve(json(200, page([]))));
    await getWatchRecords({ status: ['WATCHED_UNRATED'], size: 3 });
    expect(fetchMock).toHaveBeenCalledTimes(2);

    // 끝난 조회는 남기지 않는다. 다음 진입은 다시 묻는다
    await getWatchRecords({ status: ['RATED'], size: 3 });
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('인증 세션이 바뀌면 이전 사용자의 진행 중인 조회를 재사용하지 않는다', async () => {
    let resolveFirst: ((response: Response) => void) | undefined;
    let resolveSecond: ((response: Response) => void) | undefined;

    fetchMock
      .mockReturnValueOnce(
        new Promise<Response>((resolve) => {
          resolveFirst = resolve;
        }),
      )
      .mockReturnValueOnce(
        new Promise<Response>((resolve) => {
          resolveSecond = resolve;
        }),
      );

    startSession('user-a-token');
    const first = getWatchRecords({ status: ['RATED'], size: 3 });

    // 첫 요청이 끝나기 전에 로그아웃·재로그인한 경우를 재현한다.
    endSession('signed-out');
    startSession('user-b-token');
    const second = getWatchRecords({ status: ['RATED'], size: 3 });

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(requestedInit(0).headers).toEqual(
      expect.objectContaining({ Authorization: 'Bearer user-a-token' }),
    );
    expect(requestedInit(1).headers).toEqual(
      expect.objectContaining({ Authorization: 'Bearer user-b-token' }),
    );

    resolveFirst?.(json(200, page([record({ watchRecordId: 5001 })])));
    resolveSecond?.(json(200, page([record({ watchRecordId: 6001 })])));

    await expect(first).resolves.toEqual(page([record({ watchRecordId: 5001 })]));
    await expect(second).resolves.toEqual(page([record({ watchRecordId: 6001 })]));
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

describe('API-036 평가 생성', () => {
  const created: Rating = {
    ratingId: 91,
    movieId: 550,
    score: 4.5,
    comment: null,
    visibility: 'PRIVATE',
    spoiler: false,
    frame: { frameId: 77, createdAt: '2026-08-30T05:21:00Z' },
    popcorn: { flavorCode: 'FLAVOR_A', isNewFlavor: true, flavorCount: 1 },
    watchStatus: 'RATED',
    createdAt: '2026-08-30T05:21:00Z',
  };

  it('영화 경로에 점수만 실어 보내고 응답을 그대로 돌려준다', async () => {
    fetchMock.mockResolvedValue(json(201, created));

    const rating = await createRating(550, { score: 4.5 });

    expect(requestedUrl().pathname).toBe('/api/v1/movies/550/ratings');
    expect(requestedInit().method).toBe('POST');
    // 한줄평이 없으면 `visibility`를 싣지 않는다. 맛 코드는 서버가 정하므로 본문에 없다
    expect(requestedInit().body).toBe(JSON.stringify({ score: 4.5 }));
    expect(rating).toEqual(created);
  });

  it('한줄평이 있으면 공개 범위를 함께 싣는다', async () => {
    fetchMock.mockResolvedValue(json(201, { ...created, comment: '밤에 보면 안 됨' }));

    await createRating(550, {
      score: 4.5,
      comment: '밤에 보면 안 됨',
      visibility: 'PRIVATE',
      spoiler: true,
    });

    expect(requestedInit().body).toBe(
      JSON.stringify({
        score: 4.5,
        comment: '밤에 보면 안 됨',
        visibility: 'PRIVATE',
        spoiler: true,
      }),
    );
  });

  it('이미 평가한 영화의 `409`는 코드 그대로 올라온다', async () => {
    fetchMock.mockResolvedValue(
      json(409, { code: 'RATING_ALREADY_EXISTS', message: '이미 평가한 영화입니다.' }),
    );

    await expect(createRating(550, { score: 4.5 })).rejects.toMatchObject({
      status: 409,
      code: 'RATING_ALREADY_EXISTS',
    });
  });
});

describe('API-037 평가 수정', () => {
  it('평가 ID 경로에 전체 교체 본문을 PUT하고 응답을 돌려준다', async () => {
    const updated: Rating = {
      ratingId: 91,
      movieId: 550,
      score: 3.5,
      comment: '다시 보니 달랐다',
      visibility: 'PUBLIC',
      spoiler: true,
      frame: { frameId: 77, createdAt: '2026-08-30T05:21:00Z' },
      popcorn: { flavorCode: 'FLAVOR_A', isNewFlavor: false, flavorCount: 1 },
      watchStatus: 'RATED',
      createdAt: '2026-08-30T05:21:00Z',
    };
    fetchMock.mockResolvedValue(json(200, updated));

    await expect(
      updateRating(91, {
        score: 3.5,
        comment: '다시 보니 달랐다',
        visibility: 'PUBLIC',
        spoiler: true,
      }),
    ).resolves.toEqual(updated);
    expect(requestedUrl().pathname).toBe('/api/v1/ratings/91');
    expect(requestedInit().method).toBe('PUT');
    expect(requestedInit().body).toBe(
      JSON.stringify({
        score: 3.5,
        comment: '다시 보니 달랐다',
        visibility: 'PUBLIC',
        spoiler: true,
      }),
    );
  });
});

describe('프레임 · 팝콘 수치', () => {
  it('프레임 수는 API-014, 팝콘 알 수는 API-041에서 읽는다', async () => {
    fetchMock.mockImplementation((url: string) =>
      Promise.resolve(
        url.includes('/users/me/popcorn')
          ? json(200, { totalCount: 7, flavors: [] })
          : json(200, { stats: { frameCount: 12, popcornCount: 7, ratingCount: 12 } }),
      ),
    );

    const counts = await getRatingCounts();

    const paths = fetchMock.mock.calls.map(
      (call) => new URL(call[0] as string, 'http://test').pathname,
    );
    expect(paths).toEqual(expect.arrayContaining(['/api/v1/users/me', '/api/v1/users/me/popcorn']));
    expect(counts).toMatchObject({ frameCount: 12, popcornCount: 7 });
  });
});

function movie(movieId: number) {
  return { movieId, title: '영화', posterUrl: null };
}
