/*
 * 주소로 들어오는 검색 (업무 S15P21E106-782)
 *
 * 데스크톱 상단 바의 통합 검색은 검색어를 주소에 싣고 4.1로 보낸다. 다른 화면에서 들어오는
 * 경우는 화면이 새로 그려지므로 시작 조건을 읽는 자리가 처리하지만, **이미 4.1에 있는 채로
 * 주소만 바뀌는 경우**는 그 자리가 다시 돌지 않아 주소만 바뀌고 결과는 그대로였다.
 *
 * 두 경우를 함께 고정한다. 한쪽만 두면 고치는 사람이 다른 쪽을 깬다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import SearchPage from './index';
import DesktopTopBar from '../../../components/DesktopTopBar';

vi.mock('../../../api/search', () => ({
  getTopRatedMovies: vi.fn(),
  getTrendingMovies: vi.fn(),
  searchMovies: vi.fn(),
  countSearchResults: vi.fn(),
  getFilterOptions: vi.fn(),
}));

vi.mock('../../../api/notifications', () => ({
  getUnreadNotificationCount: vi.fn(),
}));

const search = await import('../../../api/search');
const { getUnreadNotificationCount } = await import('../../../api/notifications');

beforeEach(() => {
  vi.mocked(getUnreadNotificationCount).mockReset().mockResolvedValue(0);
  vi.mocked(search.getTopRatedMovies).mockReset().mockResolvedValue([]);
  vi.mocked(search.getTrendingMovies).mockReset().mockResolvedValue(null);
  vi.mocked(search.countSearchResults).mockReset().mockResolvedValue(0);
  vi.mocked(search.getFilterOptions).mockReset().mockRejectedValue(new Error('옵션 없음'));
  vi.mocked(search.searchMovies)
    .mockReset()
    .mockResolvedValue({
      items: [],
      hasNext: false,
      nextCursor: null,
      totalCount: 0,
    } as never);
});

/** 상단 바와 화면을 함께 세운다. 상단 바는 셸에 있어 화면이 바뀌어도 그대로 남는다 */
function renderShell(route: string) {
  return render(
    <MemoryRouter initialEntries={[route]}>
      <DesktopTopBar account={null} />
      <Routes>
        <Route path="/film" element={<p>필름 화면</p>} />
        <Route path="/search" element={<SearchPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

/** 상단 바는 첫 번째 검색 form이다. 4.1도 자기 검색 form을 갖는다 */
function submitTopBar(keyword: string) {
  fireEvent.change(screen.getByLabelText('영화, 감독, 배우 검색'), { target: { value: keyword } });
  fireEvent.submit(screen.getAllByRole('search')[0]);
}

describe('상단 바에서 보낸 검색어', () => {
  it('다른 화면에서 들어오면 그 검색어로 검색한다', async () => {
    renderShell('/film');

    submitTopBar('봉준호');

    await waitFor(() => expect(search.searchMovies).toHaveBeenCalled());
    expect(vi.mocked(search.searchMovies).mock.calls[0][0]).toMatchObject({ q: '봉준호' });
  });

  it('이미 검색 화면에 있어도 그 검색어로 다시 검색한다', async () => {
    renderShell('/search');

    await waitFor(() => expect(search.getTopRatedMovies).toHaveBeenCalled());
    expect(search.searchMovies).not.toHaveBeenCalled();

    submitTopBar('봉준호');

    await waitFor(() => expect(search.searchMovies).toHaveBeenCalled());
    expect(vi.mocked(search.searchMovies).mock.calls[0][0]).toMatchObject({ q: '봉준호' });
  });

  it('바뀐 검색어가 화면의 입력에도 들어간다', async () => {
    renderShell('/search');

    await waitFor(() => expect(search.getTopRatedMovies).toHaveBeenCalled());

    submitTopBar('봉준호');

    await waitFor(() => expect(screen.getByLabelText('영화 검색')).toHaveValue('봉준호'));
  });
});
