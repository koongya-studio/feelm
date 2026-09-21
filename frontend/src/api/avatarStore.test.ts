/*
 * 프로필 사진 기기 저장 (업무 S15P21E106-740).
 *
 * 화면으로는 드러나지 않는 것을 고정한다.
 * - **다시 열어도 남는다.** 한때 세션 메모리에만 두어 새로고침하면 사라졌다. 여기서는 세션
 *   저장소를 비워(= 앱을 다시 연 것) 사진이 남는지 본다
 * - **계정별이다.** 다른 계정으로 로그인하면 앞 계정의 사진이 보이지 않아야 한다
 * - **저장하지 못하면 던진다.** 조용히 넘어가면 저장된 줄 알았던 사진이 다음에 열 때 사라진다
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { loadAvatar, saveAvatar } from './avatarStore';
import { resetSessionForTest, startSession } from './session';
import { getMyProfileView, updateProfileExtras } from './users';
import { resetSessionStore } from '../mocks/sessionStore';
import { mockMyProfile } from '../mocks/user';
import type { MyProfile } from '../types/user';

const PHOTO = 'data:image/jpeg;base64,AAAA';

let me: MyProfile;

function stubMe() {
  vi.stubGlobal(
    'fetch',
    vi.fn(
      async () =>
        new Response(JSON.stringify(me), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
    ),
  );
}

beforeEach(() => {
  localStorage.clear();
  resetSessionStore();
  resetSessionForTest();
  startSession('token');
  me = { ...mockMyProfile };
  stubMe();
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('프로필 사진 기기 저장', () => {
  it('올린 사진은 앱을 다시 열어도 남는다', async () => {
    await updateProfileExtras({ avatarUrl: PHOTO });

    // 앱을 다시 연 것처럼 세션 메모리를 비운다
    resetSessionStore();

    expect((await getMyProfileView()).avatarUrl).toBe(PHOTO);
  });

  it('기본으로 돌리면 다시 열어도 기본 이미지다', async () => {
    await updateProfileExtras({ avatarUrl: PHOTO });
    await updateProfileExtras({ avatarUrl: null });
    resetSessionStore();

    expect((await getMyProfileView()).avatarUrl).toBeNull();
    expect(loadAvatar(me.userId)).toBeNull();
  });

  it('다른 계정으로 로그인하면 앞 계정의 사진이 보이지 않는다', async () => {
    await updateProfileExtras({ avatarUrl: PHOTO });

    me = { ...mockMyProfile, userId: me.userId + 1, nickname: '다른사람' };

    expect((await getMyProfileView()).avatarUrl).toBeNull();

    // 앞 계정으로 돌아오면 그 사진이 다시 보인다
    me = { ...mockMyProfile };
    expect((await getMyProfileView()).avatarUrl).toBe(PHOTO);
  });

  it('사진이 아닌 항목을 저장해도 사진은 그대로다', async () => {
    await updateProfileExtras({ avatarUrl: PHOTO });
    await updateProfileExtras({ bio: '영화 좋아해요' });

    expect((await getMyProfileView()).avatarUrl).toBe(PHOTO);
  });

  it('저장소가 가득 차 쓰지 못하면 실패를 올린다', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('full', 'QuotaExceededError');
    });

    await expect(updateProfileExtras({ avatarUrl: PHOTO })).rejects.toThrow();
    expect(loadAvatar(me.userId)).toBeNull();
  });

  it('저장소를 읽을 수 없으면 기본 이미지로 둔다', () => {
    saveAvatar(me.userId, PHOTO);
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });

    expect(loadAvatar(me.userId)).toBeNull();
  });
});
