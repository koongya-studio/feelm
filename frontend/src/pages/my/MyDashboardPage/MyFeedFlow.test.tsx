/*
 * MY 피드 — 이동 경로 (업무 S15P21E106-495)
 *
 * 7.5 추천 기록 · 7.6 평가한 영화 · 7.7 안 남긴 영화 화면을 지우고 7.1 MY 안의 탭으로
 * 옮겼다. **그 화면들이 걷던 사슬을 피드 위에서 그대로 걷는다.** 306에서 만든
 * `MyRecordsFlow.test.tsx`가 잡던 자리이고, 화면이 사라졌을 뿐 확인할 것은 같다.
 *
 * - 피드(평가) ⇢ 2.5: 이미 평가한 영화를 고치면 **평가가 늘면 안 된다**
 * - 피드(안 남긴 영화) ⇢ 2.5: 평가하고 돌아오면 그 탭에서 빠지고 평가 탭 맨 위에 선다
 *
 * 실제 호출인 도메인만 가로챈다 — API-014 내 프로필(`api/users`), OTT 기준정보(`api/otts`),
 * 팝콘(`api/taste`), 영화 상세(`api/movies`, 업무 -475), 감상 기록(`api/watch`, 업무 -477)이다.
 * 나머지는 목업이라 그대로 둔다. 영화 상세는 목업 평가 저장소의 현재 값을 읽는 대역을
 * 세운다 — 2.5가 이미 평가한 영화인지 이 응답으로 판단하기 때문이다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import MyDashboardPage from './index';
import MovieRatingPage from '../../movies/MovieRatingPage';
import RatingResultPage from '../../movies/RatingResultPage';
import { getMyRatings } from '../../../api/watch';
import { getMyProfile } from '../../../api/users';
import { findMockMovieDetail } from '../../../mocks/movieDetails';
import { findMockMovie } from '../../../mocks/movies';
import { mockOtts } from '../../../mocks/otts';
import { resetSessionStore, withRatingEdit } from '../../../mocks/sessionStore';
import { mockMyProfile } from '../../../mocks/user';
import { ROUTE_PATTERN } from '../../../routes/paths';
import type { MovieDetail } from '../../../types/movie';
import type { WatchRecordListItem } from '../../../types/watch';

vi.mock('../../../api/users', () => ({
  getMyProfile: vi.fn(),
}));

vi.mock('../../../api/otts', () => ({
  getOtts: vi.fn(),
}));

vi.mock('../../../api/taste', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/taste')>()),
  getPopcornBucket: vi.fn(),
}));

vi.mock('../../../api/movies', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/movies')>()),
  getMovieDetail: vi.fn(),
}));

vi.mock('../../../api/watch', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/watch')>()),
  getWatchRecords: vi.fn(),
  // 2.5의 감상 정보 줄이 쓴다. 모듈 안에서 부르는 함수라 따로 세워야 대역이 걸린다
  findWatchedRecord: vi.fn(),
}));

const { getOtts } = await import('../../../api/otts');
const { getPopcornBucket } = await import('../../../api/taste');
const { getMovieDetail } = await import('../../../api/movies');
const { getWatchRecords, findWatchedRecord } = await import('../../../api/watch');

/**
 * 서버가 들고 있는 감상 기록(API-034). `안 남긴 영화` 탭이 그리는 세 건이다.
 *
 * 와이어프레임 7.7의 날짜와 OTT를 옮겼다. 감상 기록이 실제 API가 되면서(업무 -477)
 * 목업 세션 저장소가 아니라 서버 대역이 준다.
 */
function watchRecord(
  watchRecordId: number,
  movieId: number,
  ottId: number,
  day: string,
): WatchRecordListItem {
  const movie = findMockMovie(movieId);
  const at = `2026-08-${day}T12:00:00Z`;

  return {
    watchRecordId,
    movie: { movieId, title: movie.title, posterUrl: movie.posterUrl },
    status: 'WATCHED_UNRATED',
    ott: { ottId, code: mockOtts.find((ott) => ott.ottId === ottId)?.code ?? '' },
    clickedAt: at,
    confirmDueAt: at,
    expiresAt: at,
    watchedAt: at,
    ratingId: null,
  };
}

const unratedRecords = [
  watchRecord(4001, 411, 1, '26'),
  watchRecord(4002, 412, 2, '20'),
  watchRecord(4003, 413, 3, '14'),
];

/** 영화 상세를 주는 서버 대역. 목업 평가 저장소의 현재 값을 읽는다 */
function movieDetail(movieId: number): Promise<MovieDetail> {
  const detail = findMockMovieDetail(movieId);

  if (!detail) {
    return Promise.reject(new Error('없는 영화'));
  }

  return Promise.resolve({
    ...detail,
    myRating: detail.myRating && withRatingEdit(detail.myRating),
  });
}

