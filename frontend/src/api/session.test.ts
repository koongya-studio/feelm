/*
 * 인증 세션의 재발급 판정 (업무 S15P21E106-373).
 *
 * 재발급 실패에는 성격이 다른 두 가지가 섞여 있다. 세션이 없어서 거절된 것과 서버가
 * 잠시 응답하지 못한 것이다. 둘을 같게 다루면 백엔드가 잠깐 죽었다 살아난 뒤에도
 * 재발급을 다시 시도하지 않아 새로고침 전까지 로그아웃된 것처럼 보인다. 화면에서는
 * 백엔드를 내렸다 올려야 재현되므로 여기서 고정한다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  canRefresh,
  getAccessToken,
  onSessionEnd,
  refreshAccessToken,
  resetSessionForTest,
  startSession,
} from './session';

const fetchMock = vi.fn();

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

beforeEach(() => {
  resetSessionForTest();
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('토큰 재발급', () => {
  it('성공하면 새 토큰을 세션에 넣는다', async () => {
    fetchMock.mockResolvedValue(json(200, { accessToken: 'new', expiresIn: 1800 }));

    await expect(refreshAccessToken()).resolves.toBe('new');
    expect(getAccessToken()).toBe('new');
    expect(canRefresh()).toBe(true);
  });

  it('401이면 세션을 끝내고 다시 시도하지 않는다', async () => {
    startSession('old');
    const ended = vi.fn();
    onSessionEnd(ended);
    fetchMock.mockResolvedValue(json(401, { code: 'REFRESH_TOKEN_INVALID', message: '만료' }));

    await expect(refreshAccessToken()).resolves.toBeNull();
    expect(getAccessToken()).toBeNull();
    expect(canRefresh()).toBe(false);
    expect(ended).toHaveBeenCalled();
  });

  it.each([500, 502, 503, 504])('%d는 서버 사정이므로 세션을 유지한다', async (status) => {
    startSession('old');
    const ended = vi.fn();
    onSessionEnd(ended);
    fetchMock.mockResolvedValue(new Response('<html>error</html>', { status }));

    await expect(refreshAccessToken()).resolves.toBeNull();
    expect(canRefresh()).toBe(true);
    expect(ended).not.toHaveBeenCalled();
  });

  it('요청이 서버에 닿지 못해도 세션을 유지한다', async () => {
    startSession('old');
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));

    await expect(refreshAccessToken()).resolves.toBeNull();
    expect(canRefresh()).toBe(true);
  });

  it('본문에 토큰이 없는 200은 계약 위반이지 세션 만료가 아니다', async () => {
    startSession('old');
    fetchMock.mockResolvedValue(json(200, { expiresIn: 1800 }));

    await expect(refreshAccessToken()).resolves.toBeNull();
    expect(canRefresh()).toBe(true);
  });

  it('장애로 실패한 뒤 서버가 돌아오면 다시 재발급된다', async () => {
    startSession('old');
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 503 }));

    await expect(refreshAccessToken()).resolves.toBeNull();

    fetchMock.mockResolvedValueOnce(json(200, { accessToken: 'recovered', expiresIn: 1800 }));

    await expect(refreshAccessToken()).resolves.toBe('recovered');
    expect(getAccessToken()).toBe('recovered');
  });
});
