/*
 * 7.2-B 다른 사용자 공개 프로필 (업무 S15P21E106-305)
 *
 * **이 화면에는 주 동작이 없다.** 검정 알약 하나가 7.2-C 취향 비교로 가던 자리인데,
 * 비교를 하지 않기로 하면서 그 화면을 지웠다(업무 -506). 읽는 화면으로 남았고
 * 이동표의 `7.2-B → 7.2-C`도 함께 사라졌다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, within } from '@testing-library/react';
import PublicProfilePage from './index';
import { resetSessionForTest, startSession } from '../../../api/session';
import { resetSessionStore } from '../../../mocks/sessionStore';
import { renderWithRouter } from '../../../test/render';

/**
 * 프로필은 목업이고 이 화면은 실제 API를 부르지 않는다(업무 -610). 혹시 나가는 요청이 있으면
 * 여기서 잡힌다 — 그것이 곧 시험 실패다.
 */
function stubServer() {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
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
  // 화면 제목은 2.4의 영화 제목처럼 본문 머리의 이름이다(ProfileBoard)
  await screen.findByRole('heading', { name: '무비버디' });

  return result;
}

describe('7.2-B 다른 사용자 공개 프로필', () => {
  it('취향 비교로 가는 버튼을 두지 않는다', async () => {
    /*
     * 7.2-C를 지웠으므로(업무 -506) 이 버튼이 되살아나면 없는 경로로 보내게 된다.
     * 화면에서 지운 것을 시험으로 고정한다.
     */
    await renderProfile();

    expect(screen.queryByRole('button', { name: '나와 취향 비교하기' })).not.toBeInTheDocument();
  });

  it('2.4와 같은 화면이다 — 이름 · 키워드 · 수치 한 줄, 팝콘 · 필름 · 한줄평 구역', async () => {
    /*
     * 업무 -605. 그전의 수치 3열(`dl`)은 없고 이름 아래 한 줄이다. 팔로워는 Feelm에 없는
     * 개념이고 `나와 일치`는 계약(API-063)이 미구현이라 없다(업무 -459).
     */
    await renderProfile();

    expect(screen.queryByRole('definition')).not.toBeInTheDocument();
    expect(screen.queryByText(/팔로워|팔로잉/)).not.toBeInTheDocument();
    expect(screen.queryByText('나와 일치')).not.toBeInTheDocument();

    expect(screen.getByRole('heading', { name: '취향 팝콘' })).toBeInTheDocument();
    expect(screen.getByText(/^팝콘 \d+알$/)).toBeInTheDocument();

    // 다른 사람의 프레임이라 상세로 가는 링크가 아니다
    const films = screen.getByRole('heading', { name: '취향 필름' }).parentElement!;
    expect(films.querySelectorAll('li').length).toBeGreaterThan(0);
    expect(within(films).queryByRole('link')).not.toBeInTheDocument();

    expect(screen.getByRole('heading', { name: '공개 한줄평' })).toBeInTheDocument();
    expect(screen.getByText('스릴러만 먹다가 드라마 맛도 늘었어요.')).toBeInTheDocument();
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
    expect(await screen.findByRole('status')).toHaveTextContent('신고 내용은 운영팀이 확인해요');

    const posts = vi
      .mocked(fetch)
      .mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === 'POST');
    expect(posts).toHaveLength(0);
    expect(screen.getByTestId('location').textContent).toBe(`/users/${USER_ID}`);
  });

  it('없는 사용자면 안내를 보여준다', async () => {
    renderWithRouter(<PublicProfilePage />, {
      route: '/users/9999',
      path: '/users/:userId',
    });

    expect(await screen.findByText('프로필을 불러오지 못했어요')).toBeInTheDocument();
  });
});
