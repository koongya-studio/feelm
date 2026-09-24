/*
 * 2.5 → 2.6 → 3.1 평가가 취향 필름이 되는 경로 (업무 S15P21E106-633)
 *
 * 고정하는 것은 하나다. **방금 평가한 영화는 기존 필름에 이어 붙은 프레임으로 보이고,
 * `취향 필름 확인`으로 간 3.1에서도 같은 필름의 맨 앞에 있다.** 전에는 2.6이 그 영화의
 * 포스터 한 장만 그려, 3.1로 가기 전까지는 필름이 그 한 장뿐인 것처럼 보였다.
 *
 * 실제 호출인 도메인만 가로챈다. 내 필름(API-042)은 저장 뒤의 서버처럼 새 프레임을 맨 앞에
 * 담아 돌려준다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import MovieRatingPage from './MovieRatingPage';
import RatingResultPage from './RatingResultPage';
import FilmPage from '../film/FilmPage';
import { AuthContext } from '../../contexts/authContext';
import { testAuth } from '../../test/auth';
import { ROUTE_PATTERN } from '../../routes/paths';
import type { MovieDetail } from '../../types/movie';
import type { FrameList, FrameSummary } from '../../types/taste';
import type { Rating } from '../../types/watch';

vi.mock('../../api/movies', () => ({ getMovieDetail: vi.fn() }));
vi.mock('../../api/otts', () => ({ getOtts: vi.fn() }));
vi.mock('../../api/watch', () => ({
  createRating: vi.fn(),
  findWatchedRecord: vi.fn(),
  getRatingCounts: vi.fn(),
  updateRating: vi.fn(),
}));
vi.mock('../../api/taste', () => ({
  getMyFrames: vi.fn(),
  getPopcornBucket: vi.fn(),
  getPopcornFlavors: vi.fn(),
}));
vi.mock('../../api/reports', () => ({ getLatestReadyReport: vi.fn() }));
vi.mock('../../api/notifications', () => ({ getUnreadNotificationCount: vi.fn() }));

const { getMovieDetail } = await import('../../api/movies');
const { getOtts } = await import('../../api/otts');
const { createRating, findWatchedRecord, getRatingCounts } = await import('../../api/watch');
const { getMyFrames, getPopcornBucket, getPopcornFlavors } = await import('../../api/taste');
const { getLatestReadyReport } = await import('../../api/reports');
const { getUnreadNotificationCount } = await import('../../api/notifications');

const movie: MovieDetail = {
  movieId: 550,
  title: '파이트 클럽',
  originalTitle: 'Fight Club',
  synopsis: null,
  releaseDate: '1999-11-12',
  runtimeMinutes: 139,
  genres: [],
  countries: [],
  directors: [],
  cast: [],
  posterUrl: '/p/550.jpg',
  backdropUrl: null,
  flavorCode: 'FLAVOR_A',
  averageRating: 4.3,
  ratingCount: 10,
  ottProviders: [],
  myRating: null,
  myWatchStatus: null,
  recommendation: null,
};

const created: Rating = {
  ratingId: 91,
  movieId: 550,
  score: 4.5,
  comment: null,
  visibility: 'PRIVATE',
  spoiler: false,
  frame: { frameId: 77, createdAt: '2026-09-17T13:59:03Z' },
  popcorn: { flavorCode: 'FLAVOR_A', isNewFlavor: false, flavorCount: 3 },
  watchStatus: 'RATED',
  createdAt: '2026-09-17T13:59:03Z',
};

function frame(frameId: number, movieId: number, title: string, createdAt: string): FrameSummary {
  return {
    frameId,
    movie: { movieId, title, posterUrl: `/p/${movieId}.jpg` },
    score: 4,
    flavorCode: 'FLAVOR_A',
    createdAt,
  };
}

/** 저장 뒤의 내 필름. 새 프레임이 맨 앞이다 */
const film: FrameList = {
  totalCount: 3,
  items: [
    frame(77, 550, '파이트 클럽', '2026-09-17T13:59:03Z'),
    frame(60, 680, '펄프 픽션', '2026-09-10T00:00:00Z'),
    frame(41, 13, '포레스트 검프', '2026-09-01T00:00:00Z'),
  ],
  nextCursor: null,
  hasNext: false,
};

beforeEach(() => {
  vi.mocked(getMovieDetail).mockReset().mockResolvedValue(movie);
  vi.mocked(getOtts).mockReset().mockResolvedValue([]);
  vi.mocked(findWatchedRecord).mockReset().mockResolvedValue(null);
  vi.mocked(getRatingCounts)
    .mockReset()
    .mockResolvedValueOnce({ frameCount: 2, popcornCount: 11, weeklyFrameDelta: 3 })
    .mockResolvedValue({ frameCount: 3, popcornCount: 12, weeklyFrameDelta: 3 });
  vi.mocked(createRating).mockReset().mockResolvedValue(created);
  vi.mocked(getMyFrames).mockReset().mockResolvedValue(film);
  vi.mocked(getPopcornBucket).mockReset().mockResolvedValue({ totalCount: 12, flavors: [] });
  vi.mocked(getPopcornFlavors).mockReset().mockResolvedValue([]);
  vi.mocked(getLatestReadyReport).mockReset().mockResolvedValue(null);
  vi.mocked(getUnreadNotificationCount).mockReset().mockResolvedValue(0);
});

/** 릴에 그려진 포스터 주소를 앞에서부터 읽는다 */
function reelPosters() {
  return Array.from(screen.getByTestId('film-reel').querySelectorAll('img')).map((img) =>
    img.getAttribute('src'),
  );
}

describe('2.5 → 2.6 → 3.1', () => {
  it('평가한 영화가 기존 필름에 이어 붙고 3.1에서도 필름의 맨 앞에 있다', async () => {
    render(
      <AuthContext.Provider value={testAuth}>
        <MemoryRouter initialEntries={['/movies/550/rate']}>
          <Routes>
            <Route path={ROUTE_PATTERN.movieRating} element={<MovieRatingPage />} />
            <Route path={ROUTE_PATTERN.ratingResult} element={<RatingResultPage />} />
            <Route path={ROUTE_PATTERN.film} element={<FilmPage />} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>,
    );

    // 2.5
    const slider = await screen.findByRole('slider', { name: '별점' });
    fireEvent.change(slider, { target: { value: '4.5' } });
    fireEvent.click(screen.getByRole('button', { name: '평가 완료' }));

    // 2.6 — 새 프레임 아래에 기존 프레임이 최신순으로 잇는다. 새 프레임은 한 번만이다
    await waitFor(() => expect(reelPosters()).toEqual(['/p/550.jpg', '/p/680.jpg', '/p/13.jpg']));
    expect(screen.getByText(/영화 프레임이 취향 필름에 추가됩니다/)).toBeInTheDocument();

    // 2.7
    fireEvent.click(
      await screen.findByRole('button', { name: '취향 필름 확인' }, { timeout: 3000 }),
    );

    // 3.1 — 같은 필름이고, 방금 평가한 영화가 맨 앞이다
    const reel = within(await screen.findByTestId('film-reel'));
    const frames = await reel.findAllByRole('button', { name: /상세 보기$/ });
    expect(frames[0]).toHaveAccessibleName(/파이트 클럽/);
    expect(frames).toHaveLength(3);
  });
});
