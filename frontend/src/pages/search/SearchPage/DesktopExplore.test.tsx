import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import AppLayout from '../../../layouts/AppLayout';
import SearchPage from './index';
import { AuthContext } from '../../../contexts/authContext';
import { resetViewport, setViewportWidth } from '../../../test/viewport';
import type { AuthValue } from '../../../contexts/authContext';

vi.mock('../../../api/search', () => ({
  getTopRatedMovies: vi.fn(),
  getTrendingMovies: vi.fn(),
  searchMovies: vi.fn(),
  countSearchResults: vi.fn(),
  getFilterOptions: vi.fn(),
}));
vi.mock('../../../api/notifications', () => ({ getUnreadNotificationCount: vi.fn() }));
vi.mock('../../../api/users', () => ({ getMyProfile: vi.fn() }));
vi.mock('../../../api/otts', () => ({ getOtts: vi.fn() }));

const search = await import('../../../api/search');
const { getUnreadNotificationCount } = await import('../../../api/notifications');
const { getMyProfile } = await import('../../../api/users');
const { getOtts } = await import('../../../api/otts');

const auth: AuthValue = {
  status: 'authenticated',
  user: { userId: 7, nickname: '테스터', onboardingCompleted: true },
  signIn: vi.fn(),
  signInWithSocial: vi.fn(),
  completeEmailSignup: vi.fn(),
  completeSocialSignup: vi.fn(),
  signOut: vi.fn(),
};

function Address() {
  const location = useLocation();
  return <output data-testid="address">{location.pathname + location.search}</output>;
}

beforeEach(() => {
  setViewportWidth(1100);
  vi.mocked(getUnreadNotificationCount).mockReset().mockResolvedValue(0);
  vi.mocked(getMyProfile).mockReset().mockRejectedValue(new Error('사용 안 함'));
  vi.mocked(getOtts).mockReset().mockResolvedValue([]);
  vi.mocked(search.getTopRatedMovies).mockReset().mockResolvedValue([]);
  vi.mocked(search.getTrendingMovies).mockReset().mockResolvedValue(null);
  vi.mocked(search.getFilterOptions)
    .mockReset()
    .mockResolvedValue({
      genres: [],
      countries: [],
      yearRange: { min: 1920, max: 2026 },
      otts: [],
    });
  vi.mocked(search.countSearchResults).mockReset().mockResolvedValue(0);
  vi.mocked(search.searchMovies)
    .mockReset()
    .mockResolvedValue({
      totalCount: 1,
      items: [
        {
          movieId: 1,
          title: '달의 뒷면',
          originalTitle: '달의 뒷면',
          posterUrl: null,
          releaseYear: 2020,
          genres: ['드라마'],
          averageRating: 4,
          ottProviders: [],
          watched: false,
        },
      ],
      nextCursor: null,
      hasNext: false,
    });
});

afterEach(() => {
  cleanup();
  resetViewport();
});

it('데스크톱 탐색 화면은 상단 검색 하나로 입력·결과·초기화를 처리하고 필터를 유지한다', async () => {
  render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={['/search']} useTransitions={false}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/search" element={<SearchPage />} />
          </Route>
        </Routes>
        <Address />
      </MemoryRouter>
    </AuthContext.Provider>,
  );

  const input = screen.getByRole('searchbox', { name: '영화, 감독, 배우 검색' });
  expect(screen.getAllByRole('searchbox')).toHaveLength(1);
  expect(screen.getByRole('heading', { name: '영화 탐색' })).toBeInTheDocument();
  expect(await screen.findByRole('button', { name: '필터' })).toBeEnabled();

  fireEvent.change(input, { target: { value: '달의' } });
  await waitFor(() =>
    expect(search.searchMovies).toHaveBeenCalledWith(expect.objectContaining({ q: '달의' })),
  );
  expect(await screen.findByRole('heading', { name: '검색 결과 1' })).toBeInTheDocument();
  expect(input).toHaveValue('달의');
  expect(screen.getByTestId('address')).toHaveTextContent('/search?q=%EB%8B%AC%EC%9D%98');
  expect(screen.getByRole('button', { name: '필터' })).toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: '검색어 지우기' }));
  await waitFor(() => expect(screen.getByTestId('address').textContent).toBe('/search'));
  expect(input).toHaveValue('');
  expect(screen.getByText('영화 제목, 감독, 배우 이름으로 검색해 보세요')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '필터' })).toBeInTheDocument();
});
