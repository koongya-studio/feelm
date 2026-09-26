/*
 * MY 피드 — 이동 경로 (업무 S15P21E106-495)
 *
 * 7.5 추천 기록 · 7.6 평가한 영화 · 7.7 평가하지 않은 영화 화면을 지우고 7.1 MY 안의 탭으로
 * 옮겼다. **그 화면들이 걷던 사슬을 피드 위에서 그대로 걷는다.** 306에서 만든
 * `MyRecordsFlow.test.tsx`가 잡던 자리이고, 화면이 사라졌을 뿐 확인할 것은 같다.
 *
 * - 피드(평가) ⇢ 2.4 → 2.5: 이미 평가한 영화를 고치면 **평가가 늘면 안 된다**. 피드는 2.4 영화
 *   상세로 가고, 수정은 2.4의 `내 별점 수정하기`로 들어간다(업무 -638)
 * - 피드(최근 본 영화) ⇢ 2.5: 평가하면 API-036으로 저장되고, 다시 받은 서버 목록에 남는다
 *
 * 실제 호출인 도메인만 가로챈다 — API-014 내 프로필(`api/users`), OTT 기준정보(`api/otts`),
 * 팝콘과 맛 기준정보(`api/taste`), 영화 상세(`api/movies`, 업무 -475), 감상 기록 · 평가 생성 ·
 * 내 평가 목록(`api/watch`, 업무 -477 · -491 · -611)이다. 나머지는 목업이라 그대로 둔다. 영화 상세는 목업 평가
 * 저장소의 현재 값을 읽는 대역을 세운다 — 2.5가 이미 평가한 영화인지 이 응답으로 판단하기
 * 때문이다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import MyDashboardPage from './index';
import MovieDetailPage from '../../movies/MovieDetailPage';
import MovieRatingPage from '../../movies/MovieRatingPage';
import RatingResultPage from '../../movies/RatingResultPage';
import { getMyProfile, getMyProfileView } from '../../../api/users';
import { mockFrameList } from '../../../mocks/frames';
import { findMockMovieDetail } from '../../../mocks/movieDetails';
import { findMockMovie } from '../../../mocks/movies';
import { mockOtts } from '../../../mocks/otts';
import { resetSessionStore } from '../../../mocks/sessionStore';
import { mockMyProfileView } from '../../../mocks/publicProfiles';
import { mockMyProfile } from '../../../mocks/user';
import { AuthContext } from '../../../contexts/authContext';
import { ROUTE_PATTERN } from '../../../routes/paths';
import type { MovieDetail } from '../../../types/movie';
import type {
  MyRatedItem,
  MyRatingList,
  MyRatingListItem,
  RatingUpdateInput,
  WatchRecordListItem,
} from '../../../types/watch';

/**
 * 서버가 들고 있는 평가 수정 결과의 대역. 화면이 API-037로 보낸 값을 다음 조회가 돌려준다.
 * 목업 세션 저장소에서 지웠으므로(업무 -610) 시험이 스스로 든다.
 */
const ratingEdits = new Map<number, RatingUpdateInput>();

function saveRatingEdit(ratingId: number, input: RatingUpdateInput) {
  ratingEdits.set(ratingId, { ...input });
}

/** 기준 평가. 영화 상세 대역(`mocks/movieDetails`)의 내 평가다 */
function myRatingOf(movieId: number) {
  const rating = findMockMovieDetail(movieId)?.myRating;

  if (!rating) throw new Error(`기준 평가가 없는 영화다: ${movieId}`);

  return rating;
}

function withRatingEdit<T extends RatingUpdateInput & { ratingId: number }>(rating: T): T {
  const edit = ratingEdits.get(rating.ratingId);

  return edit ? { ...rating, ...edit } : rating;
}

// 7.1은 프로필 전체(`getMyProfileView`)를, 2.6 평가 완료는 수치(`getMyProfile`)를 읽는다
vi.mock('../../../api/users', () => ({
  getMyProfile: vi.fn(),
  getMyProfileView: vi.fn(),
}));

