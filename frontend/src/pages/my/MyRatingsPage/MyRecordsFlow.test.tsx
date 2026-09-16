/*
 * MY 기록 목록 — 이동 경로 (업무 S15P21E106-306)
 *
 * 화면을 하나씩 열면 멀쩡하지만 **이어서 걸으면 끊기는 자리**를 잡는다.
 *
 * - 7.6 ⇢ 2.5: 이미 평가한 영화를 고치면 **프레임이 늘면 안 된다.** 306 전의 2.5는
 *   수정도 새 평가로 저장해 필름이 101프레임이 됐다
 * - 7.7 ⇢ 2.5: 7.7의 줄에서 평가 화면으로 이어지고, 2.5가 그 기록의 감상 정보를 읽는다
 * - 7.6 ⇢ 7.6-A: 시트에서 고친 한줄평이 목록과 평가 저장소에 함께 남아야 한다
 *
 * 화면은 실제 라우트 패턴으로 함께 건다(PartyFlow.test.tsx와 같은 방식).
 * 실제 호출인 도메인만 가로챈다 — API-014 내 프로필(`api/users`), 연동(-379) 뒤의
 * OTT 기준정보 · 구독(`api/otts`), 연동(-392) 뒤의 필름(`api/taste`), 연동(-475) 뒤의
 * 영화 상세(`api/movies`), 연동(-477) 뒤의 감상 기록(`api/watch`)이다. 나머지는 목업이라
 * 그대로 둔다.
 *
 * `api/watch`도 **일부만** 가로챈다. 평가 계열(`getMyRatings` · `createRating` ·
 * `updateRating`)은 아직 목업이라 그대로 둬야 평가 흐름이 이어진다. 감상 기록 조회만
 * 서버 대역으로 세운다.
 *
 * 영화 상세도 **일부만** 가로챈다. 유사 영화와 한줄평은 아직 목업이라 그대로 둔다.
 * 이 파일이 걷는 영화들은 목업 목록(7.5 · 7.6 · 7.7)의 영화라 실제 서버에 없으므로,
 * 목업 상세를 읽어 주는 스텁을 서버 대신 세운다. 실제 화면에서 같은 경로로 들어가면
 * `404`가 되는 것이 현재 한계다(`verification/S15P21E106-475.md`).
 *
 * `api/taste`는 **일부만** 가로챈다. 2.6이 쓰는 `getMyFrameCount` · `getMyPopcornCount`는
 * 목업 그대로 둬야 평가 흐름이 이어진다. 프레임 상세는 서버가 주는 값이므로, 목업 평가
 * 저장소를 읽어 상세를 만드는 스텁을 서버 대신 세운다 — 시트에서 고친 한줄평이 다시
 * 읽을 때 반영되는지가 이 파일이 잡으려는 자리다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import MyRatingsPage from './index';
import FrameDetailPage from '../../film/FrameDetailPage';
import MovieDetailPage from '../../movies/MovieDetailPage';
import MovieRatingPage from '../../movies/MovieRatingPage';
import RatingResultPage from '../../movies/RatingResultPage';
import MyDashboardPage from '../MyDashboardPage';
import RecommendationHistoryPage from '../RecommendationHistoryPage';
import { getMyRatings } from '../../../api/watch';
import { getMyProfile } from '../../../api/users';
import { mockFrameList, mockMyRatings } from '../../../mocks/frames';
import { findMockMovieDetail } from '../../../mocks/movieDetails';
import { findMockMovie } from '../../../mocks/movies';
import { mockOtts } from '../../../mocks/otts';
import { resetSessionStore, withRatingEdit } from '../../../mocks/sessionStore';
import { mockMyProfile } from '../../../mocks/user';
import { ROUTE_PATTERN } from '../../../routes/paths';
import type { MovieDetail } from '../../../types/movie';
import type { FrameDetail } from '../../../types/taste';
import type { WatchRecordListItem } from '../../../types/watch';

vi.mock('../../../api/users', () => ({
  getMyProfile: vi.fn(),
}));

vi.mock('../../../api/otts', () => ({
  getOtts: vi.fn(),
  getMyOttIds: vi.fn(),
  setMyOttIds: vi.fn(),
}));

vi.mock('../../../api/taste', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/taste')>()),
  getMyFrames: vi.fn(),
  getFrameDetail: vi.fn(),
}));

vi.mock('../../../api/movies', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/movies')>()),
  getMovieDetail: vi.fn(),
}));

vi.mock('../../../api/watch', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/watch')>()),
  getWatchRecords: vi.fn(),
  findWatchedRecord: vi.fn(),
}));

const { getOtts, getMyOttIds } = await import('../../../api/otts');
const { getMyFrames, getFrameDetail } = await import('../../../api/taste');
const { getMovieDetail } = await import('../../../api/movies');
const { getWatchRecords, findWatchedRecord } = await import('../../../api/watch');

/**
 * 서버가 들고 있는 감상 기록(API-034). 7.7이 그리는 `평가 안 남긴 영화` 세 건이다.
 *
 * 와이어프레임 7.7의 날짜와 OTT를 옮겼다(`8. 26 · 넷플릭스에서 시청` 등). 이전에는 같은
 * 값이 목업 세션 저장소에 있었지만, 감상 기록이 실제 API가 되면서(-477) 서버 대역으로
 * 옮겼다.
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

/** 프레임 상세를 주는 서버 대역. 목업 평가 저장소의 현재 값을 읽는다 */
function frameDetail(frameId: number): Promise<FrameDetail> {
  const frame = mockFrameList.items.find((item) => item.frameId === frameId);

  if (!frame) {
    return Promise.reject(new Error('없는 프레임'));
  }

  const movie = findMockMovie(frame.movie.movieId);

  return Promise.resolve({
    frameId,
    flavorCode: frame.flavorCode,
    movie: {
      movieId: movie.movieId,
      title: movie.title,
      posterUrl: movie.posterUrl,
      releaseYear: movie.releaseYear,
      genres: movie.genres,
      directors: [],
    },
    rating: withRatingEdit(mockMyRatings[frame.movie.movieId]),
    watchRecord: { ott: null, clickedAt: null, watchedAt: null, ratedAt: frame.createdAt },
  });
}

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
  vi.mocked(getMyOttIds).mockReset().mockResolvedValue([mockOtts[0].ottId, mockOtts[4].ottId]);
  // 평가 API가 없어 서버에는 프레임이 없다. 7.1 필름 카드는 비어 있는 쪽이 실제에 가깝다
  vi.mocked(getMyFrames)
    .mockReset()
    .mockResolvedValue({ totalCount: 0, items: [], nextCursor: null, hasNext: false });
  vi.mocked(getFrameDetail).mockReset().mockImplementation(frameDetail);
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
        <Route path={ROUTE_PATTERN.recommendationHistory} element={<RecommendationHistoryPage />} />
        <Route path={ROUTE_PATTERN.myRatings} element={<MyRatingsPage tab="rated" />} />
        <Route path={ROUTE_PATTERN.unratedMovies} element={<MyRatingsPage tab="unrated" />} />
        <Route path={ROUTE_PATTERN.movieDetail} element={<MovieDetailPage />} />
        <Route path={ROUTE_PATTERN.movieRating} element={<MovieRatingPage />} />
        <Route path={ROUTE_PATTERN.ratingResult} element={<RatingResultPage />} />
        <Route path={ROUTE_PATTERN.frameDetail} element={<FrameDetailPage />} />
      </Routes>
      <LocationProbe />
    </MemoryRouter>,
  );
}

