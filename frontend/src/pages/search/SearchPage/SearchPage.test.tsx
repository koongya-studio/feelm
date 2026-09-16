/*
 * 4.1 · 4.2 · 4.4 — 한 화면의 세 상태 전환 (업무 S15P21E106-303 · -476)
 *
 * 이 화면은 세 프레임을 상태로 나눠 갖는다. 어느 상태를 보여줄지는 결과가 `null`인지
 * 빈 `items`인지로 갈리는데, 그 둘을 헷갈리면 **검색하지 않은 화면과 결과가 없는 화면이
 * 뒤바뀐다.** 눈으로는 둘 다 "목록이 없는 화면"이라 구분되지 않는다.
 *
 * 급상승과 검색은 실패를 다르게 다룬다. 검색이 실패하면 화면을 채우는 안내가 되고,
 * 급상승이 실패하면 그 자리만 인라인으로 빈다. 그 비대칭이 의도된 것임을 고정한다.
 *
 * 실제 API로 바뀌면서(-476) 커서 페이지가 생겼다. **건수는 받아 온 `items`가 아니라
 * `totalCount`**이고, `더 보기`가 실패해도 이미 그린 목록은 남아야 한다.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import SearchPage from './index';
import { renderWithRouter } from '../../../test/render';
import { resetSessionStore } from '../../../mocks/sessionStore';
import type { MovieSearchItem, MovieSearchResult } from '../../../types/search';
import type { TrendingList } from '../../../types/trend';

vi.mock('../../../api/search', () => ({
  searchMovies: vi.fn(),
  countSearchResults: vi.fn(),
  getFilterOptions: vi.fn(),
  getTrendingMovies: vi.fn(),
}));

vi.mock('../../../api/otts', () => ({
  getOtts: vi.fn(),
}));

const { searchMovies, countSearchResults, getFilterOptions, getTrendingMovies } =
  await import('../../../api/search');
const { getOtts } = await import('../../../api/otts');

/** API-028 한 장. `totalCount`는 커서 이전 전체 건수라 `items`보다 클 수 있다 */
function page(
  items: MovieSearchItem[],
  overrides: Partial<MovieSearchResult> = {},
): MovieSearchResult {
  return { totalCount: items.length, items, nextCursor: null, hasNext: false, ...overrides };
}

function item(movieId: number, title: string): MovieSearchItem {
  return {
    movieId,
    title,
    originalTitle: title,
    posterUrl: null,
    releaseYear: 2020,
    genres: ['드라마'],
    averageRating: 4,
    ottProviders: ['NETFLIX'],
    watched: false,
  };
}

const trending: TrendingList = {
  period: { from: '2026-09-03T00:00:00Z', to: '2026-09-10T00:00:00Z' },
  updatedAt: '2026-09-10T00:00:00Z',
  items: [
    {
      rank: 1,
      rankDelta: 2,
      movie: { movieId: 205, title: '웡카와 초콜릿', posterUrl: null },
      ratingCount: 214,
      averageScore: 4.3,
      trendScore: 0.9,
    },
  ],
};

const filterOptions = {
  genres: [{ code: 'DRAMA', name: '드라마' }],
  countries: [{ code: 'KR', name: '한국' }],
  yearRange: { min: 1920, max: 2026 },
  otts: [{ ottId: 1, code: 'NETFLIX', name: '넷플릭스' }],
};

beforeEach(() => {
  // 호출 기록까지 지운다. `mockResolvedValue`만으로는 이전 시험의 호출이 남는다
  vi.clearAllMocks();
  // 최근 검색어가 세션 저장소에 남아 시험끼리 영향을 준다
  resetSessionStore();

  vi.mocked(getOtts).mockResolvedValue([
    { ottId: 1, code: 'NETFLIX', name: '넷플릭스', logoUrl: null },
  ]);
  vi.mocked(getFilterOptions).mockResolvedValue(filterOptions);
  vi.mocked(getTrendingMovies).mockResolvedValue(trending);
  vi.mocked(searchMovies).mockResolvedValue(page([]));
  vi.mocked(countSearchResults).mockResolvedValue(0);
});

