/*
 * 4.1 · 4.2 · 4.4 — 한 화면의 상태 전환 (업무 S15P21E106-303 · -476 · -612)
 *
 * 이 화면은 프레임을 상태로 나눠 갖는다. 어느 상태를 보여줄지는 검색이 `idle`인지
 * 결과가 빈 `items`인지로 갈리는데, 그 둘을 헷갈리면 **검색하지 않은 화면과 결과가 없는
 * 화면이 뒤바뀐다.** 눈으로는 둘 다 "목록이 없는 화면"이라 구분되지 않는다.
 *
 * 업무 -612에서 검색이 **타이핑에 따라** 나가게 됐다. 그래서 고정할 것이 늘었다 — 한
 * 글자에도 나가는지, 공백만으로는 안 나가는지, 한글 조합 중에는 참는지, 늦게 온 옛 응답이
 * 새 결과를 덮지 않는지. 디바운스는 실제 타이머로 기다린다. 가짜 타이머는
 * `findBy`의 폴링과 얽혀 시험이 더 어려워진다.
 *
 * 커서 페이지(-476)는 그대로다. **건수는 받아 온 `items`가 아니라 `totalCount`**이고,
 * 다음 장이 실패해도 이미 그린 목록은 남아야 한다. jsdom에는 `IntersectionObserver`가
 * 없으므로 이어 받기는 버튼으로 시험한다 — 화면도 그 환경에서는 버튼을 낸다.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import SearchPage from './index';
import { renderWithRouter } from '../../../test/render';
import { clearRecentSearchQueries, resetSessionStore } from '../../../mocks/sessionStore';
import type { MovieSearchItem, MovieSearchResult } from '../../../types/search';

vi.mock('../../../api/search', () => ({
  searchMovies: vi.fn(),
  countSearchResults: vi.fn(),
  getFilterOptions: vi.fn(),
}));

const { searchMovies, countSearchResults, getFilterOptions } = await import('../../../api/search');

/** 디바운스(280ms)가 지나가고도 남을 시간. "요청이 나가지 않는다"를 단정할 때 기다린다 */
const SETTLE_MS = 400;

const settle = () => new Promise((resolve) => setTimeout(resolve, SETTLE_MS));

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

  vi.mocked(getFilterOptions).mockResolvedValue(filterOptions);
  vi.mocked(searchMovies).mockResolvedValue(page([]));
  vi.mocked(countSearchResults).mockResolvedValue(0);
});

const input = () => screen.getByRole('searchbox', { name: '영화 검색' });

/** 검색어를 친다. 디바운스가 지나면 검색이 나간다 */
function type(keyword: string) {
  fireEvent.change(input(), { target: { value: keyword } });
}

/** 검색어를 넣고 키보드의 검색 버튼(제출)으로 바로 검색한다 */
function submit(keyword: string) {
  type(keyword);
  fireEvent.submit(input().closest('form') as HTMLFormElement);
}

/** `검색 결과 N` 머리줄 */
const resultsHeading = (count: number) =>
  screen.findByRole('heading', { name: `검색 결과 ${count}` });

describe('4.1 검색 전', () => {
  it('최근 검색어가 있으면 그것만 보여준다. 급상승은 없다', () => {
    renderWithRouter(<SearchPage />, { route: '/search' });

    expect(screen.getByRole('heading', { name: '검색' })).toBeInTheDocument();
    expect(screen.getByText('최근 검색어')).toBeInTheDocument();
    expect(screen.getByText('제목 · 감독 · 배우 통합 검색')).toBeInTheDocument();
    expect(screen.queryByText(/급상승/)).not.toBeInTheDocument();
  });

  it('최근 검색어가 없으면 안내 한 줄이다', () => {
    clearRecentSearchQueries();

    renderWithRouter(<SearchPage />, { route: '/search' });

    expect(screen.getByText('영화 제목, 감독, 배우 이름으로 검색해 보세요')).toBeInTheDocument();
    expect(screen.queryByText('최근 검색어')).not.toBeInTheDocument();
  });
});

