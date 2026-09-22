/*
 * 7.2-B 다른 사용자 공개 프로필 · 7.2-A 내 공개 프로필 미리보기 (업무 S15P21E106-305 · -616 · -810)
 *
 * **두 화면은 한 컴포넌트다**(업무 -616). 미리보기는 따로 그리지 않고 남이 보는 화면을
 * 그대로 연다. 그래서 여기서 고정하는 것은 둘이 **같다는 것**과 **다른 곳이 권한뿐이라는 것**이다.
 *
 * 7.2-B는 실제 API-062를 부른다(업무 -810). 화면에서 드러나지 않는 것을 함께 고정한다.
 * - 주소의 `publicId`로 부르고, 차단은 본문의 **숫자** `user.userId`로 한다
 * - 한줄평은 개수만 오므로 목록 자리에 `없어요`라고 쓰지 않는다
 *
 * **이 화면에는 주 동작이 없다.** 검정 알약 하나가 7.2-C 취향 비교로 가던 자리인데,
 * 비교를 하지 않기로 하면서 그 화면을 지웠다(업무 -506).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, screen, within } from '@testing-library/react';
import PublicProfilePage from './index';
import { resetSessionForTest, startSession } from '../../../api/session';
import { resetSessionStore, saveProfileExtras } from '../../../mocks/sessionStore';
import { mockMyProfile } from '../../../mocks/user';
import { renderWithRouter } from '../../../test/render';
import type { PublicProfile } from '../../../types/user';

/** 공개 프로필 주소의 식별자. `users.public_id`(UUID)다 */
const PUBLIC_ID = '1b4e28ba-2fa1-11d2-883f-0016d3cca427';
/** API-062 본문의 내부 숫자 ID. 차단이 이 값을 쓴다 */
const USER_ID = 2048;

/** API-062 응답. 계약 예시와 같은 모양이다 */
const buddy: PublicProfile = {
  user: { userId: USER_ID, nickname: '무비버디', avatarUrl: null, visibility: 'PUBLIC' },
  keywords: [
    { code: 'TWIST_LOVER', label: '반전 선호' },
    { code: 'NOIR', label: '느와르' },
  ],
  stats: { frameCount: 86, popcornCount: 86 },
  popcorn: {
    flavors: [
      { flavorCode: 'FLAVOR_A', popcornCount: 18, averageScore: 4.5 },
      { flavorCode: 'FLAVOR_B', popcornCount: 0, averageScore: null },
    ],
  },
  recentFrames: [1, 2, 3, 4].map((index) => ({
    frameId: 190 + index,
    movie: { movieId: 550 + index, title: `최근 영화 ${index}`, posterUrl: null },
    score: 4.5,
    createdAt: '2026-09-20T05:21:00Z',
  })),
  publicReviewCount: 21,
  isBlockedByMe: false,
};

function json(status: number, body: unknown) {
  return Promise.resolve(
    new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    }),
  );
}

/** 가짜 서버. 차단 응답은 시험마다 바꿀 수 있다 */
let blockResponse: () => Promise<Response> = () =>
  json(201, { blockedUserId: USER_ID, blockedAt: '2026-09-16T00:00:00Z' });

function stubServer() {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      // 미리보기가 내 프로필을 받는다(API-014)
      if (url === '/api/v1/users/me') return json(200, mockMyProfile);

      if (url === `/api/v1/users/${PUBLIC_ID}/profile`) return json(200, buddy);

      if (url.endsWith('/profile')) {
        return json(404, { code: 'RESOURCE_NOT_FOUND', message: '프로필을 찾을 수 없습니다.' });
      }

      if (url === `/api/v1/users/${USER_ID}/block` && init?.method === 'POST') {
        return blockResponse();
      }

      throw new Error(`가짜 서버가 모르는 요청이다: ${url}`);
    }),
  );
}

