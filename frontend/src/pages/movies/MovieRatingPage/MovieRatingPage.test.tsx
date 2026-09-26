/*
 * 2.5 영화 평가 — 저장 (업무 S15P21E106-491)
 *
 * 고정하는 것은 세 가지다.
 *
 * - **저장에 성공하면 API-036 응답과 추가 전 수치를 그대로 2.6에 넘긴다.** 2.6 · 2.7은
 *   Router state만 보고 그리므로, 여기서 빠진 값은 그 화면에서 지어낼 수 없다. 추가 전 수치는
 *   화면에 들어올 때 받아 두고, 추가 후 수치는 2.6이 읽으므로 기다리지 않는다(업무 -635)
 * - **실패하면 화면에 남기고 이동하지 않는다.** 서버 문구를 그대로 쓰고, 다시 누를 수 있다
 * - **`409 RATING_ALREADY_EXISTS`는 수정 모드로 바꾼다.** 상세를 읽은 뒤 다른 곳에서 평가가
 *   생긴 경우다. 알리기만 하면 사용자는 같은 요청을 반복하게 된다
 *
 * 실제 호출인 도메인만 가로챈다 — 영화 상세(`api/movies`) · 감상 기록과 평가(`api/watch`) ·
 * OTT 기준정보(`api/otts`).
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import MovieRatingPage from './index';
import WatchConfirmPage from '../../watch/WatchConfirmPage';
import { ApiError } from '../../../api/apiError';
import { ROUTE_PATTERN } from '../../../routes/paths';
import type { MovieDetail } from '../../../types/movie';
import type { Rating } from '../../../types/watch';

vi.mock('../../../api/movies', () => ({ getMovieDetail: vi.fn() }));
vi.mock('../../../api/otts', () => ({ getOtts: vi.fn() }));
vi.mock('../../../api/watch', () => ({
  confirmWatch: vi.fn(),
  findWatchRecord: vi.fn(),
  createRating: vi.fn(),
  deleteRating: vi.fn(),
  findWatchedRecord: vi.fn(),
  getRatingCounts: vi.fn(),
  updateRating: vi.fn(),
}));

const { getMovieDetail } = await import('../../../api/movies');
const { getOtts } = await import('../../../api/otts');
const { createRating, deleteRating, findWatchedRecord, getRatingCounts, updateRating } =
  await import('../../../api/watch');
const { confirmWatch, findWatchRecord } = await import('../../../api/watch');

/** 아직 평가하지 않은 실제 영화(API-030) */
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
  posterUrl: null,
  backdropUrl: null,
  flavorCode: 'FLAVOR_A',
  averageRating: 4.3,
  ratingCount: 10,
  ottProviders: [],
  myRating: null,
  myWatchStatus: null,
  recommendation: null,
};

/** 서버가 만든 평가(API-036) */
const created: Rating = {
  ratingId: 91,
  movieId: 550,
  score: 4.5,
  comment: null,
  visibility: 'PRIVATE',
  spoiler: false,
  frame: { frameId: 77, createdAt: '2026-08-30T05:21:00Z' },
  popcorn: { flavorCode: 'FLAVOR_A', isNewFlavor: true, flavorCount: 1 },
  watchStatus: 'RATED',
  createdAt: '2026-08-30T05:21:00Z',
};

const counts = { frameCount: 12, popcornCount: 7, weeklyFrameDelta: 3 };

