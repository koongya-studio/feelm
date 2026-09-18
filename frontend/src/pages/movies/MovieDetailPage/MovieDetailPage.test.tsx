/*
 * 2.4 영화 상세 — 몰입형 화면과 실제 상세 데이터 (업무 S15P21E106-458, -535)
 *
 * 이 화면의 주 동작은 OTT 아이콘이다. MR !349 리뷰가 두 가지를 잡았다 — 기록을 기다린
 * 뒤에 `window.open`을 부르면 모바일 팝업 차단에 막힌다는 것과, 관련 영화로 넘어갈 때
 * 스크롤이 그대로 남아 새 영화가 이미 흐려진 배경으로 시작한다는 것. 둘 다 눈으로는
 * 잡히지 않는 순서와 상태 문제라 여기서 고정한다.
 *
 * 상세(API-030) · 유사(API-031) · 한줄평(API-032) · 클릭 기록(API-033)을 전부 가로챈다.
 * 새 탭은 `window.open`을 가로채 가짜 창을 돌려주고, 그 창의 `location.replace`가
 * 언제 어떤 주소로 불리는지 본다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import MovieDetailPage from './index';
import { renderWithRouter } from '../../../test/render';
import { AuthContext } from '../../../contexts/authContext';
import { ROUTE_PATTERN, path } from '../../../routes/paths';
import type { AuthStatus, AuthValue } from '../../../contexts/authContext';
import type { MovieDetail } from '../../../types/movie';
import type { WatchRecord } from '../../../types/watch';

vi.mock('../../../api/movies', () => ({
  getMovieDetail: vi.fn(),
  getSimilarMovies: vi.fn(),
  getMovieReviews: vi.fn(),
}));

vi.mock('../../../api/watch', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/watch')>()),
  recordOttClick: vi.fn(),
}));

const { getMovieDetail, getSimilarMovies, getMovieReviews } = await import('../../../api/movies');
const { recordOttClick } = await import('../../../api/watch');

const WATCH_URL = 'https://justwatch.example/watch/101';
const NETFLIX_SEARCH_URL = 'https://www.netflix.com/search?q=%EA%B4%B4%EB%AC%BC';

function movie(overrides: Partial<MovieDetail> = {}): MovieDetail {
  return {
    movieId: 101,
    title: '괴물',
    originalTitle: null,
    synopsis: '줄거리',
    releaseDate: '2019-07-10',
    runtimeMinutes: 108,
    genres: [{ code: 'DRAMA', name: '드라마' }],
    countries: [],
    directors: [],
    cast: [],
    posterUrl: null,
    backdropUrl: null,
    flavorCode: null,
    averageRating: 4.2,
    ratingCount: 10,
    ottProviders: [
      { ottId: 1, code: 'NETFLIX', name: '넷플릭스', watchUrl: WATCH_URL, subscribed: true },
      { ottId: 2, code: 'WATCHA', name: '왓챠', watchUrl: null, subscribed: false },
    ],
    myRating: null,
    myWatchStatus: null,
    recommendation: null,
    ...overrides,
  };
}

const record: WatchRecord = {
  watchRecordId: 77,
  movieId: 101,
  status: 'LINK_CLICKED',
  clickedAt: '2026-09-17T00:00:00Z',
  confirmDueAt: null,
  expiresAt: null,
};

/** `window.open`이 돌려주는 가짜 창. 주소를 넣고 닫는 것만 본다 */
function fakeWindow() {
  return {
    opener: window,
    location: { replace: vi.fn() },
    close: vi.fn(),
  } as unknown as Window;
}

/** 기록 응답을 손에 쥐고 있다가 원할 때 푼다. 새 탭을 여는 순서를 보려는 것이다 */
function deferredRecord() {
  let resolve!: (value: WatchRecord) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<WatchRecord>((res, rej) => {
    resolve = res;
    reject = rej;
  });

  return { promise, resolve, reject };
}

