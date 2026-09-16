/*
 * 7.2-B 다른 사용자 공개 프로필 → 7.2-C 취향 비교 (업무 S15P21E106-305)
 *
 * 이동표 `7.2-B → 7.2-C`는 **대상 사용자가 이어져야** 성립한다. 비교 화면으로 가기만
 * 하고 누구와 비교하는지가 빠지면 화면은 열리지만 경로는 끊긴 것이다
 * (screen-spec-my.md 2장 9번).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import PublicProfilePage from './index';
import { resetSessionForTest, startSession } from '../../../api/session';
import { resetSessionStore } from '../../../mocks/sessionStore';
import { renderWithRouter } from '../../../test/render';

/**
 * 프로필은 목업이지만 차단은 실제 API다(API-020, 업무 -394). 화면을 그릴 때 차단 목록
 * (API-022)도 함께 나가므로 둘 다 가로챈다. 차단한 사람이 없는 상태에서 시작한다.
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
  // 화면 제목은 히어로 안의 이름이다. 헤더를 없애고 이미지가 상단을 차지한다(ProfileHero)
  await screen.findByRole('heading', { name: '무비버디' });

  return result;
}

describe('7.2-B 다른 사용자 공개 프로필', () => {
  it('비교하기를 누르면 그 사용자의 비교 화면으로 간다', async () => {
    await renderProfile();

    fireEvent.click(screen.getByRole('button', { name: '나와 취향 비교하기' }));

    await waitFor(() =>
      expect(screen.getByTestId('location').textContent).toBe(`/users/${USER_ID}/compare`),
    );
  });

  it('팔로워 대신 프레임 · 팝콘 · 나와의 일치도를 보여준다', async () => {
    // Feelm에 팔로워 개념이 없다. 레퍼런스의 3열을 서비스에 맞게 바꾼 자리다
    await renderProfile();

    expect(screen.getByText('프레임')).toBeInTheDocument();
    expect(screen.getByText('나와 일치')).toBeInTheDocument();
    expect(screen.getByText('76%')).toBeInTheDocument();
    expect(screen.queryByText(/팔로워|팔로잉/)).not.toBeInTheDocument();
  });

  it('차단은 확인을 거쳐 7.8-D로 간다', async () => {
    /*
     * 이동표 `7.2-B ⇢ 7.8-D`. 305에서는 7.8-D가 없어 안내만 했다(업무 -307에서 연결).
     * 확인 창은 공동 파티에서 나가게 된다는 것을 알려야 한다(API-020 처리 규칙).
     * 차단 자체는 API-020으로 나간다(업무 -394).
     */
    await renderProfile();

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    fireEvent.click(screen.getByRole('button', { name: '사용자 차단' }));

    const dialog = await screen.findByRole('alertdialog', { name: '무비버디 님을 차단할까요?' });
    expect(dialog).toHaveTextContent('함께 있는 파티에서는 나가게 돼요');

    fireEvent.click(within(dialog).getByRole('button', { name: '차단' }));

    await waitFor(() =>
      expect(screen.getByTestId('location').textContent).toBe('/my/settings/blocks'),
    );
  });

  it('차단이 거절되면 계약이 준 문구를 그대로 보여준다', async () => {
    /*
     * 지금 7.2-B에 들어오는 번호는 목업이라 실제 백엔드에는 없는 사람이다. 그때 오는
     * 404를 `잠시 후 다시 시도해 주세요`로 뭉개면 왜 안 되는지 알 수 없다.
     */
    await renderProfile();
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({ code: 'RESOURCE_NOT_FOUND', message: '사용자를 찾을 수 없습니다.' }),
            { status: 404, headers: { 'Content-Type': 'application/json' } },
          ),
        ),
      ),
    );

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    fireEvent.click(screen.getByRole('button', { name: '사용자 차단' }));
    const dialog = await screen.findByRole('alertdialog');
    fireEvent.click(within(dialog).getByRole('button', { name: '차단' }));

    expect(await screen.findByText('사용자를 찾을 수 없습니다.')).toBeInTheDocument();
    expect(screen.getByTestId('location').textContent).toBe(`/users/${USER_ID}`);
  });

  it('이미 차단한 사람이면 차단된 상태이므로 7.8-D로 간다', async () => {
    // 두 탭에서 같은 프로필을 열어 둔 경우다. 409는 실패가 아니라 바라던 상태다
    await renderProfile();
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({ code: 'ALREADY_BLOCKED', message: '이미 차단한 사용자입니다.' }),
            { status: 409, headers: { 'Content-Type': 'application/json' } },
          ),
        ),
      ),
    );

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    fireEvent.click(screen.getByRole('button', { name: '사용자 차단' }));
    const dialog = await screen.findByRole('alertdialog');
    fireEvent.click(within(dialog).getByRole('button', { name: '차단' }));

    await waitFor(() =>
      expect(screen.getByTestId('location').textContent).toBe('/my/settings/blocks'),
    );
  });

  it('차단을 취소하면 아무 일도 없다', async () => {
    await renderProfile();

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    fireEvent.click(screen.getByRole('button', { name: '사용자 차단' }));
    fireEvent.click(await screen.findByRole('button', { name: '취소' }));

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
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