vi.mock('../../../api/otts', () => ({
  getOtts: vi.fn(),
}));

vi.mock('../../../api/taste', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/taste')>()),
  getPopcornBucket: vi.fn(),
  getPopcornFlavors: vi.fn(),
  getMyTaste: vi.fn().mockResolvedValue({
    ratingCount: 0,
    insufficientData: true,
    analysis: { genres: [] },
    keywords: [],
    analyzedAt: null,
  }),
}));

vi.mock('../../../api/movies', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/movies')>()),
  getMovieDetail: vi.fn(),
  // 2.4의 비슷한 영화 · 한줄평. 이 흐름에서 보는 것이 아니라 비워 둔다
  getSimilarMovies: vi.fn(() => Promise.resolve([])),
  getMovieReviews: vi.fn(() => Promise.resolve({ items: [], nextCursor: null, hasNext: false })),
}));

vi.mock('../../../api/watch', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/watch')>()),
  getWatchRecords: vi.fn(),
  // 7.1 `평가한 영화` 탭의 원천이다(API-039, 업무 -611)
  getMyRatings: vi.fn(),
  // 2.5의 감상 정보 줄이 쓴다. 모듈 안에서 부르는 함수라 따로 세워야 대역이 걸린다
  findWatchedRecord: vi.fn(),
  // 평가 생성(API-036)과 수정(API-037)은 실제 호출이므로 화면 흐름에서는 대역을 세운다
  createRating: vi.fn(),
  updateRating: vi.fn((ratingId, input) => {
    saveRatingEdit(ratingId, input);
    return Promise.resolve({});
  }),
}));

const { getOtts } = await import('../../../api/otts');
const { getPopcornBucket, getPopcornFlavors } = await import('../../../api/taste');
const { getMovieDetail } = await import('../../../api/movies');
const { getWatchRecords, getMyRatings, findWatchedRecord, createRating, updateRating } =
  await import('../../../api/watch');

/**
 * 서버가 들고 있는 감상 기록(API-034). `최근 본 영화` 탭이 그리는 세 건이다.
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

/**
 * 내 평가 목록(API-039)을 주는 서버 대역.
 *
 * **`filter=ALL`이면 미평가까지 합쳐 준다**(업무 -919). `최근 본 영화` 탭이 그렇게 부른다.
 * 서버가 하는 대로 같은 영화를 한 번만 두고(평가가 있으면 평가 쪽), `activityAt` 내림차순으로
 * 세운다. 필터가 없으면 평가만이다 — `평가한 영화` 탭이다.
 */
function myRatings(query: { filter?: string } = {}): Promise<MyRatingList> {
  const saved = mockFrameList.items.map((frame): MyRatedItem => {
    const rating = withRatingEdit(myRatingOf(frame.movie.movieId));

    return {
      itemType: 'RATED',
      ratingId: rating.ratingId,
      movie: frame.movie,
      score: rating.score,
      comment: rating.comment,
      visibility: rating.visibility,
      spoiler: rating.spoiler,
      frameId: frame.frameId,
      createdAt: frame.createdAt,
      activityAt: frame.createdAt,
    };
  });

  const rated = [...createdRatings, ...saved];

  if (query.filter !== 'ALL') {
    return Promise.resolve({ items: rated, nextCursor: null, hasNext: false });
  }

  const ratedMovieIds = new Set(rated.map((item) => item.movie.movieId));
  const items = [...rated, ...unratedItems.filter((item) => !ratedMovieIds.has(item.movie.movieId))]
    .slice()
    .sort((a, b) => (b.activityAt ?? '').localeCompare(a.activityAt ?? ''));

  return Promise.resolve({ items, nextCursor: null, hasNext: false });
}

/** 위 감상 기록을 API-039의 미평가 항목 모양으로 바꾼 것 */
const unratedItems: MyRatingListItem[] = unratedRecords.map((record) => ({
  itemType: 'UNRATED',
  ratingId: null,
  movie: record.movie,
  score: null,
  comment: null,
  visibility: null,
  spoiler: null,
  frameId: null,
  createdAt: null,
  activityAt: record.watchedAt,
}));

