/*
 * 2.4 영화 상세 — OTT 클릭 흐름과 화면 전환 (업무 S15P21E106-458)
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
import { ROUTE_PATTERN, path } from '../../../routes/paths';
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

const WATCH_URL = 'https://ott.example/watch/101';

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

function renderMovie(movieId = 101) {
  return renderWithRouter(<MovieDetailPage />, {
    route: path.movieDetail(movieId),
    path: ROUTE_PATTERN.movieDetail,
  });
}

const location = () => screen.getByTestId('location').textContent;

beforeEach(() => {
  vi.mocked(getMovieDetail).mockReset().mockResolvedValue(movie());
  vi.mocked(getSimilarMovies).mockReset().mockResolvedValue([]);
  vi.mocked(getMovieReviews).mockReset().mockResolvedValue([]);
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
  it('새 탭은 기록을 기다리기 전에 누른 자리에서 열고, 주소는 기록이 끝난 뒤 넣는다', async () => {
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
      expect(opened.location.replace).toHaveBeenCalledWith(WATCH_URL);
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

  it('시청 주소가 없는 OTT는 새 탭을 열지 않고 2.4-A 감상 확인으로 보낸다', async () => {
    const open = vi.spyOn(window, 'open').mockReturnValue(null);
    vi.mocked(recordOttClick).mockResolvedValue(record);

    renderMovie();
    fireEvent.click(await screen.findByRole('button', { name: '왓챠에서 보기' }));

    await waitFor(() => {
      expect(location()).toBe(path.watchConfirm(77));
    });
    expect(open).not.toHaveBeenCalled();
    expect(recordOttClick).toHaveBeenCalledWith(101, 2);
  });
});

describe('2.4 화면', () => {
  it('감상 상태로 문구가 바뀌는 알약을 두지 않는다. 초기 설정에서 고른 영화도 이 화면에 온다', async () => {
    vi.mocked(getMovieDetail).mockResolvedValue(movie({ myWatchStatus: 'RATED' }));

    renderMovie();
    await screen.findByRole('heading', { level: 1, name: '괴물' });

    for (const name of [
      '넷플릭스에서 바로 보기',
      '별점 다시 남기기',
      '감상 확인하기',
      '내 별점 남기기',
    ]) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
    }
    // OTT는 로고 아이콘 하나로만 남는다
    expect(screen.getByRole('button', { name: '넷플릭스에서 보기' })).toBeInTheDocument();
  });

  it('관련 영화로 넘어가면 스크롤을 맨 위로 되돌린다', async () => {
    vi.mocked(getSimilarMovies).mockResolvedValue([
      {
        movieId: 202,
        title: '기생충',
        posterUrl: null,
        releaseYear: 2019,
        averageRating: 4.5,
        similarity: 0.9,
        reason: { summary: '감독이 같아요' },
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