/** 검색어를 넣고 제출한다 */
function search(keyword: string) {
  const input = screen.getByRole('searchbox', { name: '영화 검색' });

  fireEvent.change(input, { target: { value: keyword } });
  fireEvent.submit(input.closest('form') as HTMLFormElement);
}

describe('4.1 → 4.2 → 4.4 상태 전환', () => {
  it('검색 전에는 급상승을 보여준다 (4.1)', async () => {
    renderWithRouter(<SearchPage />, { route: '/search' });

    expect(await screen.findByText('웡카와 초콜릿')).toBeInTheDocument();
    expect(screen.getByText('검색어를 입력하면 결과가 나와요')).toBeInTheDocument();
  });

  it('결과가 있으면 목록을 보여준다 (4.2)', async () => {
    vi.mocked(searchMovies).mockResolvedValue(
      page([item(201, '나우 유 씨 미'), item(202, '나우 유 씨 미 2')]),
    );

    renderWithRouter(<SearchPage />, { route: '/search' });
    await screen.findByText('웡카와 초콜릿');

    search('나우 유');

    expect(await screen.findByText('검색 결과 2건')).toBeInTheDocument();
    // 급상승은 물러난다. 두 목록이 함께 보이면 안 된다
    expect(screen.queryByText('웡카와 초콜릿')).not.toBeInTheDocument();
  });

  it('결과가 없으면 안내로 바꾼다 (4.4)', async () => {
    renderWithRouter(<SearchPage />, { route: '/search' });
    await screen.findByText('웡카와 초콜릿');

    search('인터스텔라22');

    expect(await screen.findByText('검색 결과가 없어요')).toBeInTheDocument();
  });

  it('검색어 초기화를 누르면 급상승으로 돌아간다 (4.4 ⇢ 4.1)', async () => {
    renderWithRouter(<SearchPage />, { route: '/search' });
    await screen.findByText('웡카와 초콜릿');

    search('인터스텔라22');
    await screen.findByText('검색 결과가 없어요');

    fireEvent.click(screen.getByRole('button', { name: '검색어 초기화' }));

    expect(await screen.findByText('웡카와 초콜릿')).toBeInTheDocument();
    expect(screen.queryByText('검색 결과가 없어요')).not.toBeInTheDocument();
  });
});

describe('실패를 다르게 다룬다', () => {
  it('검색이 실패하면 화면을 채우는 안내와 다시 시도가 나온다', async () => {
    vi.mocked(searchMovies).mockRejectedValue(new Error('실패'));

    renderWithRouter(<SearchPage />, { route: '/search' });
    await screen.findByText('웡카와 초콜릿');

    search('나우 유');

    expect(await screen.findByText('검색하지 못했어요')).toBeInTheDocument();

    // 다시 시도하면 같은 조건으로 한 번 더 조회한다
    vi.mocked(searchMovies).mockResolvedValue(page([item(201, '나우 유 씨 미')]));
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));

    expect(await screen.findByText('검색 결과 1건')).toBeInTheDocument();
  });

  it('급상승이 실패해도 검색은 그대로 쓸 수 있다', async () => {
    vi.mocked(getTrendingMovies).mockRejectedValue(new Error('실패'));

    renderWithRouter(<SearchPage />, { route: '/search' });

    expect(await screen.findByText('급상승 영화를 불러오지 못했어요')).toBeInTheDocument();
    // 검색 입력은 살아 있다
    expect(screen.getByRole('searchbox', { name: '영화 검색' })).toBeEnabled();
  });

  it('필터 옵션을 못 받으면 필터 버튼만 잠긴다', async () => {
    vi.mocked(getFilterOptions).mockRejectedValue(new Error('실패'));

    renderWithRouter(<SearchPage />, { route: '/search' });
    await screen.findByText('웡카와 초콜릿');

    expect(screen.getByRole('button', { name: '필터' })).toBeDisabled();
  });
});