it('알림에서 감상 확인 후 평가를 미루면 알림 목록으로 돌아간다', async () => {
  vi.mocked(findWatchRecord).mockResolvedValue({
    watchRecordId: 42,
    movie: { movieId: 550, title: '파이트 클럽', posterUrl: null },
    status: 'LINK_CLICKED',
    ott: null,
    clickedAt: null,
    confirmDueAt: null,
    expiresAt: null,
    watchedAt: null,
    ratingId: null,
  });
  vi.mocked(confirmWatch).mockResolvedValue({
    watchRecordId: 42,
    status: 'WATCHED_UNRATED',
    watchedAt: '2026-09-26T00:00:00Z',
  });
  render(
    <MemoryRouter initialEntries={['/notifications', '/watch-records/42/confirm']} initialIndex={1}>
      <Routes>
        <Route path={ROUTE_PATTERN.notifications} element={<h1>알림 목록</h1>} />
        <Route path={ROUTE_PATTERN.watchConfirm} element={<WatchConfirmPage />} />
        <Route path={ROUTE_PATTERN.movieRating} element={<MovieRatingPage />} />
      </Routes>
    </MemoryRouter>,
  );
  fireEvent.click(await screen.findByRole('button', { name: '봤어요' }));
  fireEvent.click(await screen.findByRole('button', { name: '나중에 평가하기' }));
  expect(await screen.findByRole('heading', { name: '알림 목록' })).toBeInTheDocument();
  expect(confirmWatch).toHaveBeenCalledWith(42, true);
  expect(createRating).not.toHaveBeenCalled();
});

beforeEach(() => {
  vi.mocked(getMovieDetail).mockReset().mockResolvedValue(movie);
  vi.mocked(getOtts).mockReset().mockResolvedValue([]);
  vi.mocked(findWatchedRecord).mockReset().mockResolvedValue(null);
  vi.mocked(getRatingCounts).mockReset().mockResolvedValue(counts);
  vi.mocked(createRating).mockReset().mockResolvedValue(created);
  vi.mocked(updateRating).mockReset().mockResolvedValue(created);
  vi.mocked(deleteRating).mockReset().mockResolvedValue(undefined);
});

/** 2.6이 받은 Router state를 드러낸다. 2.6 자체는 여기서 그리지 않는다 */
function ResultProbe() {
  const location = useLocation();

  return (
    <div data-testid="result" data-state={JSON.stringify(location.state)}>
      {location.pathname}
    </div>
  );
}

function LocationProbe() {
  const location = useLocation();

  return <div data-testid="location">{location.pathname}</div>;
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/movies/550/rate']}>
      <Routes>
        <Route path={ROUTE_PATTERN.movieRating} element={<MovieRatingPage />} />
        <Route path={ROUTE_PATTERN.ratingResult} element={<ResultProbe />} />
      </Routes>
      <LocationProbe />
    </MemoryRouter>,
  );
}

const location = () => screen.getByTestId('location').textContent;

async function rate(score: number, comment?: string) {
  const slider = await screen.findByRole('slider', { name: '별점' });
  fireEvent.change(slider, { target: { value: String(score) } });

  if (comment !== undefined) {
    fireEvent.change(screen.getByLabelText('한줄평'), { target: { value: comment } });
  }

  fireEvent.click(screen.getByRole('button', { name: '평가 완료' }));
}

