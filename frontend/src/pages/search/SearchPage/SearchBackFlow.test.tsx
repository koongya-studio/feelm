/*
 * 4.1 → 4.2 → 2.4 → 뒤로 (업무 S15P21E106-634)
 *
 * 상세에서 뒤로 오면 검색 결과를 건너뛰고 빈 검색 화면(4.1)으로 떨어졌다. 검색 조건이
 * 화면 상태에만 있어 검색 화면이 새로 그려지며 모두 사라졌기 때문이다. 화면 하나만 열어
 * 보면 이 문제는 보이지 않으므로 **검색 → 상세 → 뒤로를 한 라우터에서 걷는다**.
 *
 * 고정하는 것
 * - 조건은 주소에 남고, 타이핑해도 기록이 쌓이지 않는다(바꿔 쓰기)
 * - 뒤로 오면 같은 결과가 다시 부르지 않고 되살아난다 — `더 보기`로 받은 뒷장까지
 * - 주소로 바로 들어오면 그 조건으로 검색한다
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router';
import SearchPage from './index';
import { resetSessionStore } from '../../../mocks/sessionStore';
import type { MovieSearchItem, MovieSearchResult } from '../../../types/search';

vi.mock('../../../api/search', () => ({
  searchMovies: vi.fn(),
  countSearchResults: vi.fn(),
  getFilterOptions: vi.fn(),
}));

const { searchMovies, countSearchResults, getFilterOptions } = await import('../../../api/search');

function item(movieId: number, title: string): MovieSearchItem {
  return {
    movieId,
    title,
    originalTitle: title,
    posterUrl: null,
    releaseYear: 2020,
    genres: ['드라마'],
    averageRating: 4,
    ottProviders: [],
    watched: false,
  };
}

function page(items: MovieSearchItem[], nextCursor: string | null = null): MovieSearchResult {
  return {
    totalCount: items.length + (nextCursor ? 1 : 0),
    items,
    nextCursor,
    hasNext: !!nextCursor,
  };
}

/** 2.4 자리. 뒤로 가기 버튼 하나다 */
function DetailStub() {
  const navigate = useNavigate();

  return (
    <button type="button" onClick={() => void navigate(-1)}>
      상세에서 뒤로
    </button>
  );
}

/** 브라우저의 뒤로 가기. 어느 화면에서든 한 칸 뒤로 간다 */
function BrowserBack() {
  const navigate = useNavigate();

  return (
    <button type="button" onClick={() => void navigate(-1)}>
      브라우저 뒤로
    </button>
  );
}

function LocationProbe() {
  const location = useLocation();

  return <div data-testid="location">{location.pathname + location.search}</div>;
}

function renderFlow(entries: string[] = ['/film', '/search']) {
  return render(
    <MemoryRouter initialEntries={entries} initialIndex={entries.length - 1}>
      <Routes>
        <Route path="/search" element={<SearchPage />} />
        <Route path="/movies/:movieId" element={<DetailStub />} />
        <Route path="/film" element={<p>필름</p>} />
      </Routes>
      <BrowserBack />
      <LocationProbe />
    </MemoryRouter>,
  );
}

const location = () => screen.getByTestId('location').textContent;

beforeEach(() => {
  vi.clearAllMocks();
  resetSessionStore();
  window.sessionStorage.clear();

  vi.mocked(getFilterOptions).mockResolvedValue({
    genres: [{ code: 'DRAMA', name: '드라마' }],
    countries: [],
    yearRange: { min: 1920, max: 2026 },
    otts: [],
  });
  vi.mocked(countSearchResults).mockResolvedValue(0);
});

afterEach(() => {
  window.sessionStorage.clear();
});

function submit(keyword: string) {
  const input = screen.getByRole('searchbox', { name: '영화 검색' });

  fireEvent.change(input, { target: { value: keyword } });
  fireEvent.submit(input.closest('form') as HTMLFormElement);
}