/*
 * API-030은 선택 인증이라 세션 복구 전에 부르면 비회원 응답이 굳는다(업무 -600).
 * 화면이 `status`를 보고 기다리므로 테스트도 인증 상태를 준다.
 */
function authValue(status: AuthStatus): AuthValue {
  return {
    status,
    user: null,
    signIn: vi.fn(),
    signInWithSocial: vi.fn(),
    completeSocialSignup: vi.fn(),
    signOut: vi.fn(),
  };
}

function renderMovie(movieId = 101, status: AuthStatus = 'anonymous') {
  return renderWithRouter(
    <AuthContext.Provider value={authValue(status)}>
      <MovieDetailPage />
    </AuthContext.Provider>,
    {
      route: path.movieDetail(movieId),
      path: ROUTE_PATTERN.movieDetail,
    },
  );
}

const location = () => screen.getByTestId('location').textContent;

beforeEach(() => {
  vi.mocked(getMovieDetail).mockReset().mockResolvedValue(movie());
  vi.mocked(getSimilarMovies).mockReset().mockResolvedValue([]);
  vi.mocked(getMovieReviews)
    .mockReset()
    .mockResolvedValue({ items: [], nextCursor: null, hasNext: false });
  vi.mocked(recordOttClick).mockReset();
  // jsdom의 `scrollTo`는 구현이 없다고 콘솔에 찍는다. 불렸는지만 세면 된다
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
});

/*
 * `vi.spyOn`은 테스트가 끝나도 남는다. 다음 테스트가 같은 메서드를 다시 spy하면 이전
 * 스파이를 그대로 돌려줘 호출 기록이 누적된다 — `open`이 3번, `scrollTo`가 6번으로
 * 세어진 것이 그 때문이었다. 매번 원래 함수로 되돌린다.
 */
afterEach(() => {
  vi.restoreAllMocks();
});

