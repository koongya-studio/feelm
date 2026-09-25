/*
 * 데스크톱 상단 바 (업무 -782)
 *
 * 적고 Enter를 치면 검색어를 들고 4.1로 간다. 질의 이름이 4.1이 읽는 것과 달라지면 검색어를
 * 들고 들어가도 빈 화면이 열리므로, 주소를 그대로 확인한다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import DesktopTopBar from './index';
import { renderWithRouter } from '../../test/render';

vi.mock('../../api/notifications', () => ({
  getUnreadNotificationCount: vi.fn(),
}));

const { getUnreadNotificationCount } = await import('../../api/notifications');

beforeEach(() => {
  vi.mocked(getUnreadNotificationCount).mockReset().mockResolvedValue(0);
});

describe('DesktopTopBar', () => {
  it('검색어를 들고 4.1로 간다', () => {
    renderWithRouter(<DesktopTopBar />, { route: '/film' });

    const input = screen.getByLabelText('영화, 감독, 배우 검색');

    fireEvent.change(input, { target: { value: '봉준호' } });
    fireEvent.submit(screen.getByRole('search'));

    expect(screen.getByTestId('location').textContent).toBe(
      '/search?q=%EB%B4%89%EC%A4%80%ED%98%B8',
    );
  });

  it('빈 채로 보내면 조건 없는 4.1을 연다', () => {
    renderWithRouter(<DesktopTopBar />, { route: '/film' });

    fireEvent.submit(screen.getByRole('search'));

    expect(screen.getByTestId('location').textContent).toBe('/search');
  });

  it('보내고 나면 입력을 비운다', () => {
    renderWithRouter(<DesktopTopBar />, { route: '/film' });

    const input = screen.getByLabelText('영화, 감독, 배우 검색');

    fireEvent.change(input, { target: { value: '봉준호' } });
    fireEvent.submit(screen.getByRole('search'));

    expect(input).toHaveValue('');
  });

  it('앞뒤 공백만 적으면 조건 없는 4.1을 연다', () => {
    renderWithRouter(<DesktopTopBar />, { route: '/film' });

    fireEvent.change(screen.getByLabelText('영화, 감독, 배우 검색'), { target: { value: '   ' } });
    fireEvent.submit(screen.getByRole('search'));

    expect(screen.getByTestId('location').textContent).toBe('/search');
  });

  it('읽지 않은 알림이 있으면 이름과 점으로 알린다', async () => {
    vi.mocked(getUnreadNotificationCount).mockResolvedValue(3);

    renderWithRouter(<DesktopTopBar />);

    expect(
      await screen.findByRole('link', { name: '알림, 읽지 않은 알림 3개' }),
    ).toBeInTheDocument();
    expect(screen.getByTestId('desktop-notification-dot')).toBeInTheDocument();
  });

  it('알림 수를 받지 못해도 상단 바는 그대로다', async () => {
    vi.mocked(getUnreadNotificationCount).mockRejectedValue(new Error('network'));

    renderWithRouter(<DesktopTopBar />);

    await waitFor(() => expect(getUnreadNotificationCount).toHaveBeenCalled());
    expect(screen.getByRole('link', { name: '알림' })).toBeInTheDocument();
    expect(screen.queryByTestId('desktop-notification-dot')).not.toBeInTheDocument();
  });

  it('계정 메뉴는 좌측 내비에만 둔다', () => {
    renderWithRouter(<DesktopTopBar />);
    expect(screen.queryByRole('button', { name: /계정 메뉴/ })).not.toBeInTheDocument();
    expect(screen.getByLabelText('영화, 감독, 배우 검색')).toBeInTheDocument();
  });
});
