/*
 * 사용자 검색 (업무 S15P21E106-393) · 공개 프로필 (업무 -810) · 프로필 사진 (업무 -762).
 *
 * 5.3이 초대할 사람을 고르는 유일한 길이다. 화면에서는 드러나지 않는 두 가지를 고정한다.
 * - `items` 래퍼를 벗기지 않는다. 검색은 이어 받을 수 있어야 해서 `nextCursor`가 필요하다
 * - **길이 검증을 화면이 먼저 한다.** 한 글자만 쳐도 요청이 나가면 매번 `400`이다
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  deleteMyAvatar,
  getMyProfileView,
  getPublicProfile,
  isSearchableNickname,
  searchUsers,
  uploadMyAvatar,
} from './users';
import { mockMyProfile } from '../mocks/user';
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

/*
 * 업무 -810. API-062 응답은 네 프로필 화면이 함께 쓰는 모양으로 바뀌어 나간다. 화면만 보면
 * 매핑이 틀려도 비슷해 보이므로 규칙을 여기서 고정한다.
 */
describe('API-062 공개 프로필', () => {
  const response = {
    user: { userId: 2048, nickname: '영화광', avatarUrl: '/media/a.jpg', visibility: 'PUBLIC' },
    keywords: [{ code: 'DARK_THRILLER_LOVER', label: '어둠 속 스릴러 애호가' }],
    stats: { frameCount: 88, popcornCount: 88 },
    popcorn: { flavors: [{ flavorCode: 'FLAVOR_A', popcornCount: 30, averageScore: 4.5 }] },
    recentFrames: [1, 2, 3, 4].map((index) => ({
      frameId: 190 + index,
      movie: { movieId: 550 + index, title: `영화 ${index}`, posterUrl: null },
      score: 5,
      createdAt: '2026-08-30T05:21:00Z',
    })),
    publicReviewCount: 21,
    isBlockedByMe: false,
  };

  it('publicId를 경로에 넣고, 로그인했으면 토큰을 싣는다', async () => {
    fetchMock.mockResolvedValue(json(200, response));

    await getPublicProfile('1b4e28ba-2fa1');

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/users/1b4e28ba-2fa1/profile');
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer token');
  });

  it('비회원이면 토큰 없이 부른다(선택 인증)', async () => {
    resetSessionForTest();
    fetchMock.mockResolvedValue(json(200, response));

    await getPublicProfile('1b4e28ba-2fa1');

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect((init.headers as Record<string, string>).Authorization).toBeUndefined();
  });

  it('화면 모양으로 바꾼다 — 필름은 앞의 세 편, 소개는 비고, 한줄평 목록은 없음(null)', async () => {
    fetchMock.mockResolvedValue(json(200, response));

    const view = await getPublicProfile('1b4e28ba-2fa1');

    expect(view).toMatchObject({
      userId: 2048,
      nickname: '영화광',
      avatarUrl: '/media/a.jpg',
      bio: '',
      stats: { frameCount: 88, popcornCount: 88, publicReviewCount: 21 },
      keywords: [{ code: 'DARK_THRILLER_LOVER', label: '어둠 속 스릴러 애호가', isPublic: true }],
      popcorn: [{ flavorCode: 'FLAVOR_A', popcornCount: 30 }],
      reviews: null,
    });
    expect(view.frames.map((frame) => frame.frameId)).toEqual([191, 192, 193]);
    expect(view.frames[0]).toEqual({
      frameId: 191,
      movieId: 551,
      title: '영화 1',
      posterUrl: null,
      isPublic: true,
    });
  });

  it('없음 · 비공개 · 차단 관계는 404로 올라간다', async () => {
    fetchMock.mockResolvedValue(
      json(404, { code: 'RESOURCE_NOT_FOUND', message: '프로필을 찾을 수 없습니다.' }),
    );

    await expect(getPublicProfile('nope')).rejects.toMatchObject({ status: 404 });
  });
});

describe('API-081 · 082 프로필 사진', () => {
  it('잘린 사진을 multipart `file` 칸으로 PUT하고 서버가 준 주소를 돌려준다', async () => {
    fetchMock.mockResolvedValue(json(200, { avatarUrl: '/media/avatars/new.jpg' }));
    const photo = new Blob(['jpeg'], { type: 'image/jpeg' });

    await expect(uploadMyAvatar(photo)).resolves.toBe('/media/avatars/new.jpg');

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/users/me/avatar');
    expect(init.method).toBe('PUT');
    expect(init.body).toBeInstanceOf(FormData);
    expect(await ((init.body as FormData).get('file') as Blob).text()).toBe('jpeg');
    // 경계(boundary)는 브라우저가 붙인다. 직접 쓰면 서버가 본문을 나누지 못한다
    expect(init.headers).not.toHaveProperty('Content-Type');
  });

  it('거절은 계약의 코드 그대로 올라간다', async () => {
    fetchMock.mockResolvedValue(json(413, { code: 'AVATAR_TOO_LARGE', message: '너무 큼' }));

    await expect(uploadMyAvatar(new Blob(['x']))).rejects.toMatchObject({
      status: 413,
      code: 'AVATAR_TOO_LARGE',
    });
  });

  it('지우기는 DELETE이고 204를 받는다', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await expect(deleteMyAvatar()).resolves.toBeUndefined();
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/users/me/avatar',
      expect.objectContaining({ method: 'DELETE' }),
    );
  });

  it('내 프로필 사진은 API-014의 `avatarUrl`이다. 기기 저장소를 읽지 않는다', async () => {
    localStorage.setItem(`feelm.avatar.${mockMyProfile.userId}`, 'data:image/jpeg;base64,OLD');
    fetchMock.mockResolvedValue(json(200, { ...mockMyProfile, avatarUrl: '/media/avatars/a.jpg' }));

    expect((await getMyProfileView()).avatarUrl).toBe('/media/avatars/a.jpg');

    localStorage.clear();
  });
});