describe('타이핑에 따라 검색한다', () => {
  it.each(['ㄱ', '달', 'A', '1'])('한 글자 "%s"로도 검색한다', async (keyword) => {
    renderWithRouter(<SearchPage />, { route: '/search' });

    type(keyword);

    await waitFor(() =>
      expect(vi.mocked(searchMovies)).toHaveBeenCalledWith(expect.objectContaining({ q: keyword })),
    );
  });

  it('공백만 치면 검색하지 않고 4.1에 머문다', async () => {
    renderWithRouter(<SearchPage />, { route: '/search' });

    type('   ');
    await settle();

    expect(vi.mocked(searchMovies)).not.toHaveBeenCalled();
    expect(screen.getByText('최근 검색어')).toBeInTheDocument();
  });

  it('앞뒤 공백은 떼고 보낸다', async () => {
    renderWithRouter(<SearchPage />, { route: '/search' });

    type('  달  ');

    await waitFor(() =>
      expect(vi.mocked(searchMovies)).toHaveBeenCalledWith(expect.objectContaining({ q: '달' })),
    );
  });

  it('연속 입력은 마지막 값으로 한 번만 보낸다', async () => {
    renderWithRouter(<SearchPage />, { route: '/search' });

    type('달');
    type('달의');
    type('달의 뒷');

    await waitFor(() => expect(vi.mocked(searchMovies)).toHaveBeenCalledTimes(1));
    expect(vi.mocked(searchMovies)).toHaveBeenCalledWith(expect.objectContaining({ q: '달의 뒷' }));
  });

  it('한글 조합 중에는 보내지 않고 조합이 끝나면 보낸다', async () => {
    renderWithRouter(<SearchPage />, { route: '/search' });

    fireEvent.compositionStart(input());
    type('ㄷ');
    type('다');
    await settle();

    expect(vi.mocked(searchMovies)).not.toHaveBeenCalled();

    type('달');
    fireEvent.compositionEnd(input());

    await waitFor(() =>
      expect(vi.mocked(searchMovies)).toHaveBeenCalledWith(expect.objectContaining({ q: '달' })),
    );
    expect(vi.mocked(searchMovies)).toHaveBeenCalledTimes(1);
  });

  it('키보드의 검색 버튼은 기다리지 않고 바로 보낸다', () => {
    renderWithRouter(<SearchPage />, { route: '/search' });

    submit('달');

    expect(vi.mocked(searchMovies)).toHaveBeenCalledTimes(1);
  });

  it('늦게 온 옛 응답은 새 결과를 덮지 않는다', async () => {
    let resolveFirst: (value: MovieSearchResult) => void = () => {};
    vi.mocked(searchMovies)
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveFirst = resolve;
          }),
      )
      .mockResolvedValueOnce(page([item(2, '달의 노래')]));

    renderWithRouter(<SearchPage />, { route: '/search' });

    submit('달');
    submit('달의');
    await screen.findByText('달의 노래');

    resolveFirst(page([item(1, '달의 뒷면')]));
    await settle();

    expect(screen.getByText('달의 노래')).toBeInTheDocument();
    expect(screen.queryByText('달의 뒷면')).not.toBeInTheDocument();
  });
});