/** 화면이 API-036으로 만든 평가. 서버가 저장했다고 보고 다음 조회에 돌려준다 */
const createdRatings: MyRatedItem[] = [];

beforeEach(() => {
  resetSessionStore();
  ratingEdits.clear();
  createdRatings.length = 0;
  vi.mocked(getMyRatings).mockReset().mockImplementation(myRatings);
  vi.mocked(getMyProfileView).mockReset().mockResolvedValue(mockMyProfileView);
  vi.mocked(getMyProfile).mockReset().mockResolvedValue(mockMyProfile);
  vi.mocked(getOtts).mockReset().mockResolvedValue(mockOtts);
  // 팝콘 통은 그림이라 비어 있어도 화면이 그려진다
  vi.mocked(getPopcornBucket).mockReset().mockResolvedValue({ totalCount: 0, flavors: [] });
  vi.mocked(getPopcornFlavors).mockReset().mockResolvedValue([]);
  vi.mocked(getMovieDetail).mockReset().mockImplementation(movieDetail);
  vi.mocked(getWatchRecords)
    .mockReset()
    .mockResolvedValue({ items: unratedRecords, nextCursor: null, hasNext: false });
  vi.mocked(findWatchedRecord)
    .mockReset()
    .mockImplementation((movieId: number) =>
      Promise.resolve(unratedRecords.find((record) => record.movie.movieId === movieId) ?? null),
    );
  vi.mocked(createRating).mockReset();
  vi.mocked(updateRating).mockClear();
});

function LocationProbe() {
  const location = useLocation();

  return <div data-testid="location">{location.pathname}</div>;
}

/** 2.4가 세션 복구를 기다린다. 이 흐름은 로그인한 사용자다 */
const auth = {
  status: 'authenticated',
  user: null,
  signIn: vi.fn(),
  signInWithSocial: vi.fn(),
  completeEmailSignup: vi.fn(),
  completeSocialSignup: vi.fn(),
  signOut: vi.fn(),
} as const;

