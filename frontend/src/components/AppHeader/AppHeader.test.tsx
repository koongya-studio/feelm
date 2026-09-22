/*
 * 탭 공통 헤더 (업무 -676)
 *
 * 필름 · 파티 · MY 어디서든 한 번에 검색 · 알림으로 간다. 워드마크는 화면 제목(h1)이 아니다 —
 * 세 탭이 모두 `Feelm`을 h1로 두면 화면을 구분하지 못한다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import AppHeader from './index';
import { renderWithRouter } from '../../test/render';
import { resetViewport, setViewportWidth } from '../../test/viewport';

vi.mock('../../api/notifications', () => ({
  getUnreadNotificationCount: vi.fn(),
}));

const { getUnreadNotificationCount } = await import('../../api/notifications');

beforeEach(() => {
  vi.mocked(getUnreadNotificationCount).mockReset().mockResolvedValue(0);
});

describe('AppHeader', () => {
  it('검색 → 알림 순서로 두 곳에 간다', async () => {
    renderWithRouter(<AppHeader />);

    const links = screen.getAllByRole('link');
    expect(links.map((link) => link.getAttribute('aria-label'))).toEqual(['검색', '알림']);
    expect(links[0]).toHaveAttribute('href', '/search');
    expect(links[1]).toHaveAttribute('href', '/notifications');
    await waitFor(() => expect(getUnreadNotificationCount).toHaveBeenCalledTimes(1));
  });

  it('워드마크는 제목(h1)이 아니다', () => {
    renderWithRouter(<AppHeader />);

    expect(screen.getByLabelText('Feelm')).toBeInTheDocument();
    expect(screen.queryByRole('heading')).not.toBeInTheDocument();
  });

  it('읽지 않은 알림이 있으면 이름과 점으로 알린다', async () => {
    vi.mocked(getUnreadNotificationCount).mockResolvedValue(3);

    renderWithRouter(<AppHeader />);

    expect(
      await screen.findByRole('link', { name: '알림, 읽지 않은 알림 3개' }),
    ).toBeInTheDocument();
    expect(screen.getByTestId('notification-dot')).toBeInTheDocument();
  });

  it('알림 수를 받지 못해도 헤더는 그대로다', async () => {
    vi.mocked(getUnreadNotificationCount).mockRejectedValue(new Error('network'));

    renderWithRouter(<AppHeader />);

    await waitFor(() => expect(getUnreadNotificationCount).toHaveBeenCalled());
    expect(screen.getByRole('link', { name: '알림' })).toBeInTheDocument();
    expect(screen.queryByTestId('notification-dot')).not.toBeInTheDocument();
  });

  /*
   * 데스크톱에서는 셸이 워드마크 · 검색 · 알림을 모두 내놓는다(업무 -782). 두 벌이 겹치면
   * 검색 진입점이 둘이고 읽지 않은 알림 수를 두 번 받으러 간다.
   */
  describe('1024px 이상', () => {
    afterEach(() => {
      resetViewport();
    });

    it('셸이 같은 셋을 내놓으므로 그리지 않는다', () => {
      setViewportWidth(1280);

      const { container } = renderWithRouter(<AppHeader />);

      expect(container.querySelector('header')).toBeNull();
      expect(screen.queryByLabelText('Feelm')).not.toBeInTheDocument();
    });

    it('읽지 않은 알림 수도 받으러 가지 않는다', () => {
      setViewportWidth(1280);

      renderWithRouter(<AppHeader />);

      expect(getUnreadNotificationCount).not.toHaveBeenCalled();
    });
  });
});