describe('4.2 → 2.4 → 뒤로', () => {
  it('검색 조건이 주소에 남는다', async () => {
    vi.mocked(searchMovies).mockResolvedValue(page([item(1, '달의 뒷면')]));
    renderFlow();

    submit('달');

    await screen.findByText('달의 뒷면');
    expect(location()).toBe('/search?q=%EB%8B%AC');
  });

  it('뒤로 오면 같은 결과가 다시 부르지 않고 그대로 보인다 — 더 보기로 받은 뒷장까지', async () => {
    vi.mocked(searchMovies)
      .mockResolvedValueOnce(page([item(1, '달의 뒷면')], 'cursor-2'))
      .mockResolvedValueOnce(page([item(2, '달빛 정원')]));
    renderFlow();

    submit('달');
    await screen.findByText('달의 뒷면');
    // jsdom에는 스크롤 감시가 없어 버튼으로 다음 장을 받는다
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    await screen.findByText('달빛 정원');

    fireEvent.click(screen.getByRole('link', { name: '달빛 정원' }));
    expect(location()).toBe('/movies/2');

    fireEvent.click(screen.getByRole('button', { name: '상세에서 뒤로' }));

    expect(await screen.findByText('달빛 정원')).toBeInTheDocument();
    expect(screen.getByText('달의 뒷면')).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: '영화 검색' })).toHaveValue('달');
    expect(location()).toBe('/search?q=%EB%8B%AC');
    // 첫 장과 뒷장, 두 번뿐이다. 돌아와서 다시 부르지 않았다
    expect(searchMovies).toHaveBeenCalledTimes(2);
  });

  it('검색어를 고쳐도 기록이 쌓이지 않는다 — 뒤로 가면 검색 전 화면으로 간다', async () => {
    vi.mocked(searchMovies).mockResolvedValue(page([item(1, '달의 뒷면')]));
    renderFlow();

    submit('달');
    await screen.findByText('달의 뒷면');
    submit('달의');
    await waitFor(() => expect(location()).toBe('/search?q=%EB%8B%AC%EC%9D%98'));

    // 필름(3.1)에서 들어왔으므로 한 번 뒤로 가면 필름이다. 검색어마다 기록이 쌓였다면
    // `?q=달`로 돌아간다
    fireEvent.click(screen.getByRole('button', { name: '브라우저 뒤로' }));
    await waitFor(() => expect(location()).toBe('/film'));
  });
});

describe('주소로 바로 들어오기', () => {
  it('주소의 조건으로 곧바로 검색한다', async () => {
    vi.mocked(searchMovies).mockResolvedValue(page([item(1, '달의 뒷면')]));

    renderFlow(['/search?q=%EB%8B%AC&genre=DRAMA&sort=RATING']);

    expect(await screen.findByText('달의 뒷면')).toBeInTheDocument();
    expect(searchMovies).toHaveBeenCalledWith(
      expect.objectContaining({ q: '달', genre: ['DRAMA'], sort: 'RATING' }),
    );
    expect(screen.getByRole('searchbox', { name: '영화 검색' })).toHaveValue('달');
    expect(screen.getByRole('combobox', { name: '정렬' })).toHaveValue('RATING');
  });

  it('모르는 값은 버리고 기본값으로 검색한다', async () => {
    vi.mocked(searchMovies).mockResolvedValue(page([item(1, '달의 뒷면')]));

    renderFlow(['/search?q=%EB%8B%AC&sort=WRONG&year=1990S&ott=abc']);

    await screen.findByText('달의 뒷면');
    expect(searchMovies).toHaveBeenCalledWith({ q: '달', sort: 'RELEVANCE' });
  });

  it('`그 이전` 연도는 필터 옵션을 받은 뒤 시작 연도를 실어 검색한다', async () => {
    // 먼저 보내면 시작 연도(API-029 yearRange.min)를 몰라 연도 조건이 빠졌다(!405 리뷰)
    vi.mocked(searchMovies).mockResolvedValue(page([item(1, '오래된 영화')]));

    renderFlow(['/search?year=BEFORE']);

    await screen.findByText('오래된 영화');
    expect(searchMovies).toHaveBeenCalledTimes(1);
    expect(searchMovies).toHaveBeenCalledWith({ yearFrom: 1920, yearTo: 2009, sort: 'RELEVANCE' });
  });

  it('필터가 남은 채 X를 누르면 필터만으로 다시 찾고 주소도 같다', async () => {
    vi.mocked(searchMovies).mockResolvedValue(page([item(1, '달의 뒷면')]));
    renderFlow(['/search?q=%EB%8B%AC&genre=DRAMA']);
    await screen.findByText('달의 뒷면');

    vi.mocked(searchMovies).mockResolvedValue(page([item(3, '드라마 영화')]));
    fireEvent.click(screen.getByRole('button', { name: '검색어 지우기' }));

    expect(await screen.findByText('드라마 영화')).toBeInTheDocument();
    expect(searchMovies).toHaveBeenLastCalledWith({ genre: ['DRAMA'], sort: 'RELEVANCE' });
    expect(location()).toBe('/search?genre=DRAMA');
  });

  it('X를 누르면 주소의 검색어도 지워진다', async () => {
    vi.mocked(searchMovies).mockResolvedValue(page([item(1, '달의 뒷면')]));
    renderFlow(['/search?q=%EB%8B%AC']);
    await screen.findByText('달의 뒷면');

    fireEvent.click(screen.getByRole('button', { name: '검색어 지우기' }));

    expect(location()).toBe('/search');
  });
});