describe('4.2 결과 · 4.4 결과 없음 · 오류', () => {
  it('검색 중에는 스켈레톤을 보여준다', () => {
    vi.mocked(searchMovies).mockReturnValue(new Promise(() => {}));

    renderWithRouter(<SearchPage />, { route: '/search' });

    submit('달');

    expect(screen.getByRole('status')).toHaveTextContent('검색 중');
    expect(screen.queryByText('최근 검색어')).not.toBeInTheDocument();
  });

  it('결과는 포스터와 제목만 있는 격자다. 칸은 상세로 가는 링크다 (4.2 → 2.4)', async () => {
    vi.mocked(searchMovies).mockResolvedValue(
      page([item(201, '달의 뒷면'), item(202, '달빛 정원')]),
    );

    renderWithRouter(<SearchPage />, { route: '/search' });

    submit('달');

    expect(await resultsHeading(2)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '달의 뒷면' })).toHaveAttribute('href', '/movies/201');
    // 칸마다 상세로 가는 단서가 있다(업무 -697)
    expect(screen.getAllByTestId('poster-cue')).toHaveLength(2);
    // 연도 · 장르 · 평점 · OTT는 칸에 없다
    expect(screen.queryByText(/2020/)).not.toBeInTheDocument();
    expect(screen.queryByText(/드라마/)).not.toBeInTheDocument();
    expect(screen.queryByText(/★/)).not.toBeInTheDocument();
    expect(screen.queryByText(/4\.0/)).not.toBeInTheDocument();
    expect(screen.queryByText(/NETFLIX|넷플릭스/)).not.toBeInTheDocument();
  });

  it('정렬을 바꾸면 같은 검색어로 다시 검색한다', async () => {
    vi.mocked(searchMovies).mockResolvedValue(page([item(201, '달의 뒷면')]));

    renderWithRouter(<SearchPage />, { route: '/search' });

    submit('달');
    await resultsHeading(1);

    fireEvent.change(screen.getByRole('combobox', { name: '정렬' }), {
      target: { value: 'RATING' },
    });

    await waitFor(() =>
      expect(vi.mocked(searchMovies)).toHaveBeenLastCalledWith(
        expect.objectContaining({ q: '달', sort: 'RATING' }),
      ),
    );
  });

  it('결과가 없으면 검색어를 넣은 안내로 바꾼다 (4.4)', async () => {
    renderWithRouter(<SearchPage />, { route: '/search' });

    submit('인터스텔라22');

    expect(await screen.findByText('‘인터스텔라22’에 대한 결과가 없어요')).toBeInTheDocument();
    expect(screen.getByText('맞춤법을 확인하거나 다른 검색어를 입력해 보세요')).toBeInTheDocument();
    // 필터가 없으면 초기화할 것도 없다
    expect(screen.queryByRole('button', { name: '필터 초기화' })).not.toBeInTheDocument();
  });

  it('필터가 걸린 채 결과가 없으면 필터 초기화가 나오고, 누르면 검색어만으로 다시 찾는다', async () => {
    renderWithRouter(<SearchPage />, { route: '/search' });
    await waitFor(() => expect(screen.getByRole('button', { name: '필터' })).toBeEnabled());

    submit('달');
    fireEvent.click(screen.getByRole('button', { name: '필터' }));
    await screen.findByRole('dialog', { name: '검색 필터' });
    fireEvent.click(screen.getByRole('button', { name: '드라마' }));
    fireEvent.click(screen.getByRole('button', { name: /결과 보기/ }));

    expect(await screen.findByRole('button', { name: '필터 초기화' })).toBeInTheDocument();

    vi.mocked(searchMovies).mockResolvedValue(page([item(201, '달의 뒷면')]));
    fireEvent.click(screen.getByRole('button', { name: '필터 초기화' }));

    expect(await resultsHeading(1)).toBeInTheDocument();
    expect(vi.mocked(searchMovies)).toHaveBeenLastCalledWith(
      expect.not.objectContaining({ genre: expect.anything() }),
    );
  });

  it('X를 누르면 입력과 결과가 함께 비고 4.1로 돌아간다', async () => {
    vi.mocked(searchMovies).mockResolvedValue(page([item(201, '달의 뒷면')]));

    renderWithRouter(<SearchPage />, { route: '/search' });

    submit('달');
    await resultsHeading(1);

    fireEvent.click(screen.getByRole('button', { name: '검색어 지우기' }));

    expect(input()).toHaveValue('');
    expect(screen.queryByText('달의 뒷면')).not.toBeInTheDocument();
    expect(screen.getByText('최근 검색어')).toBeInTheDocument();
  });

  it('검색이 실패하면 안내와 다시 시도가 나온다', async () => {
    vi.mocked(searchMovies).mockRejectedValue(new Error('실패'));

    renderWithRouter(<SearchPage />, { route: '/search' });

    submit('달');

    expect(await screen.findByText('검색 결과를 불러오지 못했어요')).toBeInTheDocument();

    // 다시 시도하면 같은 조건으로 한 번 더 조회한다
    vi.mocked(searchMovies).mockResolvedValue(page([item(201, '달의 뒷면')]));
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));

    expect(await resultsHeading(1)).toBeInTheDocument();
  });

  it('필터 옵션을 못 받으면 필터 버튼만 잠긴다', async () => {
    vi.mocked(getFilterOptions).mockRejectedValue(new Error('실패'));

    renderWithRouter(<SearchPage />, { route: '/search' });
    await settle();

    expect(screen.getByRole('button', { name: '필터' })).toBeDisabled();
    expect(input()).toBeEnabled();
  });
});