beforeEach(() => {
  resetSessionStore();
  resetSessionForTest();
  startSession('token');
  blockResponse = () => json(201, { blockedUserId: USER_ID, blockedAt: '2026-09-16T00:00:00Z' });
  stubServer();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

async function renderProfile(publicId: string = PUBLIC_ID) {
  const result = renderWithRouter(<PublicProfilePage />, {
    route: `/users/${publicId}`,
    path: '/users/:publicId',
  });
  await screen.findByRole('heading', { name: '무비버디' });

  return result;
}

async function renderPreview() {
  const result = renderWithRouter(<PublicProfilePage mode="preview" />, {
    route: '/my/profile/preview',
  });
  await screen.findByRole('heading', { name: mockMyProfile.nickname });

  return result;
}

/** 구역 제목을 차례대로. 두 화면의 배치가 같은지 견준다 */
const sectionTitles = () =>
  screen
    .getAllByRole('heading', { level: 2 })
    .slice(1)
    .map((heading) => heading.textContent);

describe('7.2-B 다른 사용자 공개 프로필', () => {
  it('주소의 publicId로 API-062를 부른다. 로그인했으면 토큰을 싣는다', async () => {
    await renderProfile();

    const call = vi
      .mocked(fetch)
      .mock.calls.find(([url]) => String(url) === `/api/v1/users/${PUBLIC_ID}/profile`);
    expect(call).toBeDefined();
    const headers = (call![1] as RequestInit).headers as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer token');
  });

  it('취향 비교로 가는 버튼을 두지 않는다', async () => {
    /*
     * 7.2-C를 지웠으므로(업무 -506) 이 버튼이 되살아나면 없는 경로로 보내게 된다.
     * 화면에서 지운 것을 시험으로 고정한다.
     */
    await renderProfile();

    expect(screen.queryByRole('button', { name: '나와 취향 비교하기' })).not.toBeInTheDocument();
  });

  it('API-062 응답을 그린다 — 수치 · 키워드 · 필름 세 편 · 팝콘 버킷 · 한줄평 수', async () => {
    await renderProfile();

    expect(screen.getByRole('heading', { name: '공개 프로필', level: 1 })).toBeInTheDocument();
    expect(sectionTitles()).toEqual(['취향 키워드', '취향 필름', '팝콘 버킷', '공개 한줄평']);

    const stat = (label: string) => screen.getByText(label, { selector: 'dt' }).nextSibling;
    expect(stat('프레임')).toHaveTextContent('86');
    expect(stat('공개 한줄평')).toHaveTextContent('21');

    expect(screen.getByText('반전 선호')).toBeInTheDocument();
    expect(screen.getByText('느와르')).toBeInTheDocument();

    // 최근 프레임 네 개 중 앞의 세 편만 보인다(7.2-A와 같은 규칙). 남의 프레임이라 링크가 아니다
    const films = screen.getByRole('heading', { name: '취향 필름' }).closest('section')!;
    expect(films.querySelectorAll('li')).toHaveLength(3);
    expect(within(films).queryByRole('link')).not.toBeInTheDocument();

    expect(screen.getByText('팝콘 86알')).toBeInTheDocument();

    // 소개는 계약에 없다. 빈 소개를 그리지 않는다
    expect(screen.queryByText('나를 소개하는 한 줄을 적어 보세요')).not.toBeInTheDocument();

    // 팔로워는 Feelm에 없는 개념이다
    expect(screen.queryByText(/팔로워|팔로잉/)).not.toBeInTheDocument();

    // 남의 프로필에는 고치는 연필도, 미리보기 안내 바도 없다
    expect(screen.queryByRole('button', { name: /수정/ })).not.toBeInTheDocument();
    expect(screen.queryByText('다른 사용자에게 보이는 내 프로필입니다.')).not.toBeInTheDocument();
  });

  it('한줄평은 개수만 오므로 목록 자리에 없다고 쓰지 않는다', async () => {
    await renderProfile();

    const reviews = screen.getByRole('heading', { name: '공개 한줄평' }).closest('section')!;
    expect(within(reviews).getByText('한줄평 목록은 아직 볼 수 없어요')).toBeInTheDocument();
    expect(within(reviews).queryByText('아직 공개한 한줄평이 없어요')).not.toBeInTheDocument();
  });

  it('차단은 확인 창을 거쳐 숫자 ID로 차단하고 7.8-D로 간다', async () => {
    await renderProfile();

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    fireEvent.click(screen.getByRole('button', { name: '사용자 차단' }));

    const dialog = await screen.findByRole('alertdialog');
    expect(within(dialog).getByText('무비버디 님을 차단할까요?')).toBeInTheDocument();
    expect(within(dialog).getByText(/함께 있는 파티에서는 나가게 돼요/)).toBeInTheDocument();

    fireEvent.click(within(dialog).getByRole('button', { name: '차단' }));

    await vi.waitFor(() =>
      expect(screen.getByTestId('location').textContent).toBe('/my/settings/blocks'),
    );
    const post = vi
      .mocked(fetch)
      .mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === 'POST');
    expect(post?.[0]).toBe(`/api/v1/users/${USER_ID}/block`);
  });

  it('이미 차단한 사람이면 성공과 같이 7.8-D로 간다', async () => {
    blockResponse = () => json(409, { code: 'ALREADY_BLOCKED', message: '이미 차단했습니다.' });
    await renderProfile();

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    fireEvent.click(screen.getByRole('button', { name: '사용자 차단' }));
    fireEvent.click(
      within(await screen.findByRole('alertdialog')).getByRole('button', { name: '차단' }),
    );

    await vi.waitFor(() =>
      expect(screen.getByTestId('location').textContent).toBe('/my/settings/blocks'),
    );
  });

  it('차단에 실패하면 화면에 남고 서버 문구로 알린다', async () => {
    blockResponse = () =>
      json(400, { code: 'VALIDATION_FAILED', message: '자기 자신은 차단할 수 없어요.' });
    await renderProfile();

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    fireEvent.click(screen.getByRole('button', { name: '사용자 차단' }));
    fireEvent.click(
      within(await screen.findByRole('alertdialog')).getByRole('button', { name: '차단' }),
    );

    expect(await screen.findByText('자기 자신은 차단할 수 없어요.')).toBeInTheDocument();
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(screen.getByTestId('location').textContent).toBe(`/users/${PUBLIC_ID}`);
  });

  it('신고는 계약이 없어 안내만 하고 서버를 부르지 않는다', async () => {
    await renderProfile();

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    fireEvent.click(screen.getByRole('button', { name: '신고하기' }));

    expect(await screen.findByText('신고 내용은 운영팀이 확인해요')).toBeInTheDocument();
    const posts = vi
      .mocked(fetch)
      .mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === 'POST');
    expect(posts).toHaveLength(0);
  });

  it('없음 · 비공개 · 차단 관계(404)면 볼 수 없다고 알리고, 다시 시도를 두지 않는다', async () => {
    renderWithRouter(<PublicProfilePage />, {
      route: '/users/not-a-member',
      path: '/users/:publicId',
    });

    expect(await screen.findByText('프로필을 볼 수 없어요')).toBeInTheDocument();
    expect(screen.getByText('탈퇴했거나 프로필을 공개하지 않은 사용자예요')).toBeInTheDocument();
    // 없는 사람은 다시 불러도 없다
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument();
  });
});

