/*
 * 사용자 검색 (업무 S15P21E106-393).
 *
 * 5.3이 초대할 사람을 고르는 유일한 길이다. 화면에서는 드러나지 않는 두 가지를 고정한다.
 * - `items` 래퍼를 벗기지 않는다. 검색은 이어 받을 수 있어야 해서 `nextCursor`가 필요하다
 * - **길이 검증을 화면이 먼저 한다.** 한 글자만 쳐도 요청이 나가면 매번 `400`이다
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { isSearchableNickname, searchUsers } from './users';
import { resetSessionForTest, startSession } from './session';

const fetchMock = vi.fn();

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
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

describe('API-019 사용자 검색', () => {
  it('닉네임을 질의로 보내고 커서를 그대로 돌려준다', async () => {
    fetchMock.mockResolvedValue(
      json(200, {
        items: [{ userId: 2048, nickname: '무비버디', visibility: 'PRIVATE' }],
        nextCursor: 'next',
        hasNext: true,
      }),
    );

    const page = await searchUsers('무비');

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/users/search?nickname=%EB%AC%B4%EB%B9%84');
    expect(init.method).toBe('GET');
    // 회원 전용이다. 토큰을 싣는다
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer token');
    expect(page.nextCursor).toBe('next');
    // PRIVATE도 결과에 들어온다. 빼면 닉네임 초대가 사실상 막힌다
    expect(page.items[0].visibility).toBe('PRIVATE');
  });

  it('커서를 주면 이어서 받는다', async () => {
    fetchMock.mockResolvedValue(json(200, { items: [], nextCursor: null, hasNext: false }));

    await searchUsers('무비', 'cursor-1');

    const [url] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('cursor=cursor-1');
  });

  it('찾지 못하면 빈 목록이다. 오류가 아니다', async () => {
    fetchMock.mockResolvedValue(json(200, { items: [], nextCursor: null, hasNext: false }));

    expect((await searchUsers('없는닉')).items).toEqual([]);
  });
});

describe('isSearchableNickname', () => {
  it('정규화 후 2~10자만 보낼 수 있다', () => {
    expect(isSearchableNickname('무')).toBe(false);
    expect(isSearchableNickname(' 무 ')).toBe(false);
    expect(isSearchableNickname('무비')).toBe(true);
    expect(isSearchableNickname('무 비')).toBe(true);
    expect(isSearchableNickname('12345678901')).toBe(false);
  });
});
