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
  getMyPublicProfile,
  updateProfileExtras,
  getPublicProfile,
  isSearchableNickname,
  searchUsers,
  uploadMyAvatar,
} from './users';
import { mockMyProfile } from '../mocks/user';
import { resetSessionStore, saveProfileExtras } from '../mocks/sessionStore';
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
    user: {
      userId: 2048,
      nickname: '영화광',
      avatarUrl: '/media/a.jpg',
      visibility: 'PUBLIC',
      frameVisibility: 'PUBLIC',
      popcornVisibility: 'PUBLIC',
      reviewVisibility: 'PUBLIC',
    },
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
      hidden: { frames: false, popcorn: false, reviews: false },
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

  it('항목별 공개 범위가 PRIVATE인 구역은 가린 것으로 표시한다(업무 -856)', async () => {
    fetchMock.mockResolvedValue(
      json(200, {
        ...response,
        user: { ...response.user, frameVisibility: 'PRIVATE', reviewVisibility: 'PRIVATE' },
        stats: { frameCount: 0, popcornCount: 88 },
        recentFrames: [],
        publicReviewCount: 0,
      }),
    );

    const view = await getPublicProfile('1b4e28ba-2fa1');

    expect(view.hidden).toEqual({ frames: true, popcorn: false, reviews: true });
    expect(view.frames).toEqual([]);
    expect(view.stats).toEqual({ frameCount: 0, popcornCount: 88, publicReviewCount: 0 });
    expect(view.popcorn).toEqual([{ flavorCode: 'FLAVOR_A', popcornCount: 30 }]);
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
    fetchMock.mockImplementation((url: string) =>
      Promise.resolve(
        json(
          200,
          url.includes('/frames')
            ? { items: [], nextCursor: null, hasNext: false }
            : { ...mockMyProfile, avatarUrl: '/media/avatars/a.jpg', keywords: [] },
        ),
      ),
    );

    expect((await getMyProfileView()).avatarUrl).toBe('/media/avatars/a.jpg');

    localStorage.clear();
  });
});

/*
 * 내 프로필의 취향 키워드 (업무 S15P21E106-836)
 *
 * 전에는 `mocks/publicProfiles`의 고정 네 개를 실어 **누가 로그인하든 같은 키워드**가 떴다.
 * 이제 API-040이 준 값을 싣는다. 공개 여부는 서버에 자리가 없어 기기에 남는데, 평가할 때마다
 * 키워드가 달라지므로 저장된 목록에 없는 새 코드를 어떻게 볼지가 갈린다.
 */
describe('API-040 취향 키워드를 내 프로필에 싣는다', () => {
  const KEYWORDS = [
    { code: 'DIRECTOR_BINGE', label: '봉준호 몰아보기' },
    { code: 'GENRE_DEVOTEE', label: '스릴러 편식' },
  ];

  /** 주소에 따라 다른 응답을 준다. 한 응답 객체를 두 번 읽을 수 없어 호출마다 새로 만든다 */
  function serve(keywords = KEYWORDS): void {
    fetchMock.mockImplementation((input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : String(input);

      if (url.includes('/users/me/frames')) {
        return Promise.resolve(json(200, { items: [], nextCursor: null, hasNext: false }));
      }

      if (url.includes('/users/me/taste')) {
        return Promise.resolve(
          json(200, {
            ratingCount: 30,
            insufficientData: keywords.length === 0,
            analysis: { genres: [] },
            keywords,
            analyzedAt: '2026-09-24T00:00:00Z',
          }),
        );
      }

      return Promise.resolve(json(200, mockMyProfile));
    });
  }

  beforeEach(() => {
    resetSessionStore();
  });

  it('T5 목업이 아니라 서버가 준 키워드를 싣는다', async () => {
    serve();

    const profile = await getMyProfileView();
    expect(profile.publicId).toBe(mockMyProfile.publicId);

    expect(profile.keywords.map((keyword) => keyword.label)).toEqual([
      '봉준호 몰아보기',
      '스릴러 편식',
    ]);
  });

  it('T6 기기에 저장한 공개 목록이 서버 키워드에 적용된다', async () => {
    serve();
    saveProfileExtras({ publicKeywordCodes: ['GENRE_DEVOTEE'] });

    const profile = await getMyProfileView();

    expect(profile.keywords.find((keyword) => keyword.code === 'GENRE_DEVOTEE')?.isPublic).toBe(
      true,
    );
    expect(profile.keywords.find((keyword) => keyword.code === 'DIRECTOR_BINGE')?.isPublic).toBe(
      false,
    );
  });

  it('T7 저장한 목록이 아예 없으면 모두 공개로 본다', async () => {
    serve();

    const profile = await getMyProfileView();

    expect(profile.keywords.every((keyword) => keyword.isPublic)).toBe(true);
  });

  it('취향 분석이 실패해도 프로필은 준다', async () => {
    fetchMock.mockImplementation((input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : String(input);

      if (url.includes('/users/me/frames')) {
        return Promise.resolve(json(200, { items: [], nextCursor: null, hasNext: false }));
      }

      return url.includes('/users/me/taste')
        ? Promise.reject(new Error('network'))
        : Promise.resolve(json(200, mockMyProfile));
    });

    const profile = await getMyProfileView();

    expect(profile.nickname).toBe(mockMyProfile.nickname);
    expect(profile.keywords).toEqual([]);
  });
});