beforeEach(() => {
  resetSessionStore();
  vi.mocked(getMyProfile).mockReset().mockResolvedValue(mockMyProfile);
  vi.mocked(getOtts).mockReset().mockResolvedValue(mockOtts);
  // 팝콘 통은 그림이라 비어 있어도 화면이 그려진다
  vi.mocked(getPopcornBucket).mockReset().mockResolvedValue({ totalCount: 0, flavors: [] });
  vi.mocked(getMovieDetail).mockReset().mockImplementation(movieDetail);
  vi.mocked(getWatchRecords)
    .mockReset()
    .mockResolvedValue({ items: unratedRecords, nextCursor: null, hasNext: false });
  vi.mocked(findWatchedRecord)
    .mockReset()
    .mockImplementation((movieId: number) =>
      Promise.resolve(unratedRecords.find((record) => record.movie.movieId === movieId) ?? null),
    );
});

function LocationProbe() {
  const location = useLocation();

  return <div data-testid="location">{location.pathname}</div>;
}

function renderApp(entries: string[]) {
  return render(
    <MemoryRouter initialEntries={entries} initialIndex={entries.length - 1}>
      <Routes>
        <Route path={ROUTE_PATTERN.my} element={<MyDashboardPage />} />
        <Route path={ROUTE_PATTERN.movieRating} element={<MovieRatingPage />} />
        <Route path={ROUTE_PATTERN.ratingResult} element={<RatingResultPage />} />
      </Routes>
      <LocationProbe />
    </MemoryRouter>,
  );
}

const location = () => screen.getByTestId('location').textContent;

/** 피드는 프로필을 받은 뒤에 그려진다 */
async function findTab(name: string) {
  return screen.findByRole('tab', { name });
}

async function setScore(score: number) {
  const slider = await screen.findByRole('slider', { name: '별점' });
  fireEvent.change(slider, { target: { value: String(score) } });
}

describe('MY 피드(평가) ⇢ 2.5', () => {
  it('기존 평가를 고치면 피드에 반영되고 평가가 늘지 않는다', async () => {
    renderApp([ROUTE_PATTERN.my]);

    // 402는 별점 4.0에 한줄평이 있는 기준 평가다
    const cell = await screen.findByRole('link', { name: /영화 제목 2/ });
    expect(cell).toHaveAttribute('href', '/movies/402/rate');
    fireEvent.click(cell);

    await setScore(2.5);
    fireEvent.click(screen.getByRole('button', { name: '평가 완료' }));

    // 수정이라 2.6이 아니라 온 곳(MY)으로 돌아온다
    await waitFor(() => expect(location()).toBe('/my'));
    expect(await screen.findByRole('link', { name: /영화 제목 2.*2\.5/ })).toBeInTheDocument();

    const ratings = await getMyRatings();
    expect(ratings.items.filter((rating) => rating.movie.movieId === 402)).toEqual([
      expect.objectContaining({ frameId: 502, score: 2.5 }),
    ]);
  });
});

describe('MY 피드(안 남긴 영화) ⇢ 2.5', () => {
  /*
   * `[!]` **평가해도 안 남긴 영화에서 빠지지 않는다**(업무 -477). 이 탭은 서버 기록
   * (API-034)이고 평가는 아직 목업이라, 목업 평가가 서버 기록을 `RATED`로 옮길 길이 없다.
   * 평가 생성(API-036)이 연동되면 서버가 같은 트랜잭션에서 옮긴다. 지금 상태를 시험으로
   * 고정해 두는 것은 그 자리가 고쳐졌는지를 코드가 말하게 하기 위해서다.
   */
  it('평가하면 평가 탭 맨 위에 서지만 안 남긴 영화에는 그대로 남는다', async () => {
    const { unmount } = renderApp([ROUTE_PATTERN.my]);

    fireEvent.click(await findTab('안 남긴 영화'));

    const cell = await screen.findByRole('link', { name: /영화 제목 5/ });
    fireEvent.click(cell);
    expect(location()).toBe('/movies/411/rate');

    // 2.5가 감상 정보 줄을 이 기록에서 읽는다
    expect(await screen.findByText('8월 26일 · 넷플릭스에서 시청')).toBeInTheDocument();

    await setScore(4);
    fireEvent.click(screen.getByRole('button', { name: '평가 완료' }));

    // 새 평가라서 2.6으로 간다
    await waitFor(() => expect(location()).toBe('/movies/411/rate/result'));
    unmount();

    // 평가는 목업에 쌓였지만, 서버가 주는 안 남긴 영화 목록은 그대로다
    renderApp([`${ROUTE_PATTERN.my}?tab=unrated`]);
    await findTab('안 남긴 영화');
    expect(await screen.findByRole('link', { name: /영화 제목 5/ })).toBeInTheDocument();

    // 평가 탭 맨 위에 방금 평가가 있다
    fireEvent.click(await findTab('평가한 영화'));
    const cells = await screen.findAllByRole('listitem');
    expect(cells[0]).toHaveTextContent('영화 제목 5');
  });
});
