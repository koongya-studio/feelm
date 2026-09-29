/*
 * 개발 서버 전용 목업 계정 — 서버로 보내지 않고 로그인 · 복구 · 로그아웃이 되는지
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { login, logout, restoreSession } from './auth';
import { MOCK_ACCOUNT, isMockSession } from './mockAccount';
import { getAccessToken, resetSessionForTest } from './session';
import { getMyProfile, getMyProfileView, updateMyProfile } from './users';
import { getMyRatings } from './watch';

const fetchMock = vi.fn();

beforeEach(() => {
  sessionStorage.clear();
  resetSessionForTest();
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('목업 계정', () => {
  it('서버로 보내지 않고 로그인하고, 새로고침 뒤에도 복구되며, 로그아웃하면 끝난다', async () => {
    const user = await login(MOCK_ACCOUNT.email, MOCK_ACCOUNT.password);

    expect(user).toEqual({ userId: 1024, nickname: '김난다', onboardingCompleted: true });
    expect(getAccessToken()).not.toBeNull();
    expect((await getMyProfile()).nickname).toBe('김난다');

    // 새로고침 — 메모리 세션은 사라지고 표시만 남는다
    resetSessionForTest();
    expect((await restoreSession())?.nickname).toBe('김난다');

    expect((await updateMyProfile({ nickname: '팝콘러버' })).nickname).toBe('팝콘러버');
    expect((await getMyProfile()).nickname).toBe('팝콘러버');

    await logout();
    expect(isMockSession()).toBe(false);
    expect(getAccessToken()).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('백엔드 없이 MY 프로필과 정렬된 평가 목록을 보여준다', async () => {
    await login(MOCK_ACCOUNT.email, MOCK_ACCOUNT.password);

    const profile = await getMyProfileView();
    expect(profile.nickname).toBe('김난다');
    expect(profile.frames).toHaveLength(3);

    const latest = await getMyRatings();
    const highest = await getMyRatings({ sort: 'SCORE_DESC' });
    const allWatched = await getMyRatings({ filter: 'ALL' });
    const unratedOnly = await getMyRatings({ filter: 'UNRATED' });
    expect(latest.items.map((item) => item.movie.movieId)).toEqual([401, 402, 403]);
    expect(highest.items.map((item) => item.movie.movieId)).toEqual([403, 401, 402]);
    expect(allWatched.items.map((item) => item.movie.movieId)).toEqual([101, 401, 402, 403]);
    expect(unratedOnly.items.map((item) => item.movie.movieId)).toEqual([101]);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('비밀번호가 다르면 실제 로그인 API로 보낸다', async () => {
    fetchMock.mockRejectedValue(new TypeError('network'));

    await expect(login(MOCK_ACCOUNT.email, 'wrong')).rejects.toBeDefined();
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(isMockSession()).toBe(false);
  });
});
