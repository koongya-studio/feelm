/*
 * MY 기록 목록 — 이동 경로 (업무 S15P21E106-306)
 *
 * 화면을 하나씩 열면 멀쩡하지만 **이어서 걸으면 끊기는 자리**를 잡는다. 이 업무에서
 * 그런 자리는 전부 세션 저장소를 거친다.
 *
 * - 7.6 ⇢ 2.5: 이미 평가한 영화를 고치면 **프레임이 늘면 안 된다.** 306 전의 2.5는
 *   수정도 새 평가로 저장해 필름이 101프레임이 됐다
 * - 7.7 ⇢ 2.5: 평가하고 돌아오면 7.7에서 빠지고 7.6의 수가 늘어야 한다. 감상 기록을
 *   기준 목업에 두면 `RATED`로 바꿀 곳이 없어 목록에 남는다
 * - 7.6 ⇢ 7.6-A: 시트에서 고친 한줄평이 목록과 영화 상세에 함께 보여야 한다
 *
 * 화면은 실제 라우트 패턴으로 함께 건다(PartyFlow.test.tsx와 같은 방식).
 * `api/users`만 가로챈다 — API-014가 실제 호출이라(MyDashboardPage.test.tsx).
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
import { getMovieDetail } from '../../../api/movies';
import { getMyFrames } from '../../../api/taste';
import { getMyProfile } from '../../../api/users';
import { resetSessionStore } from '../../../mocks/sessionStore';
import { mockMyProfile } from '../../../mocks/user';
import { ROUTE_PATTERN } from '../../../routes/paths';

vi.mock('../../../api/users', () => ({
  getMyProfile: vi.fn(),
}));

beforeEach(() => {
  resetSessionStore();
  vi.mocked(getMyProfile).mockReset().mockResolvedValue(mockMyProfile);
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

describe('7.1 → 7.5 → 2.4', () => {
  it('추천 기록의 영화를 누르면 그 영화 상세로 간다', async () => {
    renderApp([ROUTE_PATTERN.my]);

    fireEvent.click(await screen.findByRole('link', { name: '추천 기록' }));
    expect(location()).toBe('/my/recommendations');

    // 월 제목과 출처 세 가지가 보인다
    expect(await screen.findByRole('heading', { name: '2026년 8월' })).toBeInTheDocument();
    expect(screen.getAllByText('취향 맞춤').length).toBeGreaterThan(0);
    expect(screen.getAllByText('새로운 맛 발견').length).toBeGreaterThan(0);
    expect(screen.getByText('파티 추천')).toBeInTheDocument();
    expect(screen.getByText('총 7편을 추천받았어요')).toBeInTheDocument();

    // 파티 추천 줄(104)은 추천 목업 밖의 영화라 상세가 따로 있어야 이어진다
    const partyRow = screen.getByText('파티 추천').closest('a');
    fireEvent.click(partyRow!);

    expect(location()).toBe('/movies/104');
    expect(await screen.findByText(/오래 묻어 둔 사건 기록/)).toBeInTheDocument();
  });
});

describe('7.1 → 7.6 ⇢ 2.5 → 7.6', () => {
  it('기존 평가를 고치면 목록에 반영되고 프레임은 늘지 않는다', async () => {
    renderApp([ROUTE_PATTERN.my]);

    fireEvent.click(await screen.findByRole('link', { name: '평가' }));
    expect(location()).toBe('/my/ratings');
    expect(await findTab('평가 100')).toHaveAttribute('aria-current', 'page');

    // 402는 별점 4.0에 한줄평이 있는 기준 평가다
    const row = screen.getByRole('link', { name: /영화 제목 2/ });
    expect(row).toHaveAttribute('href', '/movies/402/rate');
    fireEvent.click(row);

    await setScore(2.5);
    fireEvent.click(screen.getByRole('button', { name: '평가 완료' }));

    // 2.6 · 2.7이 아니라 온 곳(7.6)으로 돌아온다
    await waitFor(() => expect(location()).toBe('/my/ratings'));
    expect(await screen.findByRole('link', { name: /영화 제목 2.*2\.5/ })).toBeInTheDocument();
    expect(await findTab('평가 100')).toBeInTheDocument();

    // 필름에도 새 프레임이 생기지 않았고 별점만 바뀌었다
    const frames = await getMyFrames();
    expect(frames.totalCount).toBe(100);
    expect(frames.items.filter((frame) => frame.movie.movieId === 402)).toEqual([
      expect.objectContaining({ frameId: 502, score: 2.5 }),
    ]);
    // 한줄평과 공개 범위는 그대로다
    expect((await getMovieDetail(402)).myRating).toEqual(
      expect.objectContaining({ score: 2.5, visibility: 'PUBLIC' }),
    );
  });
});

describe('7.6 → 7.7 ⇢ 2.5', () => {
  it('평가 안 남긴 영화를 평가하면 7.7에서 빠지고 평가 수가 는다', async () => {
    const { unmount } = renderApp([ROUTE_PATTERN.myRatings]);

    fireEvent.click(await findTab('안 남긴 영화 3'));
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

    // 7.7로 다시 들어오면 빠져 있다
    renderApp([ROUTE_PATTERN.unratedMovies]);

    expect(await findTab('안 남긴 영화 2')).toBeInTheDocument();
    expect(await findTab('평가 101')).toBeInTheDocument();
    expect(screen.queryByText('영화 제목 5')).not.toBeInTheDocument();

    // 7.6 맨 위에 방금 평가가 있다
    fireEvent.click(await findTab('평가 101'));
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
  it('한줄평을 고치고 공개 범위를 바꾸면 목록과 영화 상세에 반영된다', async () => {
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

    expect((await getMovieDetail(402)).myRating).toEqual({
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
    expect((await getMovieDetail(402)).myRating?.comment).not.toBeNull();

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
    expect((await getMovieDetail(402)).myRating).toEqual(
      expect.objectContaining({ score: 4, comment: null }),
    );
    expect((await getMyFrames()).totalCount).toBe(100);
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
    expect(await screen.findByText('3.0')).toBeInTheDocument();
    expect((await getMyFrames()).totalCount).toBe(100);
  });
});