const location = () => screen.getByTestId('location').textContent;

/** 탭 머리는 목록을 받은 뒤에 그려진다 */
async function findTab(name: string | RegExp) {
  const nav = await screen.findByRole('navigation', { name: '평가 기록' });

  return within(nav).findByRole('link', { name });
}

async function setScore(score: number) {
  const slider = await screen.findByRole('slider', { name: '별점' });
  fireEvent.change(slider, { target: { value: String(score) } });
}

describe('7.5 → 2.4', () => {
  it('추천 기록의 영화를 누르면 그 영화 상세로 간다', async () => {
    /*
     * **7.1에서 들어오던 사슬이었다.** 사용자 시안(2026-09-16)으로 추천 기록이 7.1 안의
     * 탭이 되면서 7.1 → 7.5 화살표가 없어졌다. 7.5 화면 자체는 남아 있고 이 사슬
     * (월 묶음 · 출처 표시 · 2.4로 이동)도 그대로라 화면에서 바로 시작한다.
     * 7.5에 닿을 길이 앱에 없는 것은 screen-spec-my.md 11장에 적었다.
     */
    renderApp([ROUTE_PATTERN.recommendationHistory]);

    // 월 제목과 출처 세 가지가 보인다
    expect(await screen.findByRole('heading', { name: '2026년 8월' })).toBeInTheDocument();
    expect(screen.getAllByText('취향 맞춤').length).toBeGreaterThan(0);
    expect(screen.getAllByText('새로운 맛 발견').length).toBeGreaterThan(0);
    expect(screen.getByText('파티 추천')).toBeInTheDocument();

    // 파티 추천 줄(104)은 추천 목업 밖의 영화라 상세가 따로 있어야 이어진다
    const partyRow = screen.getByText('파티 추천').closest('a');
    fireEvent.click(partyRow!);

    expect(location()).toBe('/movies/104');
    expect(await screen.findByText(/오래 묻어 둔 사건 기록/)).toBeInTheDocument();
  });
});