describe('4.3 필터 시트', () => {
  it('닫으면 고른 조건으로 다시 검색한다 (4.3 → 4.2)', async () => {
    renderWithRouter(<SearchPage />, { route: '/search' });
    await screen.findByText('웡카와 초콜릿');

    fireEvent.click(screen.getByRole('button', { name: '필터' }));
    await screen.findByRole('dialog', { name: '검색 필터' });

    fireEvent.click(screen.getByRole('button', { name: '드라마' }));

    vi.mocked(searchMovies).mockResolvedValue(page([item(208, '여름의 끝')]));
    fireEvent.click(screen.getByRole('button', { name: /결과 보기/ }));

    expect(await screen.findByText('검색 결과 1건')).toBeInTheDocument();

    // 검색어 없이 필터만으로도 질의가 나간다
    await waitFor(() =>
      expect(vi.mocked(searchMovies)).toHaveBeenCalledWith(
        expect.objectContaining({ genre: ['DRAMA'] }),
      ),
    );
  });

  it('아무 조건도 없으면 개수를 세지 않는다', async () => {
    renderWithRouter(<SearchPage />, { route: '/search' });
    await screen.findByText('웡카와 초콜릿');

    fireEvent.click(screen.getByRole('button', { name: '필터' }));

    // 검색어도 필터도 없으면 계약이 400을 주므로 애초에 보내지 않는다
    expect(await screen.findByRole('button', { name: '결과 보기' })).toBeInTheDocument();
    expect(vi.mocked(countSearchResults)).not.toHaveBeenCalled();
  });
});

describe('커서 페이지', () => {
  it('건수는 받아 온 목록이 아니라 totalCount다', async () => {
    vi.mocked(searchMovies).mockResolvedValue(
      page([item(201, '나우 유 씨 미')], { totalCount: 24, nextCursor: 'cursor-2', hasNext: true }),
    );

    renderWithRouter(<SearchPage />, { route: '/search' });
    await screen.findByText('웡카와 초콜릿');

    search('나우 유');

    expect(await screen.findByText('검색 결과 24건')).toBeInTheDocument();
  });

  it('더 보기는 같은 조건에 커서를 실어 다음 장을 이어 붙인다', async () => {
    vi.mocked(searchMovies).mockResolvedValue(
      page([item(201, '나우 유 씨 미')], { totalCount: 2, nextCursor: 'cursor-2', hasNext: true }),
    );

    renderWithRouter(<SearchPage />, { route: '/search' });
    await screen.findByText('웡카와 초콜릿');

    search('나우 유');
    await screen.findByText('나우 유 씨 미');

    vi.mocked(searchMovies).mockResolvedValue(
      page([item(202, '나우 유 씨 미 2')], { totalCount: 2 }),
    );
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));

    expect(await screen.findByText('나우 유 씨 미 2')).toBeInTheDocument();
    // 앞 장은 그대로 남는다
    expect(screen.getByText('나우 유 씨 미')).toBeInTheDocument();
    expect(vi.mocked(searchMovies)).toHaveBeenLastCalledWith(
      expect.objectContaining({ q: '나우 유' }),
      'cursor-2',
    );
    // 다음 장이 없으면 버튼도 사라진다
    expect(screen.queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument();
  });

  it('더 보기가 실패해도 이미 그린 목록은 남는다', async () => {
    vi.mocked(searchMovies).mockResolvedValue(
      page([item(201, '나우 유 씨 미')], { totalCount: 2, nextCursor: 'cursor-2', hasNext: true }),
    );

    renderWithRouter(<SearchPage />, { route: '/search' });
    await screen.findByText('웡카와 초콜릿');

    search('나우 유');
    await screen.findByText('나우 유 씨 미');

    vi.mocked(searchMovies).mockRejectedValue(new Error('실패'));
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));

    expect(await screen.findByText('다음 결과를 불러오지 못했어요.')).toBeInTheDocument();
    expect(screen.getByText('나우 유 씨 미')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
  });
});

describe('최근 검색어', () => {
  it('검색하면 최근 검색어에 앞으로 들어간다', async () => {
    renderWithRouter(<SearchPage />, { route: '/search' });
    await screen.findByText('웡카와 초콜릿');

    search('나우 유');

    const recent = await screen.findAllByRole('button', { name: '나우 유' });
    expect(recent.length).toBeGreaterThan(0);
  });

  it('전체 삭제하면 목록이 사라진다', async () => {
    renderWithRouter(<SearchPage />, { route: '/search' });
    await screen.findByText('웡카와 초콜릿');

    fireEvent.click(screen.getByRole('button', { name: '전체 삭제' }));

    expect(screen.queryByText('최근 검색어')).not.toBeInTheDocument();
  });
});