function renderApp(entries: string[]) {
  return render(
    <MemoryRouter initialEntries={entries} initialIndex={entries.length - 1}>
      <Routes>
        <Route path={ROUTE_PATTERN.my} element={<MyDashboardPage />} />
        <Route
          path={ROUTE_PATTERN.movieDetail}
          element={
            <AuthContext.Provider value={auth}>
              <MovieDetailPage />
            </AuthContext.Provider>
          }
        />
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

describe('MY 피드(평가) ⇢ 2.4 → 2.5', () => {
  it('기존 평가를 고치면 피드에 반영되고 평가가 늘지 않는다', async () => {
    renderApp([ROUTE_PATTERN.my]);

    // 402는 별점 4.0에 한줄평이 있는 기준 평가다. 피드는 2.4 영화 상세로 간다(업무 -638)
    const cell = await screen.findByRole('link', { name: /영화 제목 2/ });
    expect(cell).toHaveAttribute('href', '/movies/402');
    fireEvent.click(cell);

    // 평가 수정은 2.4의 `내 별점 수정하기`로 이어진다
    fireEvent.click(await screen.findByRole('button', { name: '내 별점 수정하기' }));
    await waitFor(() => expect(location()).toBe('/movies/402/rate'));

    await setScore(2.5);
    fireEvent.click(screen.getByRole('button', { name: '평가 완료' }));

    // 수정이라 2.6이 아니라 온 곳(2.4)으로 돌아오고, 2.4에서 뒤로 가면 MY다
    await waitFor(() => expect(location()).toBe('/movies/402'));
    fireEvent.click(await screen.findByRole('button', { name: '뒤로 가기' }));
    await waitFor(() => expect(location()).toBe('/my'));
    expect(await screen.findByRole('link', { name: /영화 제목 2.*2\.5/ })).toBeInTheDocument();

    // 같은 평가가 고쳐졌을 뿐 새 평가가 생기지 않았다
    const list = await getMyRatings();
    expect(list.items.filter((item) => item.movie.movieId === 402)).toEqual([
      expect.objectContaining({ ratingId: 902, score: 2.5 }),
    ]);
  });
});

describe('MY 피드(최근 본 영화) ⇢ 2.5', () => {
  /*
   * 이 탭은 서버 기록(API-034)이고 평가 생성도 서버(API-036)다(업무 -491). 평가를 저장하면
   * 서버가 같은 트랜잭션에서 기록을 `RATED`로 옮긴다. 화면이 할 일은 **API-036을 부르고
   * 돌아와서 목록을 다시 받는 것**뿐이다. 그 두 자리를 고정한다.
   *
   * **업무 -919에서 기대가 바뀌었다.** 탭이 `안 남긴 영화`였을 때는 평가한 영화가 목록에서
   * 빠지는 것이 맞았다. 지금은 `최근 본 영화`라 `RATED`도 함께 받으므로 **그대로 남는다.**
   * 본 영화인 것은 변함이 없기 때문이다.
   *
   * 평가한 영화(API-039)도 서버 목록이라(업무 -611) 돌아와서 다시 받으면 방금 저장한 평가가
   * 보인다. 그것까지 이어서 본다.
   */
  it('평가하면 API-036으로 저장하고, 최근 본 영화에는 그대로 남는다', async () => {
    vi.mocked(createRating).mockImplementation((movieId, input) => {
      const movie = findMockMovie(movieId);

      createdRatings.push({
        itemType: 'RATED',
        ratingId: 91,
        movie: { movieId, title: movie.title, posterUrl: movie.posterUrl },
        score: input.score,
        comment: null,
        visibility: null,
        spoiler: false,
        frameId: 77,
        createdAt: '2026-08-30T05:21:00Z',
        activityAt: '2026-08-30T05:21:00Z',
      });

      return Promise.resolve({
        ratingId: 91,
        movieId,
        score: input.score,
        comment: input.comment ?? null,
        visibility: input.visibility ?? 'PRIVATE',
        spoiler: false,
        frame: { frameId: 77, createdAt: '2026-08-30T05:21:00Z' },
        popcorn: { flavorCode: 'FLAVOR_A', isNewFlavor: true, flavorCount: 1 },
        watchStatus: 'RATED',
        createdAt: '2026-08-30T05:21:00Z',
      });
    });

    const { unmount } = renderApp([ROUTE_PATTERN.my]);

    fireEvent.click(await findTab('최근 본 영화'));

    const cell = await screen.findByRole('link', { name: /영화 제목 5/ });
    fireEvent.click(cell);
    expect(location()).toBe('/movies/411/rate');

    // 2.5가 감상 정보 줄을 이 기록에서 읽는다
    expect(await screen.findByText('8월 26일 · 넷플릭스에서 시청')).toBeInTheDocument();

    await setScore(4);
    fireEvent.click(screen.getByRole('button', { name: '평가 완료' }));

    // 새 평가라서 API-036으로 저장하고 2.6으로 간다
    await waitFor(() => expect(location()).toBe('/movies/411/rate/result'));
    expect(createRating).toHaveBeenCalledWith(411, { score: 4 });
    unmount();

    /*
     * 서버 대역이 이미 안다 — 411에 평가가 생겼으므로 `filter=ALL`에서 그 영화는 미평가가 아니라
     * 평가 항목으로 한 번만 나온다. 목록에서 사라지지 않는 것이 이번 변경의 요지다.
     */
    renderApp([`${ROUTE_PATTERN.my}?tab=unrated`]);
    await findTab('최근 본 영화');
    expect(await screen.findByRole('link', { name: /영화 제목 6/ })).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: /영화 제목 5/ })).toHaveLength(1);

    // 평가한 영화 탭에는 방금 저장한 평가가 맨 앞에 보인다(API-039 다시 받기)
    fireEvent.click(await findTab('평가한 영화'));
    const [first] = await screen.findAllByRole('link', { name: /영화 제목/ });
    expect(first).toHaveAccessibleName(expect.stringMatching(/영화 제목 5.*4\.0/));
  });
});
