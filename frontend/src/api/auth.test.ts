/*
 * 새로고침 세션 복구 (업무 S15P21E106-908)
 *
 * 재발급 뒤의 프로필 조회(API-014)가 일시 오류면 다시 부르고, 끝내 실패하면 오류를 던진다.
 * 비로그인으로 떨어뜨릴지는 `AuthProvider`가 가른다(`AuthProvider.test.tsx`). 재발급이
 * 거절되면 지금처럼 비로그인(`null`)이다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { restoreSession } from './auth';
import { RETRY_DELAYS_MS } from './retry';
import { canRefresh, resetSessionForTest } from './session';
import { mockMyProfile } from '../mocks/user';

const fetchMock = vi.fn();

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const refreshed = () => json(200, { accessToken: 'new', expiresIn: 1800 });
const profile = () => json(200, mockMyProfile);
const unavailable = () => json(503, { code: 'SERVICE_UNAVAILABLE', message: '점검 중' });

/** 불린 주소만 순서대로 */
const calledPaths = () =>
  fetchMock.mock.calls.map(([url]) => new URL(String(url), 'http://localhost').pathname);

/** 재시도 대기를 모두 넘긴다 */
async function flushRetries(result: Promise<unknown>) {
  result.catch(() => undefined);
  await vi.advanceTimersByTimeAsync(RETRY_DELAYS_MS.reduce((sum, ms) => sum + ms, 0));
}

beforeEach(() => {
  resetSessionForTest();
  sessionStorage.clear();
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('restoreSession', () => {
  it('재발급 뒤 프로필이 한 번 5xx여도 다시 불러 로그인 상태를 되돌린다', async () => {
    fetchMock
      .mockResolvedValueOnce(refreshed())
      .mockResolvedValueOnce(unavailable())
      .mockResolvedValueOnce(profile());

    const result = restoreSession();
    await flushRetries(result);

    await expect(result).resolves.toMatchObject({ userId: mockMyProfile.userId });
    expect(calledPaths()).toEqual([
      '/api/v1/auth/token/refresh',
      '/api/v1/users/me',
      '/api/v1/users/me',
    ]);
  });

  it('프로필 요청이 서버에 닿지 못해도 다시 부른다', async () => {
    fetchMock
      .mockResolvedValueOnce(refreshed())
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(profile());

    const result = restoreSession();
    await flushRetries(result);

    await expect(result).resolves.toMatchObject({ userId: mockMyProfile.userId });
  });

  it('일시 오류가 이어지면 횟수를 다 쓴 뒤 오류를 던지고, 세션은 끝내지 않는다', async () => {
    fetchMock.mockResolvedValueOnce(refreshed()).mockResolvedValue(unavailable());

    const result = restoreSession();
    await flushRetries(result);

    await expect(result).rejects.toMatchObject({ status: 503 });
    expect(calledPaths().filter((p) => p === '/api/v1/users/me')).toHaveLength(
      RETRY_DELAYS_MS.length + 1,
    );
    expect(canRefresh()).toBe(true);
  });

  it('영구적인 4xx는 다시 부르지 않는다', async () => {
    fetchMock
      .mockResolvedValueOnce(refreshed())
      .mockResolvedValue(json(404, { code: 'USER_NOT_FOUND', message: '없음' }));

    const result = restoreSession();
    await flushRetries(result);

    await expect(result).rejects.toMatchObject({ status: 404 });
    expect(calledPaths().filter((p) => p === '/api/v1/users/me')).toHaveLength(1);
  });

  it('재발급이 거절되면 프로필을 부르지 않고 비로그인이다', async () => {
    fetchMock.mockResolvedValue(json(401, { code: 'REFRESH_TOKEN_INVALID', message: '만료' }));

    await expect(restoreSession()).resolves.toBeNull();
    expect(calledPaths()).toEqual(['/api/v1/auth/token/refresh']);
    expect(canRefresh()).toBe(false);
  });
});

/*
 * !677 리뷰. 복구 실패 화면의 `다시 시도`는 이 함수를 다시 부른다. 그때는 앞선 재발급이 성공해
 * 세션이 살아 있다는 것을 안다. 재발급부터 다시 보내면 그 요청이 잠깐 실패하는 것만으로 비로그인이
 * 됐다.
 */
describe('restoreSession · 다시 시도', () => {
  /** 주소마다 응답을 정한다. 재발급은 늘 503이다 — 불리면 안 된다 */
  function route(profileStatus: () => number) {
    fetchMock.mockImplementation((url: string) => {
      const path = new URL(url, 'http://localhost').pathname;

      if (path === '/api/v1/auth/token/refresh') return Promise.resolve(unavailable());

      return Promise.resolve(profileStatus() === 200 ? profile() : unavailable());
    });
  }

  it('재발급 성공 → 프로필 실패 뒤 다시 시도하면 재발급 없이 프로필만 다시 부른다', async () => {
    fetchMock.mockResolvedValueOnce(refreshed()).mockResolvedValue(unavailable());

    const first = restoreSession();
    await flushRetries(first);
    await expect(first).rejects.toMatchObject({ status: 503 });

    fetchMock.mockReset();
    route(() => 200);

    await expect(restoreSession()).resolves.toMatchObject({ userId: mockMyProfile.userId });
    expect(calledPaths()).toEqual(['/api/v1/users/me']);
    expect(canRefresh()).toBe(true);
  });

  it('다시 시도에서도 프로필이 실패하면 비로그인(null)이 아니라 오류를 던진다', async () => {
    fetchMock.mockResolvedValueOnce(refreshed()).mockResolvedValue(unavailable());

    const first = restoreSession();
    await flushRetries(first);
    await expect(first).rejects.toMatchObject({ status: 503 });

    fetchMock.mockReset();
    route(() => 503);

    const again = restoreSession();
    await flushRetries(again);

    await expect(again).rejects.toMatchObject({ status: 503 });
    expect(calledPaths()).not.toContain('/api/v1/auth/token/refresh');
    expect(canRefresh()).toBe(true);
  });

  it('그사이 토큰이 만료돼 클라이언트의 재발급이 잠깐 실패해도 세션을 끝내지 않는다', async () => {
    fetchMock.mockResolvedValueOnce(refreshed()).mockResolvedValue(unavailable());

    const first = restoreSession();
    await flushRetries(first);
    await expect(first).rejects.toMatchObject({ status: 503 });

    // 프로필은 401(만료), 클라이언트가 보내는 재발급은 503
    fetchMock.mockReset();
    fetchMock.mockImplementation((url: string) => {
      const path = new URL(url, 'http://localhost').pathname;

      return Promise.resolve(
        path === '/api/v1/auth/token/refresh'
          ? unavailable()
          : json(401, { code: 'TOKEN_EXPIRED', message: '만료' }),
      );
    });

    const again = restoreSession();
    await flushRetries(again);

    await expect(again).rejects.toMatchObject({ status: 401 });
    // 재발급이 거절된 것이 아니므로 세션은 살아 있다 — AuthProvider가 다시 시도를 보인다
    expect(canRefresh()).toBe(true);
  });
});