describe('7.6 ⇢ 2.5 → 7.6', () => {
  it('기존 평가를 고치면 목록에 반영되고 프레임은 늘지 않는다', async () => {
    // 위와 같다 — 7.1 → 7.6 화살표가 없어져 7.6에서 시작한다(시안 2026-09-16)
    renderApp([ROUTE_PATTERN.myRatings]);

    expect(await findTab('평가')).toHaveAttribute('aria-current', 'page');

    // 402는 별점 4.0에 한줄평이 있는 기준 평가다
    const row = screen.getByRole('link', { name: /영화 제목 2/ });
    expect(row).toHaveAttribute('href', '/movies/402/rate');
    fireEvent.click(row);

    await setScore(2.5);
    fireEvent.click(screen.getByRole('button', { name: '평가 완료' }));

    // 2.6 · 2.7이 아니라 온 곳(7.6)으로 돌아온다
    await waitFor(() => expect(location()).toBe('/my/ratings'));
    expect(await screen.findByRole('link', { name: /영화 제목 2.*2\.5/ })).toBeInTheDocument();

    /*
     * 평가가 늘지 않고 점수만 바뀌었다. 필름 프레임 수로 보던 자리인데, 필름이 실제
     * API로 바뀌어(-392) 목업 평가와 같은 곳을 보지 않는다. 평가 목록에서 확인한다.
     */
    const ratings = await getMyRatings();
    expect(ratings.items.filter((rating) => rating.movie.movieId === 402)).toEqual([
      expect.objectContaining({ frameId: 502, score: 2.5 }),
    ]);
    /*
     * 한줄평과 공개 범위는 그대로다. 영화 상세도 실제 API로 바뀌어(-475) 목업 평가를
     * 보지 않으므로, 2.4가 읽을 값 대신 그 값이 오는 곳을 직접 확인한다.
     */
    expect(withRatingEdit(mockMyRatings[402])).toEqual(
      expect.objectContaining({ score: 2.5, visibility: 'PUBLIC' }),
    );
  });
});