describe('2.5 평가 저장', () => {
  it('점수만 매기면 API-036에 점수만 보내고 응답과 추가 전 수치를 2.6에 넘긴다', async () => {
    renderPage();

    // 새 평가의 제목은 `평가하기`다. 수정일 때만 `평가 수정`이다(업무 S15P21E106-638)
    expect(await screen.findByRole('heading', { level: 1, name: '평가하기' })).toBeInTheDocument();
    // 평가하러 왔다가 미루는 길은 새 평가에 남는다(업무 S15P21E106-716)
    expect(screen.getByRole('button', { name: '나중에 평가하기' })).toBeInTheDocument();
    await rate(4.5);

    await waitFor(() => expect(location()).toBe('/movies/550/rate/result'));
    expect(createRating).toHaveBeenCalledWith(550, { score: 4.5 });

    const state = JSON.parse(screen.getByTestId('result').getAttribute('data-state') ?? '{}');
    expect(state).toEqual({
      rating: created,
      counts,
      movieTitle: '파이트 클럽',
      posterUrl: null,
    });
  });

  it('추가 전 수치는 화면에 들어올 때 받아 두고 저장 뒤에는 다시 읽지 않는다', async () => {
    renderPage();

    await screen.findByRole('slider', { name: '별점' });
    // 누르기 전에 이미 받았다
    expect(getRatingCounts).toHaveBeenCalledTimes(1);

    await rate(4.5);

    await waitFor(() => expect(location()).toBe('/movies/550/rate/result'));
    // 추가 후 수치는 2.6이 읽는다. 2.5는 그것을 기다리지 않는다
    expect(getRatingCounts).toHaveBeenCalledTimes(1);
  });

  it('미리 받지 못한 추가 전 수치는 누를 때 다시 받아 저장한다', async () => {
    vi.mocked(getRatingCounts)
      .mockReset()
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '네트워크에 연결할 수 없습니다.'))
      .mockResolvedValue(counts);

    renderPage();

    await rate(4.5);

    await waitFor(() => expect(location()).toBe('/movies/550/rate/result'));
    expect(getRatingCounts).toHaveBeenCalledTimes(2);

    const state = JSON.parse(screen.getByTestId('result').getAttribute('data-state') ?? '{}');
    expect(state.counts).toEqual(counts);
  });

  it('한줄평이 있으면 공개 범위와 스포일러 여부를 붙여 보낸다', async () => {
    renderPage();

    const spoiler = await screen.findByRole('checkbox', { name: '스포일러가 포함되어 있어요' });
    expect(spoiler).toBeDisabled();
    fireEvent.change(screen.getByLabelText('한줄평'), {
      target: { value: '  밤에 보면 안 됨  ' },
    });
    expect(spoiler).toBeEnabled();
    fireEvent.click(spoiler);
    fireEvent.change(screen.getByRole('slider', { name: '별점' }), { target: { value: '3' } });
    fireEvent.click(screen.getByRole('button', { name: '평가 완료' }));

    await waitFor(() => expect(createRating).toHaveBeenCalled());
    // 앞뒤 공백은 지우고, 공개 범위는 기본값을 쓰되 스포일러 선택은 보존한다
    expect(createRating).toHaveBeenCalledWith(550, {
      score: 3,
      comment: '밤에 보면 안 됨',
      visibility: 'PRIVATE',
      spoiler: true,
    });
  });

  it('추가 전 수치를 받지 못하면 저장하지 않고 알린다', async () => {
    vi.mocked(getRatingCounts)
      .mockReset()
      .mockRejectedValue(new ApiError(0, 'NETWORK_ERROR', '네트워크에 연결할 수 없습니다.'));

    renderPage();

    await rate(4.5);

    expect(await screen.findByRole('alert')).toHaveTextContent('네트워크에 연결할 수 없습니다.');
    expect(createRating).not.toHaveBeenCalled();
    expect(location()).toBe('/movies/550/rate');
    // 다시 누를 수 있다
    expect(screen.getByRole('button', { name: '평가 완료' })).toBeEnabled();
  });

  it('검증 실패는 서버 문구를 그대로 보여주고 이동하지 않는다', async () => {
    vi.mocked(createRating).mockRejectedValue(
      new ApiError(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다.'),
    );

    renderPage();

    await rate(4.5);

    expect(await screen.findByRole('alert')).toHaveTextContent('요청 값이 올바르지 않습니다.');
    expect(location()).toBe('/movies/550/rate');
  });

  it('이미 평가한 영화면 알리고 상세를 다시 읽어 수정 모드로 바꾼다', async () => {
    vi.mocked(createRating).mockRejectedValue(
      new ApiError(409, 'RATING_ALREADY_EXISTS', '이미 평가한 영화입니다.'),
    );
    // 다시 읽은 상세에는 다른 곳에서 만든 평가가 있다
    vi.mocked(getMovieDetail)
      .mockResolvedValueOnce(movie)
      .mockResolvedValue({
        ...movie,
        myRating: {
          ratingId: 91,
          score: 4,
          comment: null,
          visibility: 'PRIVATE',
          spoiler: false,
        },
      });

    renderPage();

    await rate(4.5);

    expect(await screen.findByRole('alert')).toHaveTextContent('이미 평가한 영화예요');
    expect(location()).toBe('/movies/550/rate');

    // 수정 모드의 안내 문구와 저장된 별점이 나타난다
    expect(await screen.findByText('평가를 고쳐도 프레임과 팝콘은 그대로예요')).toBeInTheDocument();
    expect(screen.getByRole('slider', { name: '별점' })).toHaveValue('4');
  });

  it('이미 평가한 영화는 API-036을 부르지 않고 수정으로 보낸다', async () => {
    vi.mocked(getMovieDetail).mockResolvedValue({
      ...movie,
      myRating: {
        ratingId: 91,
        score: 4,
        comment: '범인이 드러난다',
        visibility: 'PUBLIC',
        spoiler: true,
      },
    });

    renderPage();

    expect(
      await screen.findByRole('checkbox', { name: '스포일러가 포함되어 있어요' }),
    ).toBeChecked();
    // 새 평가와 구분되게 제목이 `평가 수정`이다(업무 S15P21E106-638)
    expect(screen.getByRole('heading', { level: 1, name: '평가 수정' })).toBeInTheDocument();
    // 이미 평가한 사람에게 `나중에 평가하기`는 말이 맞지 않는다(업무 S15P21E106-716)
    expect(screen.queryByRole('button', { name: '나중에 평가하기' })).not.toBeInTheDocument();
    await rate(2.5, '범인이 드러난다');

    await waitFor(() =>
      expect(updateRating).toHaveBeenCalledWith(91, {
        score: 2.5,
        comment: '범인이 드러난다',
        visibility: 'PUBLIC',
        spoiler: true,
      }),
    );
    expect(createRating).not.toHaveBeenCalled();
    // 수정은 프레임을 만들지 않으므로 수치를 받지 않는다
    expect(getRatingCounts).not.toHaveBeenCalled();
  });

  /*
   * 별점만 저장한 평가는 `visibility`가 `null`로 온다(API-030 처리 규칙). 그 값을 그대로
   * 실어 보내면 `comment`가 있는데 `visibility`가 없어 서버가 `400`으로 거절했다
   * (업무 S15P21E106-763의 차단 결함). 공개 범위를 고르는 UI가 2.5에 없으므로 생성 경로와
   * 같은 기본값 `PRIVATE`로 채운다.
   */
  it('한줄평이 없던 평가에 한줄평을 붙이면 공개 범위를 PRIVATE로 채워 보낸다', async () => {
    vi.mocked(getMovieDetail).mockResolvedValue({
      ...movie,
      myRating: {
        ratingId: 91,
        score: 4,
        comment: null,
        visibility: null,
        spoiler: false,
      },
    });

    renderPage();

    await screen.findByRole('heading', { level: 1, name: '평가 수정' });
    await rate(4, '한 번 더 보고 싶다');

    await waitFor(() =>
      expect(updateRating).toHaveBeenCalledWith(91, {
        score: 4,
        comment: '한 번 더 보고 싶다',
        visibility: 'PRIVATE',
        spoiler: false,
      }),
    );
  });
});