describe('2.4 OTT 아이콘', () => {
  it('OTT 연결 정보가 없으면 섹션을 숨기지 않고 빈 상태를 알린다', async () => {
    vi.mocked(getMovieDetail).mockResolvedValue(movie({ ottProviders: [] }));

    renderMovie();

    expect(await screen.findByRole('heading', { name: '볼 수 있는 곳' })).toBeInTheDocument();
    expect(screen.getByText('현재 확인된 OTT 제공처가 없어요')).toBeInTheDocument();
    expect(
      screen.queryByRole('link', { name: '시청 정보 제공: JustWatch' }),
    ).not.toBeInTheDocument();
  });

  it('OTT 제공처가 있으면 작은 JustWatch 출처 링크를 표시한다', async () => {
    renderMovie();

    expect(await screen.findByRole('link', { name: '시청 정보 제공: JustWatch' })).toHaveAttribute(
      'href',
      'https://www.justwatch.com/kr',
    );
  });

  it('API의 JustWatch 주소 대신 OTT의 영화 제목 검색 주소를 연다', async () => {
    const opened = fakeWindow();
    const open = vi.spyOn(window, 'open').mockReturnValue(opened);
    const pending = deferredRecord();
    vi.mocked(recordOttClick).mockReturnValue(pending.promise);

    renderMovie();
    fireEvent.click(await screen.findByRole('button', { name: '넷플릭스에서 보기' }));

    /*
     * 기록 응답이 아직 안 왔는데 창은 이미 열려 있어야 한다. await 뒤에 열면 사용자
     * 제스처가 끊겨 팝업 차단에 막힌다(MR !349 리뷰).
     */
    expect(open).toHaveBeenCalledWith('', '_blank');
    expect(opened.opener).toBeNull();
    expect(opened.location.replace).not.toHaveBeenCalled();
    expect(recordOttClick).toHaveBeenCalledWith(101, 1);

    pending.resolve(record);

    await waitFor(() => {
      expect(opened.location.replace).toHaveBeenCalledWith(NETFLIX_SEARCH_URL);
    });
    // 나간 뒤에도 이 화면은 그대로다. 감상 확인으로 보내지 않는다
    expect(location()).toBe(path.movieDetail(101));
  });

  it('브라우저가 새 탭을 막으면 그 사실을 알린다. 기록 실패와는 다른 문구다', async () => {
    vi.spyOn(window, 'open').mockReturnValue(null);
    vi.mocked(recordOttClick).mockResolvedValue(record);

    renderMovie();
    fireEvent.click(await screen.findByRole('button', { name: '넷플릭스에서 보기' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('새 탭이 열리지 않았어요');
    expect(screen.queryByText(/기록을 남기지 못했어요/)).not.toBeInTheDocument();
    expect(location()).toBe(path.movieDetail(101));
  });

  it('기록에 실패하면 열어 둔 빈 탭을 닫고 알린다', async () => {
    const opened = fakeWindow();
    vi.spyOn(window, 'open').mockReturnValue(opened);
    vi.mocked(recordOttClick).mockRejectedValue(new Error('boom'));

    renderMovie();
    fireEvent.click(await screen.findByRole('button', { name: '넷플릭스에서 보기' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('기록을 남기지 못했어요');
    expect(opened.close).toHaveBeenCalled();
    expect(opened.location.replace).not.toHaveBeenCalled();
  });

  it('Disney+는 검색어 주소 대신 한국 메인 페이지를 연다', async () => {
    const opened = fakeWindow();
    const open = vi.spyOn(window, 'open').mockReturnValue(opened);
    vi.mocked(recordOttClick).mockResolvedValue(record);
    vi.mocked(getMovieDetail).mockResolvedValue(
      movie({
        ottProviders: [
          {
            ottId: 5,
            code: 'DISNEY_PLUS',
            name: '디즈니플러스',
            watchUrl: WATCH_URL,
            subscribed: false,
          },
        ],
      }),
    );

    renderMovie();
    fireEvent.click(await screen.findByRole('button', { name: '디즈니플러스에서 보기' }));

    await waitFor(() => {
      expect(opened.location.replace).toHaveBeenCalledWith('https://www.disneyplus.com/ko-kr');
    });
    expect(open).toHaveBeenCalledWith('', '_blank');
    expect(location()).toBe(path.movieDetail(101));
    expect(recordOttClick).toHaveBeenCalledWith(101, 5);
  });
});

describe('2.4 선택 인증', () => {
  /*
   * API-030은 비회원에게도 200을 준다. 복구 중에 부르면 `myRating`과 `subscribed`가 빠진
   * 응답이 굳고, `client.ts`의 재시도는 401에만 돌아 되살릴 수단이 없다(업무 -600).
   */
  it('세션을 복구하는 동안에는 상세를 부르지 않는다', async () => {
    renderMovie(101, 'restoring');

    // 유사 영화·한줄평은 401 재시도로 되살아나므로 복구를 기다리지 않는다
    await waitFor(() => {
      expect(getSimilarMovies).toHaveBeenCalled();
    });
    expect(getMovieDetail).not.toHaveBeenCalled();
  });

  it('복구가 끝나면 상세를 부른다', async () => {
    renderMovie(101, 'authenticated');

    await screen.findByRole('heading', { level: 1, name: '괴물' });
    expect(getMovieDetail).toHaveBeenCalledWith(101, undefined);
  });
});

describe('2.4 화면', () => {
  it('감상 상태로 문구가 바뀌는 알약을 두지 않는다. 초기 설정에서 고른 영화도 이 화면에 온다', async () => {
    vi.mocked(getMovieDetail).mockResolvedValue(movie({ myWatchStatus: 'RATED' }));

    renderMovie();
    await screen.findByRole('heading', { level: 1, name: '괴물' });

    for (const name of ['넷플릭스에서 바로 보기', '별점 다시 남기기', '감상 확인하기']) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
    }
    expect(screen.getByRole('button', { name: '내 별점 남기기' })).toBeInTheDocument();
    // OTT는 로고 아이콘 하나로만 남는다
    expect(screen.getByRole('button', { name: '넷플릭스에서 보기' })).toBeInTheDocument();
  });

  it('관련 영화로 넘어가면 스크롤을 맨 위로 되돌린다', async () => {
    vi.mocked(getSimilarMovies).mockResolvedValue([
      {
        movieId: 202,
        title: '기생충',
        posterUrl: null,
        averageRating: 4.5,
        similarity: 0.9,
      },
    ]);

    renderMovie();
    await screen.findByRole('heading', { level: 1, name: '괴물' });
    expect(window.scrollTo).toHaveBeenCalledTimes(1);

    /*
     * 같은 라우트라 컴포넌트가 다시 마운트되지 않는다. 그래서 `id`가 바뀔 때 직접
     * 되돌리지 않으면 새 영화가 이미 흐려진 배경으로 시작한다(MR !349 리뷰,
     * `scrollY` 633 · 진행도 1.0으로 재현).
     */
    fireEvent.click(screen.getByRole('button', { name: /기생충/ }));

    await waitFor(() => {
      expect(location()).toBe(path.movieDetail(202));
    });
    expect(window.scrollTo).toHaveBeenCalledTimes(2);
    expect(window.scrollTo).toHaveBeenLastCalledWith(0, 0);
  });
});

describe('2.4 공개 한줄평과 출연진', () => {
  it('감독과 배우의 작은 프로필 이미지를 표시한다', async () => {
    vi.mocked(getMovieDetail).mockResolvedValue(
      movie({
        directors: [
          {
            personId: 1,
            name: '감독',
            profileUrl: 'https://image.tmdb.org/t/p/w185/director.jpg',
          },
        ],
        cast: [
          {
            personId: 2,
            name: '배우',
            character: '주인공',
            profileUrl: 'https://image.tmdb.org/t/p/w185/actor.jpg',
          },
        ],
      }),
    );

    const { container } = renderMovie();

    expect(await screen.findByText('감독')).toBeInTheDocument();
    expect(screen.queryByText('주인공')).not.toBeInTheDocument();
    expect(
      Array.from(container.querySelectorAll('img')).map((image) => image.getAttribute('src')),
    ).toEqual([
      'https://image.tmdb.org/t/p/w185/director.jpg',
      'https://image.tmdb.org/t/p/w185/actor.jpg',
    ]);
  });

  it('출연·제작 목록을 마우스로 잡아 끌어 가로 이동한다', async () => {
    vi.mocked(getMovieDetail).mockResolvedValue(
      movie({
        cast: Array.from({ length: 10 }, (_, index) => ({
          personId: index + 1,
          name: `배우 ${index + 1}`,
          profileUrl: null,
        })),
      }),
    );

    renderMovie();

    const list = await screen.findByRole('list', { name: '출연·제작 목록' });
    const setPointerCapture = vi.fn();
    const releasePointerCapture = vi.fn();
    Object.assign(list, {
      scrollLeft: 40,
      setPointerCapture,
      hasPointerCapture: vi.fn(() => true),
      releasePointerCapture,
    });

    fireEvent.pointerDown(list, {
      pointerId: 7,
      pointerType: 'mouse',
      button: 0,
      clientX: 300,
    });
    fireEvent.pointerMove(list, { pointerId: 7, pointerType: 'mouse', clientX: 100 });
    fireEvent.pointerUp(list, { pointerId: 7, pointerType: 'mouse' });

    expect(setPointerCapture).toHaveBeenCalledWith(7);
    expect(list.scrollLeft).toBe(240);
    expect(releasePointerCapture).toHaveBeenCalledWith(7);
  });

  it('목록의 스포일러는 작성자가 본인이어도 사용자가 열기 전까지 가린다', async () => {
    vi.mocked(getMovieReviews).mockResolvedValue({
      items: [
        {
          ratingId: 91,
          user: { userId: 1024, nickname: '팝콘러버' },
          score: 5,
          comment: '범인이 드러나는 문장',
          mine: false,
          spoiler: true,
          createdAt: '2026-08-30T05:21:00Z',
        },
        {
          ratingId: 92,
          user: { userId: 2048, nickname: '나' },
          score: 4,
          comment: '내 스포일러 한줄평',
          mine: true,
          spoiler: true,
          createdAt: '2026-08-29T05:21:00Z',
        },
      ],
      nextCursor: null,
      hasNext: false,
    });

    renderMovie();

    expect(await screen.findAllByText('스포일러가 포함된 한줄평을 가려두었어요')).toHaveLength(2);
    expect(screen.queryByText('범인이 드러나는 문장')).not.toBeInTheDocument();
    expect(screen.queryByText('내 스포일러 한줄평')).not.toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: '스포일러 포함 · 평가 보기' })).toHaveLength(2);

    fireEvent.click(screen.getAllByRole('button', { name: '스포일러 포함 · 평가 보기' })[0]);

    const revealedReview = await screen.findByText('범인이 드러나는 문장');
    expect(revealedReview.className).toContain('reviewBodyRevealed');
    expect(screen.queryByText('내 스포일러 한줄평')).not.toBeInTheDocument();
    expect(screen.getAllByText('스포일러가 포함된 한줄평을 가려두었어요')).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: '스포일러 포함 · 평가 보기' })).toHaveLength(1);
  });

  it('내 평가는 목록과 별개로 버튼 위에서 원문을 표시하고 수정 문구를 쓴다', async () => {
    vi.mocked(getMovieDetail).mockResolvedValue(
      movie({
        myRating: {
          ratingId: 90,
          score: 4.5,
          comment: '결말까지 재미있었다',
          visibility: 'PRIVATE',
          spoiler: true,
        },
        myWatchStatus: 'RATED',
      }),
    );

    renderMovie();

    expect(await screen.findByText('내 평가')).toBeInTheDocument();
    expect(screen.getByText('★ 4.5')).toBeInTheDocument();
    expect(screen.getByText('결말까지 재미있었다')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '내 별점 수정하기' })).toBeInTheDocument();
    expect(screen.queryByText('내 감상평')).not.toBeInTheDocument();
    expect(screen.queryByText('한 줄 감상 고치기')).not.toBeInTheDocument();
  });

  it('빈 페이지는 빈 목록 안내를 유지한다', async () => {
    renderMovie();
    expect(await screen.findByText('아직 공개된 한줄평이 없어요')).toBeInTheDocument();
  });

  it('첫 한줄평 조회가 실패하면 상세를 유지하고 다시 불러올 수 있다', async () => {
    vi.mocked(getMovieReviews)
      .mockRejectedValueOnce(new Error('temporary failure'))
      .mockResolvedValueOnce({ items: [], nextCursor: null, hasNext: false });

    renderMovie();

    expect(await screen.findByText('괴물')).toBeInTheDocument();
    expect(await screen.findByRole('alert')).toHaveTextContent('한줄평을 더 불러오지 못했어요');

    fireEvent.click(screen.getByRole('button', { name: '한줄평 다시 불러오기' }));

    expect(await screen.findByText('아직 공개된 한줄평이 없어요')).toBeInTheDocument();
    expect(getMovieReviews).toHaveBeenCalledTimes(2);
  });

  it('한줄평을 20개 받아 두고 화면에서 5개씩 펼친 뒤 다음 커서를 조회한다', async () => {
    vi.mocked(getMovieReviews)
      .mockResolvedValueOnce({
        items: Array.from({ length: 20 }, (_, index) => ({
          ratingId: 91 + index,
          user: { userId: 1024 + index, nickname: `첫 페이지 사용자 ${index + 1}` },
          score: 4,
          comment: `첫 페이지 한줄평 ${index + 1}`,
          mine: false,
          spoiler: false,
          createdAt: `2026-08-${String(29 - index).padStart(2, '0')}T05:21:00Z`,
        })),
        nextCursor: 'next-page',
        hasNext: true,
      })
      .mockResolvedValueOnce({
        items: [
          {
            ratingId: 196,
            user: { userId: 2048, nickname: '두 번째' },
            score: 3.5,
            comment: '다음 페이지',
            mine: false,
            spoiler: false,
            createdAt: '2026-08-09T05:21:00Z',
          },
        ],
        nextCursor: null,
        hasNext: false,
      });

    renderMovie();

    expect(await screen.findByText('첫 페이지 한줄평 5')).toBeInTheDocument();
    expect(screen.queryByText('첫 페이지 한줄평 6')).not.toBeInTheDocument();

    for (const lastVisible of [10, 15, 20]) {
      fireEvent.click(screen.getByRole('button', { name: '한줄평 더 보기' }));
      expect(await screen.findByText(`첫 페이지 한줄평 ${lastVisible}`)).toBeInTheDocument();
      expect(getMovieReviews).toHaveBeenCalledTimes(1);
    }

    fireEvent.click(screen.getByRole('button', { name: '한줄평 더 보기' }));

    expect(await screen.findByText('다음 페이지')).toBeInTheDocument();
    expect(getMovieReviews).toHaveBeenNthCalledWith(1, 101, undefined, 20);
    expect(getMovieReviews).toHaveBeenNthCalledWith(2, 101, 'next-page', 20);
    expect(screen.queryByRole('button', { name: '한줄평 더 보기' })).not.toBeInTheDocument();
  });

  it('API 페이지의 본인 평가도 최신순 위치에 그대로 표시한다', async () => {
    vi.mocked(getMovieReviews)
      .mockResolvedValueOnce({
        items: [
          ...Array.from({ length: 19 }, (_, index) => ({
            ratingId: 100 + index,
            user: { userId: 200 + index, nickname: `다른 사용자 ${index + 1}` },
            score: 4,
            comment: `타인 한줄평 ${index + 1}`,
            mine: false,
            spoiler: false,
            createdAt: `2026-08-${String(29 - index).padStart(2, '0')}T05:21:00Z`,
          })),
          {
            ratingId: 119,
            user: { userId: 900001, nickname: '나' },
            score: 4,
            comment: '목록에도 남아 있는 내 평가',
            mine: true,
            spoiler: false,
            createdAt: '2026-08-10T05:21:00Z',
          },
        ],
        nextCursor: 'after-mine',
        hasNext: true,
      })
      .mockResolvedValueOnce({
        items: [
          {
            ratingId: 120,
            user: { userId: 220, nickname: '다른 사용자 20' },
            score: 4.5,
            comment: '타인 한줄평 20',
            mine: false,
            spoiler: false,
            createdAt: '2026-08-09T05:21:00Z',
          },
        ],
        nextCursor: null,
        hasNext: false,
      });

    renderMovie();

    expect(await screen.findByText('타인 한줄평 5')).toBeInTheDocument();
    expect(screen.queryByText('목록에도 남아 있는 내 평가')).not.toBeInTheDocument();

    for (let index = 0; index < 3; index += 1) {
      fireEvent.click(screen.getByRole('button', { name: '한줄평 더 보기' }));
    }

    expect(await screen.findByText('목록에도 남아 있는 내 평가')).toBeInTheDocument();
    expect(getMovieReviews).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole('button', { name: '한줄평 더 보기' }));
    expect(await screen.findByText('타인 한줄평 20')).toBeInTheDocument();
  });

  it('긴 목록 아래에서 맨 위로 부드럽게 이동할 수 있다', async () => {
    renderMovie();

    fireEvent.click(await screen.findByRole('button', { name: '↑ 맨 위로' }));
    expect(window.scrollTo).toHaveBeenCalledWith({ top: 0, behavior: 'smooth' });
  });
});