describe('프로필 실제 필름 연동', () => {
  const frames = [1, 2, 3, 4].map((id) => ({
    frameId: 900 + id,
    movie: { movieId: 1000 + id, title: `내 영화 ${id}`, posterUrl: `/posters/${id}.jpg` },
    score: 4.5,
    flavorCode: 'FLAVOR_A',
    createdAt: '2026-09-27T00:00:00Z',
  }));

  beforeEach(() => {
    resetSessionStore();
    fetchMock.mockImplementation((url: string) => {
      const body = url.endsWith('/profile')
        ? {
            user: { ...mockMyProfile, userId: mockMyProfile.userId },
            keywords: [],
            stats: mockMyProfile.stats,
            popcorn: { flavors: [] },
            recentFrames: [],
            publicReviewCount: 7,
            isBlockedByMe: false,
          }
        : url.includes('/frames')
          ? url.includes('cursor=second')
            ? { items: frames.slice(2), hasNext: false, nextCursor: null }
            : { items: frames.slice(0, 2), hasNext: true, nextCursor: 'second' }
          : url.endsWith('/taste')
            ? { keywords: [] }
            : mockMyProfile;
      return Promise.resolve(json(200, body));
    });
  });

  it('모든 페이지의 실제 영화와 포스터를 최신순으로 제공한다', async () => {
    const profile = await getMyProfileView();
    expect(profile.frames.map((frame) => frame.frameId)).toEqual([901, 902, 903, 904]);
    expect(profile.frames[0]).toEqual({
      frameId: 901,
      movieId: 1001,
      title: '내 영화 1',
      posterUrl: '/posters/1.jpg',
      isPublic: true,
    });
    expect(fetchMock.mock.calls.some(([url]) => url.endsWith('/frames?cursor=second'))).toBe(true);
  });

  it('공개 선택을 저장하면 실제 ID와 포스터가 미리보기에도 유지된다', async () => {
    const saved = await updateProfileExtras({ publicFrameIds: [904] });
    expect(saved.frames.filter((frame) => frame.isPublic).map((frame) => frame.frameId)).toEqual([
      904,
    ]);
    const preview = await getMyPublicProfile();
    expect(preview.stats.publicReviewCount).toBe(7);
    expect(preview.frames).toEqual([
      expect.objectContaining({ frameId: 904, posterUrl: '/posters/4.jpg' }),
    ]);
  });

  it('미리보기는 공개된 최근 세 편만 표시하고 비공개 범위를 적용한다', async () => {
    expect((await getMyPublicProfile()).frames.map((frame) => frame.frameId)).toEqual([
      901, 902, 903,
    ]);
    fetchMock.mockImplementation((url: string) =>
      Promise.resolve(
        json(
          200,
          url.endsWith('/profile')
            ? {
                user: mockMyProfile,
                keywords: [],
                stats: mockMyProfile.stats,
                popcorn: { flavors: [] },
                recentFrames: [],
                publicReviewCount: 7,
                isBlockedByMe: false,
              }
            : url.includes('/frames')
              ? { items: frames, hasNext: false, nextCursor: null }
              : url.endsWith('/taste')
                ? { keywords: [] }
                : { ...mockMyProfile, frameVisibility: 'PRIVATE' },
        ),
      ),
    );
    expect((await getMyPublicProfile()).frames).toEqual([]);
  });

  it('실제 필름이 없으면 샘플 프레임을 넣지 않는다', async () => {
    fetchMock.mockImplementation((url: string) =>
      Promise.resolve(
        json(
          200,
          url.endsWith('/profile')
            ? {
                user: mockMyProfile,
                keywords: [],
                stats: mockMyProfile.stats,
                popcorn: { flavors: [] },
                recentFrames: [],
                publicReviewCount: 7,
                isBlockedByMe: false,
              }
            : url.includes('/frames')
              ? { items: [], hasNext: false, nextCursor: null }
              : url.endsWith('/taste')
                ? { keywords: [] }
                : mockMyProfile,
        ),
      ),
    );
    expect((await getMyProfileView()).frames).toEqual([]);
  });

  it('프레임 조회가 실패하면 목업으로 대체하지 않고 실패를 전달한다', async () => {
    fetchMock.mockImplementation((url: string) =>
      url.includes('/frames')
        ? Promise.resolve(json(503, { code: 'UNAVAILABLE', message: '조회 실패' }))
        : Promise.resolve(json(200, url.endsWith('/taste') ? { keywords: [] } : mockMyProfile)),
    );
    await expect(getMyProfileView()).rejects.toMatchObject({ status: 503 });
  });

  it('다음 커서가 반복되면 무한 요청 대신 실패한다', async () => {
    fetchMock.mockImplementation((url: string) =>
      Promise.resolve(
        json(
          200,
          url.endsWith('/profile')
            ? {
                user: mockMyProfile,
                keywords: [],
                stats: mockMyProfile.stats,
                popcorn: { flavors: [] },
                recentFrames: [],
                publicReviewCount: 7,
                isBlockedByMe: false,
              }
            : url.includes('/frames')
              ? { items: frames, hasNext: true, nextCursor: 'same' }
              : url.endsWith('/taste')
                ? { keywords: [] }
                : mockMyProfile,
        ),
      ),
    );
    await expect(getMyProfileView()).rejects.toThrow('다음 페이지');
  });
});