/*
 * 평가 삭제 (업무 S15P21E106-724)
 *
 * **수정일 때만 지울 수 있다.** 아직 없는 평가를 지울 수는 없다.
 *
 * 되돌릴 수 없는 동작이라 확인 창을 한 번 거치고, 지운 뒤에는 저장했을 때와 같이 온 곳으로
 * 돌아간다. 404는 오류가 아니다 — 다른 곳에서 이미 지웠다는 뜻이라 사용자가 바라던 결과가
 * 이미 이루어져 있다(API-038 처리 규칙).
 */
describe('2.5 평가 삭제', () => {
  /** 이미 평가한 영화로 들어온다 */
  function ratedMovie() {
    vi.mocked(getMovieDetail).mockResolvedValue({
      ...movie,
      myRating: {
        ratingId: 91,
        score: 4,
        comment: null,
        visibility: 'PRIVATE',
        spoiler: false,
      },
    });
  }

  it('새 평가에는 삭제가 없다', async () => {
    renderPage();

    expect(await screen.findByRole('button', { name: '나중에 평가하기' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '평가 삭제' })).not.toBeInTheDocument();
  });

  it('확인 창을 거쳐 API-038로 지우고 온 곳으로 돌아간다', async () => {
    ratedMovie();
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: '평가 삭제' }));

    // 무엇까지 사라지는지 그 자리에서 말한다
    const dialog = await screen.findByRole('alertdialog');
    expect(dialog).toHaveTextContent('평가를 삭제할까요?');
    // 본 기록까지 되돌아간다는 것을 그 자리에서 말한다(ADR-0025)
    expect(dialog).toHaveTextContent('본 영화 목록에서도 빠지고');
    expect(screen.getByText(/프레임과 팝콘 한 알도 함께 빠져요/)).toBeInTheDocument();
    expect(deleteRating).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: '삭제' }));

    await waitFor(() => expect(deleteRating).toHaveBeenCalledWith(91));
    expect(updateRating).not.toHaveBeenCalled();
  });

  it('확인 창에서 취소하면 지우지 않는다', async () => {
    ratedMovie();
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: '평가 삭제' }));
    fireEvent.click(await screen.findByRole('button', { name: '취소' }));

    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument());
    expect(deleteRating).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: '평가 삭제' })).toBeInTheDocument();
  });

  /* 다른 곳에서 이미 지운 평가다. 사용자가 바라던 결과가 이미 이루어져 있다 */
  it('이미 지워진 평가(404)는 오류로 남기지 않는다', async () => {
    ratedMovie();
    vi.mocked(deleteRating).mockRejectedValue(
      new ApiError(404, 'RESOURCE_NOT_FOUND', '평가를 찾을 수 없어요'),
    );

    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: '평가 삭제' }));
    fireEvent.click(await screen.findByRole('button', { name: '삭제' }));

    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument());
    expect(screen.queryByText('평가를 찾을 수 없어요')).not.toBeInTheDocument();
  });

  it('지우지 못하면 화면에 남아 실패를 알린다', async () => {
    ratedMovie();
    vi.mocked(deleteRating).mockRejectedValue(
      new ApiError(500, 'INTERNAL_ERROR', '잠시 후 다시 시도해 주세요'),
    );

    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: '평가 삭제' }));
    fireEvent.click(await screen.findByRole('button', { name: '삭제' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('잠시 후 다시 시도해 주세요');
    // 화면에 남아 다시 누를 수 있다
    expect(screen.getByRole('button', { name: '평가 삭제' })).toBeInTheDocument();
  });
});