describe('7.6 → 7.7 ⇢ 2.5', () => {
  /*
   * `[!]` **평가해도 7.7에서 빠지지 않는다**(업무 -477). 7.7은 서버 기록(API-034)이고
   * 평가는 아직 목업이라, 목업 평가가 서버 기록을 `RATED`로 옮길 길이 없다. 평가 생성
   * (API-036)이 연동되면 서버가 같은 트랜잭션에서 옮기므로 이 시험은 그때
   * `7.7에서 빠진다`로 돌아온다. 지금 상태를 시험으로 고정해 두는 것은, 그 자리가
   * 고쳐졌는지 아닌지를 코드가 말하게 하기 위해서다.
   */
  it('7.7에서 평가하면 7.6 맨 위에 서지만 7.7에는 그대로 남는다', async () => {
    const { unmount } = renderApp([ROUTE_PATTERN.myRatings]);

    fireEvent.click(await findTab('안 남긴 영화'));
    expect(location()).toBe('/my/ratings/unrated');
    expect(await screen.findByText('8. 26 · 넷플릭스에서 시청')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('link', { name: '영화 제목 5 평가하기' }));
    expect(location()).toBe('/movies/411/rate');

    // 2.5가 감상 정보 줄을 이 기록에서 읽는다
    expect(await screen.findByText('8월 26일 · 넷플릭스에서 시청')).toBeInTheDocument();

    await setScore(4);
    fireEvent.click(screen.getByRole('button', { name: '평가 완료' }));

    // 새 평가라서 2.6 · 2.7로 간다
    await waitFor(() => expect(location()).toBe('/movies/411/rate/result'));
    unmount();

    renderApp([ROUTE_PATTERN.unratedMovies]);

    // 평가는 목업에 쌓였지만, 서버가 주는 7.7 목록은 그대로다
    expect(await screen.findByRole('link', { name: '영화 제목 5 평가하기' })).toBeInTheDocument();

    // 7.6 맨 위에 방금 평가가 있다
    fireEvent.click(await findTab('평가'));
    const list = await screen.findAllByRole('listitem');
    expect(list[0]).toHaveTextContent('영화 제목 5');
  });

  it('탭을 바꿔도 뒤로 가기는 7.1로 간다', async () => {
    renderApp([ROUTE_PATTERN.my, ROUTE_PATTERN.myRatings]);

    fireEvent.click(await findTab(/안 남긴 영화/));
    expect(location()).toBe('/my/ratings/unrated');

    fireEvent.click(screen.getByRole('button', { name: '뒤로 가기' }));
    await waitFor(() => expect(location()).toBe('/my'));
  });
});

describe('7.6 ⇢ 7.6-A', () => {
  it('한줄평을 고치고 공개 범위를 바꾸면 목록과 평가 저장소에 반영된다', async () => {
    renderApp([ROUTE_PATTERN.myRatings]);

    // 한줄평이 없는 평가(401 · 403)에는 관리 버튼이 없다
    expect(
      await screen.findByRole('button', { name: '영화 제목 2 한줄평 관리' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '영화 제목 1 한줄평 관리' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '영화 제목 2 한줄평 관리' }));

    const sheet = within(screen.getByRole('dialog', { name: '한줄평 관리' }));
    const input = sheet.getByLabelText('한줄평');
    expect(input).toHaveValue('마술 트릭 하나하나가 복선이라 두 번 보게 돼요.');

    fireEvent.change(input, { target: { value: '두 번째 볼 때 더 좋았어요' } });
    expect(sheet.getByText('15/200')).toBeInTheDocument();

    const toggle = sheet.getByRole('switch', { name: '공개 한줄평 · 다른 사용자에게 노출' });
    expect(toggle).toHaveAttribute('aria-checked', 'true');
    fireEvent.click(toggle);

    fireEvent.click(sheet.getByRole('button', { name: '수정 완료' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText(/두 번째 볼 때 더 좋았어요/)).toBeInTheDocument();

    expect(withRatingEdit(mockMyRatings[402])).toEqual({
      ratingId: 902,
      score: 4,
      comment: '두 번째 볼 때 더 좋았어요',
      visibility: 'PRIVATE',
    });
  });

  it('삭제는 확인을 거쳐 한줄평만 지운다', async () => {
    renderApp([ROUTE_PATTERN.myRatings]);

    fireEvent.click(await screen.findByRole('button', { name: '영화 제목 2 한줄평 관리' }));
    fireEvent.click(
      within(screen.getByRole('dialog', { name: '한줄평 관리' })).getByRole('button', {
        name: '삭제',
      }),
    );

    const confirm = within(screen.getByRole('alertdialog', { name: '한줄평을 삭제할까요?' }));

    // 취소하면 아무것도 지우지 않고 시트로 돌아간다
    fireEvent.click(confirm.getByRole('button', { name: '취소' }));
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(withRatingEdit(mockMyRatings[402]).comment).not.toBeNull();

    fireEvent.click(
      within(screen.getByRole('dialog', { name: '한줄평 관리' })).getByRole('button', {
        name: '삭제',
      }),
    );
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '삭제' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: '영화 제목 2 한줄평 관리' })).toBeNull(),
    );

    // 평가는 남는다. 별점도 그대로다
    expect(withRatingEdit(mockMyRatings[402])).toEqual(
      expect.objectContaining({ score: 4, comment: null }),
    );
  });
});

