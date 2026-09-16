/*
 * 7.1 MY 대시보드 — 이동 경로 (업무 S15P21E106-305)
 *
 * 메뉴 5개는 눌러서 어디로 가는지가 전부다. 링크 주소가 어긋나도 화면은 멀쩡해 보이므로
 * 목적지를 고정한다(screen-spec-my.md 2장 2 · 6 · 12 · 13번).
 *
 * `api/users`를 통째로 가로챈다. API-014가 실제 호출이라(업무 -364) 가로채지 않으면
 * 테스트가 네트워크를 두드린다. develop의 `LoginPage.test.tsx`와 같은 방식이다.
 * 필름 카드가 부르는 API-042도 같은 이유로 가로챈다(업무 -392).
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { screen } from '@testing-library/react';
import MyDashboardPage from './index';
import { renderWithRouter } from '../../../test/render';
import { getMyProfile } from '../../../api/users';
import { mockMyProfile } from '../../../mocks/user';

vi.mock('../../../api/users', () => ({
  getMyProfile: vi.fn(),
}));

vi.mock('../../../api/taste', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/taste')>()),
  getMyFrames: vi.fn(),
}));

const { getMyFrames } = await import('../../../api/taste');

beforeEach(() => {
  vi.mocked(getMyProfile).mockReset().mockResolvedValue(mockMyProfile);
  vi.mocked(getMyFrames)
    .mockReset()
    .mockResolvedValue({ totalCount: 0, items: [], nextCursor: null, hasNext: false });
});

async function renderDashboard() {
  const result = renderWithRouter(<MyDashboardPage />, { route: '/my' });
  await screen.findByRole('navigation', { name: 'MY 메뉴' });

  return result;
}

describe('7.1 MY 대시보드', () => {
  it('메뉴 5개가 각자의 화면으로 간다', async () => {
    await renderDashboard();

    const menu = screen.getByRole('navigation', { name: 'MY 메뉴' });

    const destinations = Object.fromEntries(
      [...menu.querySelectorAll('a')].map((link) => [
        link.textContent?.trim(),
        link.getAttribute('href'),
      ]),
    );

    expect(destinations).toEqual({
      '취향 리포트': '/my/reports',
      '추천 기록': '/my/recommendations',
      평가: '/my/ratings',
      'OTT 관리': '/otts',
      설정: '/my/settings',
    });
  });

  it('편집과 전체 보기가 각각 7.2와 3.1로 간다', async () => {
    await renderDashboard();

    expect(screen.getByRole('link', { name: '편집' })).toHaveAttribute('href', '/my/profile/edit');
    // `전체 보기`는 이동표에 목적지가 없던 화살표다. 3.1로 확정했다(2026-09-11)
    expect(screen.getByRole('link', { name: /전체 보기/ })).toHaveAttribute('href', '/film');
  });

  it('가입 연월을 계약의 createdAt에서 만든다', async () => {
    /*
     * 305 첫 구현은 `joinedAt`이라는 없는 필드를 읽었다. API-014가 주는 것은
     * `createdAt`이다. 이름이 어긋나면 화면에 `· 가입`만 남는다.
     */
    await renderDashboard();

    expect(screen.getByText(/2026\.08 가입/)).toBeInTheDocument();
  });

  it('수치는 100 기준이다', async () => {
    /*
     * 와이어프레임 7.1은 `본 영화 13 · 평가 10`이지만 같은 사용자의 7.2-A · 3.1 · 3.2는
     * 100이다. 100으로 통일하기로 했다(mocks/user.ts).
     */
    await renderDashboard();

    expect(screen.getByText('100프레임')).toBeInTheDocument();
  });
});
