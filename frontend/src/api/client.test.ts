/*
 * 공통 HTTP 클라이언트 (업무 S15P21E106-364).
 *
 * 화면이 아니라 계약을 지키는 계층이라 눈으로 확인할 수 없다. 특히 만료 토큰 재시도는
 * 실패 조건이 드물게 겹칠 때만 드러나므로(동시 요청, 되살릴 세션이 없는 경우) 여기서
 * 고정한다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from './apiError';
import { request } from './client';
import { getAccessToken, resetSessionForTest, startSession } from './session';

const REFRESH_URL = '/api/v1/auth/token/refresh';

const fetchMock = vi.fn();

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function expired(): Response {
  return json(401, { code: 'TOKEN_EXPIRED', message: '토큰이 만료되었습니다.' });
}

function authorization(init: RequestInit | undefined): string | undefined {
  return (init?.headers as Record<string, string> | undefined)?.Authorization;
}

beforeEach(() => {
  resetSessionForTest();
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('공통 HTTP 클라이언트', () => {
  it('회원 API는 Access Token을 Bearer로 싣고 쿠키를 함께 보낸다', async () => {
    startSession('token');
    fetchMock.mockResolvedValue(json(200, { nickname: '팝콘러버' }));

    await request('/users/me');

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];

    expect(url).toBe('/api/v1/users/me');
    expect(authorization(init)).toBe('Bearer token');
    expect(init.credentials).toBe('include');
  });

  it('공개 API는 토큰을 싣지 않는다', async () => {
    startSession('token');
    fetchMock.mockResolvedValue(json(200, {}));

    await request('/auth/login', { method: 'POST', body: {}, auth: 'none' });

    expect(authorization(fetchMock.mock.calls[0]?.[1] as RequestInit)).toBeUndefined();
  });

  it('질의 문자열에서 값이 없는 항목은 빠진다', async () => {
    fetchMock.mockResolvedValue(json(200, {}));

    await request('/movies/search', { query: { q: '영화', cursor: undefined }, auth: 'optional' });

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/movies/search?q=%EC%98%81%ED%99%94');
  });

  it('배열은 같은 이름을 반복해 싣고 빈 배열은 빠진다', async () => {
    fetchMock.mockResolvedValue(json(200, {}));

    // 계약이 정한 배열 표기다(API-028). 인덱스 표기는 서버가 400으로 거절한다
    await request('/movies/search', { query: { genre: ['ACTION', 'DRAMA'], ott: [] } });

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/movies/search?genre=ACTION&genre=DRAMA');
  });

  it('오류 응답의 code와 message가 ApiError로 전달된다', async () => {
    fetchMock.mockResolvedValue(
      json(409, { code: 'NICKNAME_DUPLICATED', message: '이미 사용 중인 닉네임입니다.' }),
    );

    const error = await request('/auth/signup', { auth: 'none' }).catch(
      (caught: unknown) => caught,
    );

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(409);
    expect((error as ApiError).code).toBe('NICKNAME_DUPLICATED');
    expect((error as ApiError).message).toBe('이미 사용 중인 닉네임입니다.');
  });

  it('규약 형식이 아닌 오류 본문도 ApiError가 된다', async () => {
    fetchMock.mockResolvedValue(new Response('<html>502</html>', { status: 502 }));

    const error = await request('/users/me').catch((caught: unknown) => caught);

    expect((error as ApiError).status).toBe(502);
    expect((error as ApiError).code).toBe('UNEXPECTED_ERROR');
  });

  it('요청이 서버에 닿지 못하면 NETWORK_ERROR다', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));

    const error = await request('/users/me').catch((caught: unknown) => caught);

    expect((error as ApiError).code).toBe('NETWORK_ERROR');
    expect((error as ApiError).status).toBe(0);
  });

  it('본문 없는 응답은 undefined로 끝난다', async () => {
    startSession('token');
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await expect(request<void>('/auth/logout', { method: 'POST' })).resolves.toBeUndefined();
  });

  it('401을 받으면 재발급한 토큰으로 한 번 다시 보낸다', async () => {
    startSession('old');
    fetchMock.mockImplementation((url: string, init: RequestInit) => {
      if (url === REFRESH_URL) {
        return Promise.resolve(json(200, { accessToken: 'new', expiresIn: 1800 }));
      }

      return Promise.resolve(
        authorization(init) === 'Bearer new' ? json(200, { nickname: '팝콘러버' }) : expired(),
      );
    });

    await expect(request('/users/me')).resolves.toEqual({ nickname: '팝콘러버' });
    expect(getAccessToken()).toBe('new');
  });

  it('동시에 401을 받아도 재발급은 한 번만 호출된다', async () => {
    startSession('old');
    let refreshCalls = 0;
    fetchMock.mockImplementation((url: string, init: RequestInit) => {
      if (url === REFRESH_URL) {
        refreshCalls += 1;

        return Promise.resolve(json(200, { accessToken: 'new', expiresIn: 1800 }));
      }

      return Promise.resolve(authorization(init) === 'Bearer new' ? json(200, {}) : expired());
    });

    await Promise.all([request('/users/me'), request('/users/me/blocks'), request('/parties')]);

    expect(refreshCalls).toBe(1);
  });

  it('재발급이 거절되면 원래 오류를 그대로 던지고 이후 요청은 재발급을 시도하지 않는다', async () => {
    fetchMock.mockImplementation((url: string) => {
      if (url === REFRESH_URL) {
        return Promise.resolve(json(401, { code: 'REFRESH_TOKEN_INVALID', message: '만료' }));
      }

      return Promise.resolve(json(401, { code: 'UNAUTHORIZED', message: '인증이 필요합니다.' }));
    });

    const first = await request('/users/me').catch((caught: unknown) => caught);

    expect((first as ApiError).code).toBe('UNAUTHORIZED');

    fetchMock.mockClear();

    const second = await request('/users/me').catch((caught: unknown) => caught);

    expect((second as ApiError).code).toBe('UNAUTHORIZED');
    // 두 번째 요청은 본 요청 한 번뿐이다. 되살릴 세션이 없는 것을 이미 안다
    expect(fetchMock.mock.calls).toHaveLength(1);
  });

  it('공개 API의 401은 재발급하지 않는다', async () => {
    fetchMock.mockResolvedValue(
      json(401, {
        code: 'INVALID_CREDENTIALS',
        message: '이메일 또는 비밀번호가 올바르지 않습니다.',
      }),
    );

    const error = await request('/auth/login', {
      method: 'POST',
      body: {},
      auth: 'none',
    }).catch((caught: unknown) => caught);

    expect((error as ApiError).code).toBe('INVALID_CREDENTIALS');
    expect(fetchMock.mock.calls).toHaveLength(1);
  });
});