describe('4.3 필터 시트', () => {
  it('닫으면 고른 조건으로 다시 검색한다 (4.3 → 4.2)', async () => {
    renderWithRouter(<SearchPage />, { route: '/search' });
    await waitFor(() => expect(screen.getByRole('button', { name: '필터' })).toBeEnabled());

    fireEvent.click(screen.getByRole('button', { name: '필터' }));
    await screen.findByRole('dialog', { name: '검색 필터' });

    // 정렬은 시트에 없다. 결과 머리줄이 맡는다
    expect(screen.queryByText('정렬 기준')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '드라마' }));

    // 고른 조건은 색만이 아니라 체크로도 보인다(업무 -697). 이름은 그대로다
    const drama = screen.getByRole('button', { name: '드라마' });
    expect(drama).toHaveAttribute('aria-pressed', 'true');
    expect(drama.querySelector('svg')).not.toBeNull();

    vi.mocked(searchMovies).mockResolvedValue(page([item(208, '여름의 끝')]));
    fireEvent.click(screen.getByRole('button', { name: /결과 보기/ }));

    expect(await resultsHeading(1)).toBeInTheDocument();

    // 검색어 없이 필터만으로도 질의가 나간다
    await waitFor(() =>
      expect(vi.mocked(searchMovies)).toHaveBeenCalledWith(
        expect.objectContaining({ genre: ['DRAMA'] }),
      ),
    );
  });

  it('아무 조건도 없으면 개수를 세지 않는다', async () => {
    renderWithRouter(<SearchPage />, { route: '/search' });
    await waitFor(() => expect(screen.getByRole('button', { name: '필터' })).toBeEnabled());

    fireEvent.click(screen.getByRole('button', { name: '필터' }));

    // 검색어도 필터도 없으면 계약이 400을 주므로 애초에 보내지 않는다
    expect(await screen.findByRole('button', { name: '결과 보기' })).toBeInTheDocument();
    expect(vi.mocked(countSearchResults)).not.toHaveBeenCalled();
  });
});

describe('4.3 필터 시트 접근성 (업무 -674)', () => {
  const openSheet = async () => {
    renderWithRouter(<SearchPage />, { route: '/search' });
    await waitFor(() => expect(screen.getByRole('button', { name: '필터' })).toBeEnabled());

    const opener = screen.getByRole('button', { name: '필터' });
    fireEvent.click(opener);
    const dialog = await screen.findByRole('dialog', { name: '검색 필터' });

    return { opener, dialog };
  };

  it('여는 버튼은 대화상자를 연다고 알리고, 열리면 그 시트를 가리킨다', async () => {
    renderWithRouter(<SearchPage />, { route: '/search' });
    await waitFor(() => expect(screen.getByRole('button', { name: '필터' })).toBeEnabled());

    const opener = screen.getByRole('button', { name: '필터' });
    // 켜고 끄는 토글이 아니다
    expect(opener).not.toHaveAttribute('aria-pressed');
    expect(opener).toHaveAttribute('aria-haspopup', 'dialog');
    expect(opener).toHaveAttribute('aria-expanded', 'false');

    fireEvent.click(opener);
    const dialog = await screen.findByRole('dialog', { name: '검색 필터' });

    expect(opener).toHaveAttribute('aria-expanded', 'true');
    expect(opener).toHaveAttribute('aria-controls', dialog.id);
  });

  it('필터가 걸려 있는지는 이름이 아니라 설명으로 따로 읽힌다', async () => {
    await openSheet();
    fireEvent.click(screen.getByRole('button', { name: '드라마' }));
    fireEvent.click(screen.getByRole('button', { name: /결과 보기/ }));

    const opener = screen.getByRole('button', { name: '필터' });
    await waitFor(() => expect(opener).toHaveAccessibleDescription('필터 적용 중'));
  });

  it('열린 동안 Tab은 시트 안에서만 돈다', async () => {
    const { dialog } = await openSheet();
    const close = screen.getByRole('button', { name: '닫기' });
    const buttons = [...dialog.querySelectorAll('button')];
    const last = buttons[buttons.length - 1];

    // 열리면 닫기에 들어와 있다
    expect(close).toHaveFocus();

    // 처음에서 거꾸로 넘기면 끝으로
    fireEvent.keyDown(window, { key: 'Tab', shiftKey: true });
    expect(last).toHaveFocus();

    // 끝에서 넘기면 처음으로
    fireEvent.keyDown(window, { key: 'Tab' });
    expect(close).toHaveFocus();
  });

  it('뒤 막은 Tab 순서와 보조기술에서 빠진다', async () => {
    await openSheet();

    expect(screen.queryByRole('button', { name: '필터 닫기' })).not.toBeInTheDocument();
  });

  it.each([
    ['닫기', () => fireEvent.click(screen.getByRole('button', { name: '닫기' }))],
    ['Escape', () => fireEvent.keyDown(window, { key: 'Escape' })],
    ['결과 보기', () => fireEvent.click(screen.getByRole('button', { name: /결과 보기/ }))],
  ])('%s로 닫으면 초점이 여는 버튼으로 돌아간다', async (_, close) => {
    const { opener } = await openSheet();

    close();

    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: '검색 필터' })).not.toBeInTheDocument(),
    );
    expect(opener).toHaveFocus();
    expect(opener).toHaveAttribute('aria-expanded', 'false');
  });
});