describe('7.6-A 확인 창', () => {
  it('Escape는 삭제 확인 창만 닫고 시트는 남긴다', async () => {
    /*
     * 307에서 확인 창을 공용 ConfirmDialog로 꺼냈다. 시트도 Escape로 닫히므로 두 처리가
     * 같은 키를 받으면 시트까지 한꺼번에 닫혀 고치던 한줄평을 잃는다.
     */
    renderApp([ROUTE_PATTERN.myRatings]);

    fireEvent.click(await screen.findByRole('button', { name: '영화 제목 2 한줄평 관리' }));
    fireEvent.click(
      within(screen.getByRole('dialog', { name: '한줄평 관리' })).getByRole('button', {
        name: '삭제',
      }),
    );
    expect(screen.getByRole('alertdialog')).toBeInTheDocument();

    fireEvent.keyDown(window, { key: 'Escape' });

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: '한줄평 관리' })).toBeInTheDocument();

    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

describe('3.1-A ⇢ 7.6-A · 3.1-A ⇢ 2.5', () => {
  it('프레임 상세에서 한줄평 관리가 열리고 고친 값이 보인다', async () => {
    renderApp(['/frames/502']);

    fireEvent.click(await screen.findByRole('button', { name: '한줄평 관리' }));

    const sheet = within(screen.getByRole('dialog', { name: '한줄평 관리' }));
    fireEvent.change(sheet.getByLabelText('한줄평'), { target: { value: '다시 봐도 좋다' } });
    fireEvent.click(sheet.getByRole('button', { name: '수정 완료' }));

    expect(await screen.findByText('“다시 봐도 좋다”')).toBeInTheDocument();
  });

  it('한줄평이 없는 프레임에서는 한줄평 관리를 누를 수 없다', async () => {
    renderApp(['/frames/503']);

    expect(await screen.findByRole('button', { name: '한줄평 관리' })).toBeDisabled();
  });

  it('평가 수정은 프레임을 새로 만들지 않고 3.1-A로 돌아온다', async () => {
    renderApp(['/frames/501']);

    fireEvent.click(await screen.findByRole('button', { name: '평가 수정' }));
    expect(location()).toBe('/movies/401/rate');

    await setScore(3);
    fireEvent.click(screen.getByRole('button', { name: '평가 완료' }));

    await waitFor(() => expect(location()).toBe('/frames/501'));
    // 다시 읽은 상세가 고친 별점을 보여준다. 프레임이 새로 생겼는지는 서버가 정한다
    expect(await screen.findByText('3.0')).toBeInTheDocument();
  });
});
