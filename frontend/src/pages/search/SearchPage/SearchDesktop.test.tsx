/*
 * 4.1 · 4.2 검색 — 데스크톱 시안 (업무 S15P21E106-784)
 *
 * 넓은 화면에서 결과 카드가 연도 · 장르 · 평점 · OTT까지 말하고, 고른 영화를 우측 보조
 * 패널이 따라오는지를 고정한다. 셸이 그 자리를 내놓아야 페이지가 포털로 보낼 수 있으므로
 * (업무 -782) 화면만이 아니라 셸까지 함께 그린다.
 *
 * **동작은 배치가 바뀌어도 그대로다.** 주소의 검색 조건 · 상세 이동 · 필터가 넓은 화면에서도
 * 같은지 함께 본다 — 이 업무는 배치만 바꾼다.
 *
 * **412px은 한 줄도 바뀌지 않는다.** 그 회귀는 `SearchPage.test.tsx`가 이미 지키고 있고,
 * 여기서는 좁은 폭에서 데스크톱 전용 요소가 새지 않는지만 확인한다.
 *
 * jsdom에는 레이아웃이 없어 열 수 · 겹침 · 줄바꿈은 여기서 잡히지 않는다. 그것은 브라우저
 * 확인 기록(`frontend/verification/S15P21E106-784.md`)에 있다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import SearchPage from './index';
import AppLayout from '../../../layouts/AppLayout';
import { AuthContext } from '../../../contexts/authContext';
import { resetViewport, setViewportWidth } from '../../../test/viewport';
import {
  getFilterOptions,
  getTopRatedMovies,
  getTrendingMovies,
  searchMovies,
} from '../../../api/search';
import { getMovieDetail, getSimilarMovies } from '../../../api/movies';
import { getOtts } from '../../../api/otts';
import { getUnreadNotificationCount } from '../../../api/notifications';
import { getMyProfile } from '../../../api/users';
import type { AuthValue } from '../../../contexts/authContext';
import type { MovieDetail, SimilarMovie } from '../../../types/movie';
import type { FilterOptions, MovieSearchItem } from '../../../types/search';

vi.mock('../../../api/search', async () => {
  const actual = await vi.importActual<typeof import('../../../api/search')>('../../../api/search');

  return {
    ...actual,
    searchMovies: vi.fn(),
    countSearchResults: vi.fn(),
    getFilterOptions: vi.fn(),
    getTopRatedMovies: vi.fn(),
    getTrendingMovies: vi.fn(),
  };
});

vi.mock('../../../api/movies', () => ({
  getMovieDetail: vi.fn(),
  getSimilarMovies: vi.fn(),
}));

vi.mock('../../../api/otts', () => ({
  getOtts: vi.fn(),
}));

vi.mock('../../../api/notifications', () => ({
  getUnreadNotificationCount: vi.fn(),
}));

vi.mock('../../../api/users', () => ({
  getMyProfile: vi.fn(),
}));

const auth: AuthValue = {
  status: 'authenticated',
  user: { userId: 7, nickname: '김난다', onboardingCompleted: true },
  signIn: vi.fn(),
  signInWithSocial: vi.fn(),
  completeEmailSignup: vi.fn(),
  completeSocialSignup: vi.fn(),
  signOut: vi.fn(),
};

function item(movieId: number, title: string, overrides: Partial<MovieSearchItem> = {}) {
  return {
    movieId,
    title,
    originalTitle: null,
    posterUrl: null,
    releaseYear: 2019,
    genres: ['드라마'],
    averageRating: 4.3,
    ottProviders: ['NETFLIX'],
    watched: false,
    ...overrides,
  } satisfies MovieSearchItem;
}

const items = [item(101, '기생충'), item(102, '마더', { releaseYear: 2009, averageRating: 0 })];

const detail: MovieDetail = {
  movieId: 101,
  title: '기생충',
  originalTitle: null,
  synopsis: '전원 백수인 기택의 가족이 고액 과외 자리를 소개받는다',
  releaseDate: '2019-05-30',
  runtimeMinutes: 131,
  genres: [{ code: 'DRAMA', name: '드라마' }],
  countries: [{ code: 'KR', name: '한국' }],
  directors: [{ personId: 1, name: '봉준호', profileUrl: null }],
  cast: [
    { personId: 11, name: '송강호', profileUrl: null },
    { personId: 12, name: '이선균', profileUrl: null },
  ],
  posterUrl: null,
  backdropUrl: null,
  flavorCode: null,
  averageRating: 4.3,
  ratingCount: 12,
  ottProviders: [
    { ottId: 1, code: 'NETFLIX', name: '넷플릭스', watchUrl: null, subscribed: false },
  ],
  myRating: null,
  myWatchStatus: null,
  recommendation: null,
};

const similar: SimilarMovie[] = [
  { movieId: 201, title: '옥자', posterUrl: null, averageRating: 3.6, similarity: 0.8 },
];

const filterOptions: FilterOptions = {
  genres: [{ code: 'DRAMA', name: '드라마' }],
  countries: [{ code: 'KR', name: '한국' }],
  yearRange: { min: 1990, max: 2026 },
  otts: [{ ottId: 1, code: 'NETFLIX', name: '넷플릭스' }],
};

function LocationProbe() {
  const location = useLocation();

  return <div data-testid="location">{location.pathname + location.search}</div>;
}

function renderSearch(width: number, route = '/search?q=봉준호') {
  setViewportWidth(width);

  return render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/search" element={<SearchPage />} />
            <Route path="/movies/:movieId" element={<p>2.4 영화 상세</p>} />
          </Route>
        </Routes>
        <LocationProbe />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

beforeEach(() => {
  vi.mocked(searchMovies)
    .mockReset()
    .mockResolvedValue({ totalCount: 24, items, nextCursor: null, hasNext: false });
  vi.mocked(getFilterOptions).mockReset().mockResolvedValue(filterOptions);
  vi.mocked(getTopRatedMovies).mockReset().mockResolvedValue([]);
  vi.mocked(getTrendingMovies).mockReset().mockResolvedValue(null);
  vi.mocked(getMovieDetail).mockReset().mockResolvedValue(detail);
  vi.mocked(getSimilarMovies).mockReset().mockResolvedValue(similar);
  vi.mocked(getOtts)
    .mockReset()
    .mockResolvedValue([{ ottId: 1, code: 'NETFLIX', name: '넷플릭스', logoUrl: null }]);
  vi.mocked(getUnreadNotificationCount).mockReset().mockResolvedValue(0);
  vi.mocked(getMyProfile).mockReset().mockRejectedValue(new Error('쓰지 않는다'));
});

afterEach(() => {
  resetViewport();
});

describe('4.2 검색 데스크톱 시안', () => {
  it('결과 카드에 개봉연도 · 장르 · 평점 · OTT를 함께 적는다', async () => {
    renderSearch(1440);

    // 제목은 카드와 우측 패널에 함께 서므로 카드 쪽으로 좁힌다
    const card = await screen.findByRole('button', { name: /기생충/ });

    expect(within(card).getByText('2019 · 드라마')).toBeInTheDocument();
    expect(within(card).getByText('4.3')).toBeInTheDocument();
    expect(within(card).getByLabelText('넷플릭스')).toBeInTheDocument();

    // 평가가 없는 영화(`averageRating: 0`)에는 별을 그리지 않는다
    const unrated = screen.getByRole('button', { name: /마더/ });

    expect(within(unrated).queryByText('0.0')).not.toBeInTheDocument();
  });

  it('첫 결과를 고른 것으로 두고 우측 보조 패널이 그 영화를 말한다', async () => {
    renderSearch(1440);

    const aside = await screen.findByRole('complementary', { name: '보조 정보' });

    /*
     * **제목이 섰다고 패널이 다 찬 것은 아니다**(업무 S15P21E106-797).
     *
     * 제목은 검색 결과가 그대로 주지만 줄거리 · 감독 · 출연 · 장르는 상세(API-030)가, 유사한
     * 작품은 API-031이 따로 채운다. 제목만 기다리고 나머지를 바로 읽으면 두 응답 사이가 잡혀
     * 전체 실행에서 간헐적으로 깨진다. 늦게 오는 쪽을 기다린다.
     */
    expect(await within(aside).findByText(detail.synopsis!)).toBeInTheDocument();
    expect(within(aside).getByRole('heading', { name: '기생충' })).toBeInTheDocument();
    expect(within(aside).getByText('봉준호')).toBeInTheDocument();
    expect(within(aside).getByText('송강호')).toBeInTheDocument();
    // 시안의 `연관 키워드` 자리는 계약이 주는 장르 · 제작 국가다
    expect(within(aside).getByRole('heading', { name: '장르 · 제작 국가' })).toBeInTheDocument();
    expect(within(aside).getByText('한국')).toBeInTheDocument();
    expect(await within(aside).findByRole('heading', { name: '유사한 작품' })).toBeInTheDocument();
  });

  it('다른 카드를 고르면 패널이 그 영화로 바뀐다', async () => {
    renderSearch(1440);

    fireEvent.click(await screen.findByRole('button', { name: /마더/ }));

    await waitFor(() => expect(getMovieDetail).toHaveBeenCalledWith(102));

    const aside = screen.getByRole('complementary', { name: '보조 정보' });

    expect(within(aside).getByRole('heading', { name: '마더' })).toBeInTheDocument();
  });

  it('패널의 `영화 상세 보기`가 2.4로 간다', async () => {
    renderSearch(1440);

    const aside = await screen.findByRole('complementary', { name: '보조 정보' });

    fireEvent.click(within(aside).getByRole('link', { name: /영화 상세 보기/ }));

    expect(screen.getByTestId('location')).toHaveTextContent('/movies/101');
  });

  it('`✕`로 패널을 닫으면 자리가 비고 다시 고르면 열린다', async () => {
    renderSearch(1440);

    const aside = await screen.findByRole('complementary', { name: '보조 정보' });

    fireEvent.click(within(aside).getByRole('button', { name: '고른 영화 닫기' }));

    expect(screen.queryByRole('heading', { name: '유사한 작품' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /마더/ }));

    await waitFor(() =>
      expect(screen.getByRole('heading', { name: '유사한 작품' })).toBeInTheDocument(),
    );
  });

  it('걸려 있는 필터를 칩으로 보여주고 칩 하나만 풀 수 있다', async () => {
    renderSearch(1440, '/search?q=봉준호&genre=DRAMA&year=2010S');

    const genreChip = await screen.findByRole('button', { name: '드라마 필터 해제' });

    expect(screen.getByRole('button', { name: '2010년대 필터 해제' })).toBeInTheDocument();
    // 필터 단추에 걸린 개수가 적힌다(시안)
    expect(screen.getByText('2')).toBeInTheDocument();

    fireEvent.click(genreChip);

    await waitFor(() =>
      expect(screen.queryByRole('button', { name: '드라마 필터 해제' })).not.toBeInTheDocument(),
    );
    // 주소의 검색 조건은 그대로 유지된다. 푼 것만 빠진다
    expect(screen.getByTestId('location')).toHaveTextContent('q=%EB%B4%89%EC%A4%80%ED%98%B8');
    expect(screen.getByTestId('location')).toHaveTextContent('year=2010S');
    expect(screen.getByTestId('location')).not.toHaveTextContent('genre=');
  });

  it('`필터 초기화`는 검색어를 남기고 필터만 지운다', async () => {
    renderSearch(1440, '/search?q=봉준호&genre=DRAMA&year=2010S');

    fireEvent.click(await screen.findByRole('button', { name: '필터 초기화' }));

    await waitFor(() =>
      expect(screen.queryByRole('button', { name: '필터 초기화' })).not.toBeInTheDocument(),
    );
    expect(screen.getByTestId('location')).toHaveTextContent('q=%EB%B4%89%EC%A4%80%ED%98%B8');
    expect(screen.getByTestId('location')).not.toHaveTextContent('year=');
  });

  it('우측 패널 자리가 없는 1024px에서는 카드가 곧 상세 링크다', async () => {
    renderSearch(1024);

    expect(await screen.findByRole('link', { name: /기생충/ })).toBeInTheDocument();
    expect(screen.queryByRole('complementary', { name: '보조 정보' })).not.toBeInTheDocument();
    // 고르지 않으므로 상세를 부를 이유도 없다
    expect(getMovieDetail).not.toHaveBeenCalled();
  });

  it('412px에서는 데스크톱 시안의 요소가 나오지 않는다', async () => {
    renderSearch(412, '/search?q=봉준호&genre=DRAMA&year=2010S');

    expect(await screen.findByRole('link', { name: /기생충/ })).toBeInTheDocument();
    // 카드는 포스터와 제목뿐이고 필터 칩 줄도 없다
    expect(screen.queryByText('2019 · 드라마')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '드라마 필터 해제' })).not.toBeInTheDocument();
    expect(screen.queryByRole('complementary', { name: '보조 정보' })).not.toBeInTheDocument();
  });
});
