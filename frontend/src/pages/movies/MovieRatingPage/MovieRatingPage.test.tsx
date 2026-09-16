/*
 * 2.5 영화 평가 — 저장 (업무 S15P21E106-491)
 *
 * 고정하는 것은 세 가지다.
 *
 * - **저장에 성공하면 API-036 응답과 추가 전 수치를 그대로 2.6에 넘긴다.** 2.6 · 2.7은
 *   Router state만 보고 그리므로, 여기서 빠진 값은 그 화면에서 지어낼 수 없다
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
import { ApiError } from '../../../api/apiError';
import { ROUTE_PATTERN } from '../../../routes/paths';
import type { MovieDetail } from '../../../types/movie';
import type { Rating } from '../../../types/watch';

vi.mock('../../../api/movies', () => ({ getMovieDetail: vi.fn() }));
vi.mock('../../../api/otts', () => ({ getOtts: vi.fn() }));
vi.mock('../../../api/watch', () => ({
  createRating: vi.fn(),
  findWatchedRecord: vi.fn(),
  getCountsBeforeRating: vi.fn(),
  updateRating: vi.fn(),
}));

const { getMovieDetail } = await import('../../../api/movies');
const { getOtts } = await import('../../../api/otts');
const { createRating, findWatchedRecord, getCountsBeforeRating, updateRating } =
  await import('../../../api/watch');

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
  frame: { frameId: 77, createdAt: '2026-08-30T05:21:00Z' },
  popcorn: { flavorCode: 'FLAVOR_A', isNewFlavor: true, flavorCount: 1 },
  watchStatus: 'RATED',
  createdAt: '2026-08-30T05:21:00Z',
};

const counts = { frameCount: 12, popcornCount: 7, weeklyFrameDelta: 3 };

beforeEach(() => {
  vi.mocked(getMovieDetail).mockReset().mockResolvedValue(movie);
  vi.mocked(getOtts).mockReset().mockResolvedValue([]);
  vi.mocked(findWatchedRecord).mockReset().mockResolvedValue(null);
  vi.mocked(getCountsBeforeRating).mockReset().mockResolvedValue(counts);
  vi.mocked(createRating).mockReset().mockResolvedValue(created);
  vi.mocked(updateRating).mockReset().mockResolvedValue(undefined);
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

  it('한줄평이 있으면 공개 범위 기본값 PRIVATE을 붙여 보낸다', async () => {
    renderPage();

    await rate(3, '  밤에 보면 안 됨  ');

    await waitFor(() => expect(createRating).toHaveBeenCalled());
    // 앞뒤 공백은 지우고, 선택 UI가 없어 공개 범위는 기본값이다(CR-05)
    expect(createRating).toHaveBeenCalledWith(550, {
      score: 3,
      comment: '밤에 보면 안 됨',
      visibility: 'PRIVATE',
    });
  });

  it('추가 전 수치를 받지 못하면 저장하지 않고 알린다', async () => {
    vi.mocked(getCountsBeforeRating).mockRejectedValue(
      new ApiError(0, 'NETWORK_ERROR', '네트워크에 연결할 수 없습니다.'),
    );

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
        myRating: { ratingId: 91, score: 4, comment: null, visibility: 'PRIVATE' },
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
      myRating: { ratingId: 91, score: 4, comment: null, visibility: 'PUBLIC' },
    });

    renderPage();

    await rate(2.5);

    await waitFor(() =>
      expect(updateRating).toHaveBeenCalledWith(91, {
        score: 2.5,
        comment: null,
        visibility: 'PUBLIC',
      }),
    );
    expect(createRating).not.toHaveBeenCalled();
  });
});