describe('한줄평 공개 범위', () => {
  it('새 한줄평은 선택한 공개 값으로 저장한다', async () => {
    renderPage();
    await screen.findByRole('slider', { name: '별점' });
    expect(screen.getByRole('radio', { name: '공개' })).toBeDisabled();
    fireEvent.change(screen.getByLabelText('한줄평'), { target: { value: '좋아요' } });
    expect(screen.getByRole('radio', { name: '비공개' })).toBeChecked();
    fireEvent.click(screen.getByRole('radio', { name: '공개' }));
    await rate(4, '좋아요');
    await waitFor(() =>
      expect(createRating).toHaveBeenCalledWith(550, {
        score: 4,
        comment: '좋아요',
        visibility: 'PUBLIC',
        spoiler: false,
      }),
    );
  });

  it.each(['PUBLIC', 'PRIVATE'] as const)(
    '수정에서 %s를 유지하고 반대 값으로 변경한다',
    async (original) => {
      vi.mocked(getMovieDetail).mockResolvedValue({
        ...movie,
        myRating: {
          ratingId: 91,
          score: 4,
          comment: '좋아요',
          visibility: original,
          spoiler: false,
        },
      });
      renderPage();
      await screen.findByRole('heading', { name: '평가 수정' });
      expect(
        screen.getByRole('radio', { name: original === 'PUBLIC' ? '공개' : '비공개' }),
      ).toBeChecked();
      const next = original === 'PUBLIC' ? 'PRIVATE' : 'PUBLIC';
      fireEvent.click(screen.getByRole('radio', { name: next === 'PUBLIC' ? '공개' : '비공개' }));
      await rate(4);
      await waitFor(() =>
        expect(updateRating).toHaveBeenCalledWith(91, {
          score: 4,
          comment: '좋아요',
          visibility: next,
          spoiler: false,
        }),
      );
    },
  );
});