describe('커서 페이지', () => {
  it('건수는 받아 온 목록이 아니라 totalCount다', async () => {
    vi.mocked(searchMovies).mockResolvedValue(
      page([item(201, '달의 뒷면')], { totalCount: 24, nextCursor: 'cursor-2', hasNext: true }),
    );

    renderWithRouter(<SearchPage />, { route: '/search' });

    submit('달');

    expect(await resultsHeading(24)).toBeInTheDocument();
  });

  it('더 보기는 같은 조건에 커서를 실어 다음 장을 이어 붙인다', async () => {
    vi.mocked(searchMovies).mockResolvedValue(
      page([item(201, '달의 뒷면')], { totalCount: 2, nextCursor: 'cursor-2', hasNext: true }),
    );

    renderWithRouter(<SearchPage />, { route: '/search' });

    submit('달');
    await screen.findByText('달의 뒷면');

    vi.mocked(searchMovies).mockResolvedValue(page([item(202, '달빛 정원')], { totalCount: 2 }));
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));

    expect(await screen.findByText('달빛 정원')).toBeInTheDocument();
    // 앞 장은 그대로 남는다
    expect(screen.getByText('달의 뒷면')).toBeInTheDocument();
    expect(vi.mocked(searchMovies)).toHaveBeenLastCalledWith(
      expect.objectContaining({ q: '달' }),
      'cursor-2',
    );
    // 다음 장이 없으면 버튼도 사라진다
    expect(screen.queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument();
  });

  it('더 보기가 실패해도 이미 그린 목록은 남는다', async () => {
    vi.mocked(searchMovies).mockResolvedValue(
      page([item(201, '달의 뒷면')], { totalCount: 2, nextCursor: 'cursor-2', hasNext: true }),
    );

    renderWithRouter(<SearchPage />, { route: '/search' });

    submit('달');
    await screen.findByText('달의 뒷면');

    vi.mocked(searchMovies).mockRejectedValue(new Error('실패'));
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));

    expect(await screen.findByText('다음 결과를 불러오지 못했어요.')).toBeInTheDocument();
    expect(screen.getByText('달의 뒷면')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
  });
});

describe('최근 검색어', () => {
  it('제출하면 앞으로 들어가고, 같은 말은 다시 쌓이지 않고 맨 앞으로 온다', async () => {
    vi.mocked(searchMovies).mockResolvedValue(page([item(201, '달의 뒷면')]));

    renderWithRouter(<SearchPage />, { route: '/search' });

    submit('크리스토퍼 놀란');
    await resultsHeading(1);
    fireEvent.click(screen.getByRole('button', { name: '검색어 지우기' }));

    const chips = screen.getAllByRole('button', { name: /크리스토퍼 놀란|케이퍼 무비|SF 명작/ });
    expect(chips.map((chip) => chip.textContent)).toEqual([
      '크리스토퍼 놀란',
      '케이퍼 무비',
      'SF 명작',
    ]);
  });

  it('타이핑만으로는 남기지 않는다', async () => {
    renderWithRouter(<SearchPage />, { route: '/search' });

    type('달');
    await waitFor(() => expect(vi.mocked(searchMovies)).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: '검색어 지우기' }));

    expect(screen.queryByRole('button', { name: '달' })).not.toBeInTheDocument();
  });

  it('전체 삭제하면 목록이 사라지고 안내로 바뀐다', () => {
    renderWithRouter(<SearchPage />, { route: '/search' });

    fireEvent.click(screen.getByRole('button', { name: '전체 삭제' }));

    expect(screen.queryByText('최근 검색어')).not.toBeInTheDocument();
    expect(screen.getByText('영화 제목, 감독, 배우 이름으로 검색해 보세요')).toBeInTheDocument();
  });
});
