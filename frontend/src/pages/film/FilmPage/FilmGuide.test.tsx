import { beforeEach, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import FilmPage from './index';
import { renderWithRouter } from '../../../test/render';
import { getMyFrames, getPopcornBucket } from '../../../api/taste';
import { getLatestReadyReport } from '../../../api/reports';
import { getUnreadNotificationCount } from '../../../api/notifications';
import { FILM_GUIDE, hasSeenGuide, readGuideProgress } from '../../../utils/userGuides';
import guideStyles from '../../../components/GuideCallout/GuideCallout.module.css';

vi.mock('../../../contexts/authContext', () => ({
  useAuth: () => ({ user: { userId: 891 } }),
}));
vi.mock('../../../api/taste', () => ({
  getMyFrames: vi.fn(),
  getPopcornBucket: vi.fn(),
}));
vi.mock('../../../api/reports', () => ({ getLatestReadyReport: vi.fn() }));
vi.mock('../../../api/notifications', () => ({ getUnreadNotificationCount: vi.fn() }));

beforeEach(() => {
  localStorage.clear();
  vi.mocked(getMyFrames).mockResolvedValue({
    totalCount: 1,
    items: [
      {
        frameId: 1,
        movie: { movieId: 100, title: '테스트 영화', posterUrl: null },
        score: 4,
        flavorCode: 'FLAVOR_A',
        createdAt: '2026-09-01T00:00:00Z',
      },
    ],
    nextCursor: null,
    hasNext: false,
  });
  vi.mocked(getPopcornBucket).mockResolvedValue({
    totalCount: 1,
    flavors: [
      {
        flavorCode: 'FLAVOR_A',
        displayName: '오리지널 솔트',
        color: '#fff',
        popcornCount: 1,
        averageScore: 4,
        discoveredAt: null,
      },
    ],
  });
  vi.mocked(getLatestReadyReport).mockResolvedValue(null);
  vi.mocked(getUnreadNotificationCount).mockResolvedValue(0);
});

it('평가 버튼으로 검색에 다녀와도 필름 가이드의 다음 단계로 이어진다', async () => {
  const firstVisit = renderWithRouter(<FilmPage />, { route: '/film' });

  expect(await screen.findByRole('status', { name: '앱 사용 안내 1/6 단계' })).toBeInTheDocument();
  for (let step = 2; step <= 5; step += 1) {
    fireEvent.click(screen.getByRole('button', { name: '다음' }));
    expect(screen.getByRole('status', { name: `앱 사용 안내 ${step}/6 단계` })).toBeInTheDocument();
  }

  fireEvent.click(screen.getByRole('button', { name: '본 영화 평가하기' }));
  expect(readGuideProgress(891, FILM_GUIDE)).toBe('recommendations');
  firstVisit.unmount();

  renderWithRouter(<FilmPage />, { route: '/film' });
  expect(await screen.findByRole('status', { name: '앱 사용 안내 6/6 단계' })).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '둘러보기' }));
  expect(hasSeenGuide(891, FILM_GUIDE)).toBe(true);
  expect(readGuideProgress(891, FILM_GUIDE)).toBeNull();
});

it('모바일 팝콘과 리포트 안내는 강조 대상 위쪽에 놓인다', async () => {
  renderWithRouter(<FilmPage />, { route: '/film' });

  const firstStep = await screen.findByRole('status', { name: '앱 사용 안내 1/6 단계' });
  expect(firstStep).not.toHaveClass(guideStyles.top);

  fireEvent.click(screen.getByRole('button', { name: '다음' }));
  expect(screen.getByRole('status', { name: '앱 사용 안내 2/6 단계' })).toHaveClass(
    guideStyles.top,
  );

  fireEvent.click(screen.getByRole('button', { name: '다음' }));
  expect(screen.getByRole('status', { name: '앱 사용 안내 3/6 단계' })).toHaveClass(
    guideStyles.top,
  );
});
