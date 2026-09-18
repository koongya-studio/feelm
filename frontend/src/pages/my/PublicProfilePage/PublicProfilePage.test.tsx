/*
 * 7.2-B 다른 사용자 공개 프로필 · 7.2-A 내 공개 프로필 미리보기 (업무 S15P21E106-305 · -616)
 *
 * **두 화면은 한 컴포넌트다**(업무 -616). 미리보기는 따로 그리지 않고 남이 보는 화면을
 * 그대로 연다. 그래서 여기서 고정하는 것은 둘이 **같다는 것**과 **다른 곳이 권한뿐이라는 것**이다.
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

/**
 * 프로필은 목업이고 이 화면은 실제 API를 부르지 않는다(업무 -610). 혹시 나가는 요청이 있으면
 * 여기서 잡힌다 — 그것이 곧 시험 실패다.
 */
function stubServer() {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      // 미리보기가 내 번호를 알려고 부른다(API-014)
      if (url === '/api/v1/users/me') {
        return Promise.resolve(
          new Response(JSON.stringify(mockMyProfile), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        );
      }

      if (url.startsWith('/api/v1/users/me/blocks')) {
        return Promise.resolve(
          new Response(JSON.stringify({ items: [], nextCursor: null, hasNext: false }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        );
      }

      if (init?.method === 'POST') {
        return Promise.resolve(
          new Response(
            JSON.stringify({ blockedUserId: USER_ID, blockedAt: '2026-09-16T00:00:00Z' }),
            { status: 201, headers: { 'Content-Type': 'application/json' } },
          ),
        );
      }

      throw new Error(`가짜 서버가 모르는 요청이다: ${url}`);
    }),
  );
}

beforeEach(() => {
  resetSessionStore();
  resetSessionForTest();
  startSession('token');
  stubServer();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

/** `mocks/publicProfiles.ts`의 무비버디. 5.8 파티원과 같은 사람이다 */
const USER_ID = 2048;

async function renderProfile(userId: number = USER_ID) {
  const result = renderWithRouter(<PublicProfilePage />, {
    route: `/users/${userId}`,
    path: '/users/:userId',
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
  it('취향 비교로 가는 버튼을 두지 않는다', async () => {
    /*
     * 7.2-C를 지웠으므로(업무 -506) 이 버튼이 되살아나면 없는 경로로 보내게 된다.
     * 화면에서 지운 것을 시험으로 고정한다.
     */
    await renderProfile();

    expect(screen.queryByRole('button', { name: '나와 취향 비교하기' })).not.toBeInTheDocument();
  });

  it('시안 그대로 — 머리 · 수치 · 키워드 · 필름 · 팝콘 버킷 · 공개 한줄평', async () => {
    await renderProfile();

    expect(screen.getByRole('heading', { name: '공개 프로필', level: 1 })).toBeInTheDocument();
    expect(screen.getByText('스릴러로 시작해 드라마까지 넓히는 중')).toBeInTheDocument();
    for (const label of ['프레임', '팝콘', '공개 한줄평']) {
      expect(screen.getByText(label, { selector: 'dt' })).toBeInTheDocument();
    }
    expect(sectionTitles()).toEqual(['취향 키워드', '취향 필름', '팝콘 버킷', '공개 한줄평']);

    // 팔로워는 Feelm에 없는 개념이고 `나와 일치`는 계약(API-063)이 미구현이다(업무 -459)
    expect(screen.queryByText(/팔로워|팔로잉/)).not.toBeInTheDocument();
    expect(screen.queryByText('나와 일치')).not.toBeInTheDocument();

    // 다른 사람의 프레임이라 상세로 가는 링크가 아니다
    const films = screen.getByRole('heading', { name: '취향 필름' }).closest('section')!;
    expect(films.querySelectorAll('li').length).toBeGreaterThan(0);
    expect(within(films).queryByRole('link')).not.toBeInTheDocument();

    expect(screen.getByText('스릴러만 먹다가 드라마 맛도 늘었어요.')).toBeInTheDocument();

    // 남의 프로필에는 고치는 연필도, 미리보기 안내 바도 없다
    expect(screen.queryByRole('button', { name: /수정/ })).not.toBeInTheDocument();
    expect(screen.queryByText('다른 사용자에게 보이는 내 프로필입니다.')).not.toBeInTheDocument();
  });

  it('차단은 준비 중이라 눌리지 않고, 목업 번호로 실제 차단 API를 부르지 않는다', async () => {
    /*
     * 프로필이 목업(API-062 미구현)인 동안 실제 차단(API-020)을 부르면 서버에 없는 번호라
     * 늘 404였다(업무 -610). 신고는 계약이 없어 안내만 한다.
     */
    await renderProfile();

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));

    const block = screen.getByRole('button', { name: /사용자 차단/ });
    expect(block).toBeDisabled();
    expect(block).toHaveTextContent('준비 중');

    fireEvent.click(screen.getByRole('button', { name: '신고하기' }));
    expect(await screen.findByText('신고 내용은 운영팀이 확인해요')).toBeInTheDocument();

    const posts = vi
      .mocked(fetch)
      .mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === 'POST');
    expect(posts).toHaveLength(0);
    expect(screen.getByTestId('location').textContent).toBe(`/users/${USER_ID}`);
  });

  it('없는 사용자면 볼 수 없다고 알리고, 다시 시도를 두지 않는다', async () => {
    renderWithRouter(<PublicProfilePage />, {
      route: '/users/9999',
      path: '/users/:userId',
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