describe('7.2-A 내 공개 프로필 미리보기', () => {
  it('남의 프로필과 같은 배치다. 위에 안내 바만 붙는다', async () => {
    await renderProfile();
    const others = sectionTitles();
    cleanup();

    await renderPreview();

    expect(sectionTitles()).toEqual(others);
    expect(screen.getByRole('heading', { name: '공개 프로필', level: 1 })).toBeInTheDocument();
    expect(screen.getByText('다른 사용자에게 보이는 내 프로필입니다.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '편집으로 돌아가기' })).toBeInTheDocument();
  });

  it('내 프로필은 한 번만 받는다', async () => {
    // 내 번호를 알려고 한 번, 내 프로필을 만들려고 또 한 번 부르던 것을 줄였다(!402 리뷰)
    await renderPreview();

    const profileCalls = vi
      .mocked(fetch)
      .mock.calls.filter(([url]) => String(url) === '/api/v1/users/me');
    expect(profileCalls).toHaveLength(1);
  });

  it('비공개로 둔 것과 본인 전용 기능 · 자기에게 하는 차단 · 신고가 없다', async () => {
    saveProfileExtras({ publicKeywordCodes: [], publicFrameIds: [] });
    await renderPreview();

    expect(screen.getByText('공개된 취향 키워드가 없어요')).toBeInTheDocument();
    expect(screen.getByText('공개된 취향 필름이 없어요')).toBeInTheDocument();

    expect(screen.queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /차단|신고/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /수정/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /설정|프로필 편집/ })).not.toBeInTheDocument();
    // 비공개 정보(이메일)는 공개 모양에 아예 없다
    expect(screen.queryByText(mockMyProfile.email)).not.toBeInTheDocument();
  });
});
