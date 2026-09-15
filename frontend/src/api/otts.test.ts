/*
 * OTT 기준정보와 구독 설정 (업무 S15P21E106-379).
 *
 * 화면에서는 보이지 않는 계약의 모양을 고정한다. 목업 시절 이 세 함수는 모두 `Ott[]`를
 * 돌려줬는데, **계약은 API-025만 목록이고 API-026 · API-027은 식별자 배열**이다. 모양을
 * 되돌리면 화면이 `ott.ottId`를 `undefined`로 읽으면서도 조용히 렌더되므로 여기서 막는다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getMyOttIds, getOtts, setMyOttIds } from './otts';
import { resetSessionForTest, startSession } from './session';

const fetchMock = vi.fn();

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function call(index = 0) {
  return fetchMock.mock.calls[index] as [string, RequestInit];
}

beforeEach(() => {
  resetSessionForTest();
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('API-025 OTT 목록 조회', () => {
  it('`items` 래퍼를 벗겨 목록으로 돌려준다', async () => {
    const items = [{ ottId: 1, code: 'NETFLIX', name: '넷플릭스', logoUrl: null }];
    fetchMock.mockResolvedValue(json(200, { items }));

    expect(await getOtts()).toEqual(items);
    expect(call()[0]).toBe('/api/v1/otts');
  });

  it('공개 API라 토큰을 싣지 않는다', async () => {
    startSession('token');
    fetchMock.mockResolvedValue(json(200, { items: [] }));

    await getOtts();

    const headers = call()[1].headers as Record<string, string>;
    expect(headers.Authorization).toBeUndefined();
  });
});

describe('API-026 내 구독 OTT 조회', () => {
  it('식별자 배열을 그대로 돌려준다', async () => {
    startSession('token');
    fetchMock.mockResolvedValue(json(200, { ottIds: [1, 3] }));

    expect(await getMyOttIds()).toEqual([1, 3]);
    expect(call()[0]).toBe('/api/v1/users/me/otts');
  });

  // 설정한 적이 없으면 빈 배열이다. 목업이 쓰던 기준값 `[1, 2, 3, 4]`는 계약에 없다
  it('설정한 적이 없으면 빈 배열이다', async () => {
    startSession('token');
    fetchMock.mockResolvedValue(json(200, { ottIds: [] }));

    expect(await getMyOttIds()).toEqual([]);
  });
});

describe('API-027 내 구독 OTT 설정', () => {
  it('목록을 통째로 PUT한다', async () => {
    startSession('token');
    fetchMock.mockResolvedValue(json(200, { ottIds: [1, 3] }));

    await setMyOttIds([1, 3]);

    const [url, init] = call();
    expect(url).toBe('/api/v1/users/me/otts');
    expect(init.method).toBe('PUT');
    expect(JSON.parse(init.body as string)).toEqual({ ottIds: [1, 3] });
  });

  /*
   * 서버가 중복을 제거하고 저장하므로 응답이 요청보다 짧을 수 있다(API-027 처리 규칙).
   * 화면은 보낸 값이 아니라 받은 값을 반영해야 한다.
   */
  it('보낸 값이 아니라 서버가 저장한 값을 돌려준다', async () => {
    startSession('token');
    fetchMock.mockResolvedValue(json(200, { ottIds: [1] }));

    expect(await setMyOttIds([1, 1])).toEqual([1]);
  });

  it('빈 배열은 전체 해제다', async () => {
    startSession('token');
    fetchMock.mockResolvedValue(json(200, { ottIds: [] }));

    expect(await setMyOttIds([])).toEqual([]);
    expect(JSON.parse(call()[1].body as string)).toEqual({ ottIds: [] });
  });
});
