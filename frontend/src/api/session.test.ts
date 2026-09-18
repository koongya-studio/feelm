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
  endSession,
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
    expect(ended).toHaveBeenCalledWith('expired');
  });

  /*
   * 앱이 뜰 때의 복구 재발급은 비로그인 방문자에게도 나간다(`AuthProvider`). 쿠키가 없으면
   * 서버는 그것도 401로 거절하므로, 이것을 만료로 다루면 처음 온 사람이 만료 안내와 함께
   * 로그인 화면으로 밀려난다(리뷰 A-1·B-1).
   */
  it('쓰던 세션이 없었다면 만료가 아니라 비로그인으로 알린다', async () => {
    const ended = vi.fn();
    onSessionEnd(ended);
    fetchMock.mockResolvedValue(json(401, { code: 'REFRESH_TOKEN_INVALID', message: '없음' }));

    await expect(refreshAccessToken()).resolves.toBeNull();
    expect(ended).toHaveBeenCalledWith('unauthenticated');
    expect(canRefresh()).toBe(false);
  });

  it('로그아웃으로 끝난 세션은 만료와 구분해서 알린다', () => {
    startSession('old');
    const ended = vi.fn();
    onSessionEnd(ended);

    endSession('signed-out');

    expect(ended).toHaveBeenCalledWith('signed-out');
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

  /*
   * 탭 안의 중복은 `inFlight`가 막지만 탭 사이는 막지 못한다. 쿠키를 공유하는 다른 탭이
   * 동시에 재발급하면 서버가 두 번째를 재사용으로 보기 때문에(API-006) 잠금 안에서 보낸다.
   */
  it('재발급은 탭 간 잠금 안에서 보낸다', async () => {
    const request = vi.fn(
      async (_name: string, _options: unknown, run: () => Promise<string | null>) => run(),
    );
    vi.stubGlobal('navigator', { locks: { request } });
    fetchMock.mockResolvedValue(json(200, { accessToken: 'locked', expiresIn: 1800 }));

    await expect(refreshAccessToken()).resolves.toBe('locked');

    expect(request).toHaveBeenCalledTimes(1);
    expect(request.mock.calls[0][0]).toBe('feelm:auth:refresh');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  /*
   * 잠금은 순서를 세우는 편의일 뿐이다. 잠금을 얻지 못했다고 재발급을 포기하거나 오류를
   * 던지면, 잠금을 쥔 탭이 멈춘 동안 다른 탭이 아무것도 못 한다(리뷰 A-6·B-5).
   */
  it('잠금을 얻지 못하면 조율을 포기하고 그냥 보낸다', async () => {
    const request = vi.fn().mockRejectedValue(new DOMException('aborted', 'AbortError'));
    vi.stubGlobal('navigator', { locks: { request } });
    fetchMock.mockResolvedValue(json(200, { accessToken: 'sent-anyway', expiresIn: 1800 }));

    await expect(refreshAccessToken()).resolves.toBe('sent-anyway');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('잠금 안에서 난 오류를 잠금 실패로 오인해 두 번 보내지 않는다', async () => {
    const request = vi.fn(async (_name: string, _options: unknown, run: () => Promise<unknown>) => {
      await run();

      throw new Error('잠금이 풀린 뒤 터졌다');
    });
    vi.stubGlobal('navigator', { locks: { request } });
    fetchMock.mockResolvedValue(json(200, { accessToken: 'once', expiresIn: 1800 }));

    await expect(refreshAccessToken()).rejects.toThrow('잠금이 풀린 뒤 터졌다');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('잠금을 못 쓰는 브라우저에서도 재발급한다', async () => {
    vi.stubGlobal('navigator', {});
    fetchMock.mockResolvedValue(json(200, { accessToken: 'unlocked', expiresIn: 1800 }));

    await expect(refreshAccessToken()).resolves.toBe('unlocked');
    expect(getAccessToken()).toBe('unlocked');
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
