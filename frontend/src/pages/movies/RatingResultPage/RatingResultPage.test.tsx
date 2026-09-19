/*
 * 2.7 필름 추가 완료 — 수치와 문구 (업무 S15P21E106-599)
 *
 * 고정하는 것은 하나다. **이 화면은 실제로 움직인 만큼만 말한다.**
 *
 * 예전에는 추가 전 수치에 `+1`을 더해 추가 후를 지어냈다. 팝콘 집계는 `대표 맛이 있는
 * 영화의 평가`를 세므로 평가가 아니라 대표 맛 배정만으로도 알 수가 늘고, 그때 화면만
 * 거짓말을 한다. 그래서 저장 뒤 다시 읽은 실측(`after`)을 쓰고, 그것을 받지 못했을
 * 때만 계약이 보장하는 증가량(프레임 1개 · 대표 맛이 있으면 팝콘 1알)으로 물러선다.
 *
 * 실측은 2.6이 떠 있는 동안 이 화면이 읽는다(업무 -635). 2.5는 그것을 기다리지 않고 넘어오므로
 * 2.7은 전환 시간과 실측 도착을 **둘 다** 기다린다.
 *
 * 2.6의 필름 릴은 새 프레임 아래에 내 필름(API-042)의 기존 프레임을 잇는다(업무 -633).
 * 읽지 못해도 새 프레임 한 장은 그린다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import RatingResultPage from './index';
import { ROUTE_PATTERN } from '../../../routes/paths';
import type { RatingCounts } from '../../../api/watch';
import type { FrameList, FrameSummary } from '../../../types/taste';
import type { Rating } from '../../../types/watch';

vi.mock('../../../api/taste', () => ({ getMyFrames: vi.fn(), getPopcornFlavors: vi.fn() }));
vi.mock('../../../api/watch', () => ({ getRatingCounts: vi.fn() }));

const { getMyFrames, getPopcornFlavors } = await import('../../../api/taste');
const { getRatingCounts } = await import('../../../api/watch');

/** 2.6 → 2.7 자동 전환 시간(ms). 화면의 상수와 같다 */
const TRANSITION_MS = 1200;

const rating: Rating = {
  ratingId: 91,
  movieId: 550,
  score: 4.5,
  comment: null,
  visibility: 'PRIVATE',
  spoiler: false,
  frame: { frameId: 77, createdAt: '2026-09-17T13:59:03Z' },
  popcorn: { flavorCode: 'FLAVOR_A', isNewFlavor: false, flavorCount: 3 },
  watchStatus: 'RATED',
  createdAt: '2026-09-17T13:59:03Z',
};

const counts: RatingCounts = { frameCount: 2, popcornCount: 11, weeklyFrameDelta: 3 };

function frame(frameId: number, createdAt: string): FrameSummary {
  return {
    frameId,
    movie: { movieId: 100 + frameId, title: `영화 ${frameId}`, posterUrl: `/p/${frameId}.jpg` },
    score: 4,
    flavorCode: 'FLAVOR_A',
    createdAt,
  };
}

function frameList(items: FrameSummary[]): FrameList {
  return { totalCount: items.length, items, nextCursor: null, hasNext: false };
}

/** 릴에 그려진 포스터 주소를 위에서부터 읽는다. 새 프레임은 `/p/new.jpg`다 */
function reelPosters() {
  return Array.from(screen.getByTestId('film-reel').querySelectorAll('img')).map((img) =>
    img.getAttribute('src'),
  );
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.mocked(getRatingCounts).mockReset();
  vi.mocked(getMyFrames).mockReset().mockResolvedValue(frameList([]));
  vi.mocked(getPopcornFlavors)
    .mockReset()
    .mockResolvedValue([
      {
        code: 'FLAVOR_A',
        displayName: '오리지널 솔트',
        color: '#F5E6C8',
        representativeKeywords: [],
        sortOrder: 1,
      },
    ]);
});

afterEach(() => {
  vi.useRealTimers();
});

function renderResult(state: {
  rating: Rating;
  counts: RatingCounts;
  after?: RatingCounts | null;
  posterUrl?: string | null;
}) {
  render(
    <MemoryRouter
      initialEntries={[
        {
          pathname: '/movies/550/rate/result',
          state: { movieTitle: '영화', posterUrl: null, ...state },
        },
      ]}
    >
      <Routes>
        <Route path={ROUTE_PATTERN.ratingResult} element={<RatingResultPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

/** 2.6에서 2.7로 넘기고, 그 사이 맛 기준정보 조회도 흘려보낸다 */
async function finish() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(TRANSITION_MS);
  });
}

describe('2.7 수치와 문구', () => {
  it('실측을 받으면 서버가 실제로 움직인 만큼 적는다', async () => {
    // 평가는 한 건인데 팝콘은 두 알 늘었다. 대표 맛 배정이 같은 시간에 과거 평가를 버킷에 넣은 경우다
    renderResult({
      rating,
      counts,
      after: { frameCount: 3, popcornCount: 13, weeklyFrameDelta: 3 },
    });

    await finish();

    expect(screen.getByText('프레임 1개 · 팝콘 2알이 추가됐어요')).toBeInTheDocument();
    expect(screen.getByText('팝콘 버킷 11알 → 13알')).toBeInTheDocument();
    expect(screen.getByText('3프레임 · 이번 주 +4')).toBeInTheDocument();
    // 이 평가가 담긴 맛은 언제나 한 알이다
    expect(screen.getByText('오리지널 솔트 맛 +1알')).toBeInTheDocument();
  });

  it('실측을 받지 못하면 계약이 보장하는 증가량으로 물러선다', async () => {
    renderResult({ rating, counts, after: null });

    await finish();

    expect(screen.getByText('프레임 1개 · 팝콘 1알이 추가됐어요')).toBeInTheDocument();
    expect(screen.getByText('팝콘 버킷 11알 → 12알')).toBeInTheDocument();
    expect(screen.getByText('3프레임 · 이번 주 +4')).toBeInTheDocument();
  });

  it('대표 맛이 없는 영화라도 버킷이 움직였으면 버킷만 적는다', async () => {
    // 이 평가는 팝콘을 만들지 않았는데, 같은 시간에 다른 영화에 대표 맛이 붙어 버킷이 늘었다
    renderResult({
      rating: { ...rating, popcorn: { flavorCode: null, isNewFlavor: false, flavorCount: 0 } },
      counts,
      after: { frameCount: 3, popcornCount: 12, weeklyFrameDelta: 3 },
    });

    await finish();

    expect(screen.getByText('프레임 1개 · 팝콘 1알이 추가됐어요')).toBeInTheDocument();
    expect(screen.getByText('팝콘 버킷 11알 → 12알')).toBeInTheDocument();
    expect(screen.queryByText(/맛 \+1알/)).not.toBeInTheDocument();
  });

  it('대표 맛이 없고 버킷도 그대로면 팝콘을 말하지 않는다', async () => {
    renderResult({
      rating: { ...rating, popcorn: { flavorCode: null, isNewFlavor: false, flavorCount: 0 } },
      counts,
      after: { frameCount: 3, popcornCount: 11, weeklyFrameDelta: 3 },
    });

    await finish();

    expect(screen.getByText('프레임 1개가 추가됐어요')).toBeInTheDocument();
    expect(screen.queryByText(/팝콘 버킷/)).not.toBeInTheDocument();
    expect(screen.queryByText(/맛 \+1알/)).not.toBeInTheDocument();
  });
});

describe('2.6에서 추가 후 수치 읽기', () => {
  it('2.5가 넘기지 않은 실측을 2.6이 떠 있는 동안 읽어 2.7에 쓴다', async () => {
    vi.mocked(getRatingCounts).mockResolvedValue({
      frameCount: 3,
      popcornCount: 13,
      weeklyFrameDelta: 3,
    });

    renderResult({ rating, counts });

    // 2.6은 추가 전 수치만 그리므로 곧바로 뜬다
    expect(screen.getByText('2프레임 · 이번 주 +3')).toBeInTheDocument();
    expect(getRatingCounts).toHaveBeenCalledTimes(1);

    await finish();

    expect(screen.getByText('프레임 1개 · 팝콘 2알이 추가됐어요')).toBeInTheDocument();
    expect(screen.getByText('팝콘 버킷 11알 → 13알')).toBeInTheDocument();
    expect(getRatingCounts).toHaveBeenCalledTimes(1);
  });

  it('전환 시간이 지나도 실측이 오기 전에는 2.7을 띄우지 않는다', async () => {
    let resolve: (value: RatingCounts) => void = () => undefined;
    vi.mocked(getRatingCounts).mockReturnValue(
      new Promise((done) => {
        resolve = done;
      }),
    );

    renderResult({ rating, counts });

    await finish();

    // 추가 전 수치에 +1을 지어내 먼저 그리지 않는다
    expect(screen.getByText('2프레임 · 이번 주 +3')).toBeInTheDocument();
    expect(screen.queryByText(/추가됐어요/)).not.toBeInTheDocument();

    await act(async () => {
      resolve({ frameCount: 3, popcornCount: 12, weeklyFrameDelta: 3 });
    });

    expect(screen.getByText('프레임 1개 · 팝콘 1알이 추가됐어요')).toBeInTheDocument();
    expect(screen.getByText('3프레임 · 이번 주 +4')).toBeInTheDocument();
  });

  it('실측을 읽지 못하면 계약이 보장하는 증가량으로 물러선다', async () => {
    vi.mocked(getRatingCounts).mockRejectedValue(new Error('network'));

    renderResult({ rating, counts });

    await finish();

    expect(screen.getByText('프레임 1개 · 팝콘 1알이 추가됐어요')).toBeInTheDocument();
    expect(screen.getByText('팝콘 버킷 11알 → 12알')).toBeInTheDocument();
  });

  it('실측을 이미 받은 state면 다시 읽지 않는다', async () => {
    renderResult({ rating, counts, after: null });

    await finish();

    expect(getRatingCounts).not.toHaveBeenCalled();
  });
});

describe('2.6 필름 릴', () => {
  it('새 프레임 아래에 내 필름의 기존 프레임을 최신순으로 잇는다', async () => {
    // API-042는 방금 저장된 새 프레임(77)을 맨 앞에 담아 준다
    vi.mocked(getMyFrames).mockResolvedValue(
      frameList([
        frame(77, '2026-09-17T13:59:03Z'),
        frame(60, '2026-09-10T00:00:00Z'),
        frame(41, '2026-09-01T00:00:00Z'),
      ]),
    );

    renderResult({ rating, counts, after: null, posterUrl: '/p/new.jpg' });

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });

    // 새 프레임은 한 번만, 맨 앞에 선다
    expect(reelPosters()).toEqual(['/p/new.jpg', '/p/60.jpg', '/p/41.jpg']);
    const reel = within(screen.getByTestId('film-reel'));
    expect(reel.getByAltText('영화 60')).toBeInTheDocument();
  });

  it('새 프레임보다 나중에 생긴 프레임은 잇지 않는다', async () => {
    // 뒤로 가기로 2.6이 다시 열렸을 때 그 사이 다른 영화를 평가한 경우다
    vi.mocked(getMyFrames).mockResolvedValue(
      frameList([
        frame(90, '2026-09-18T09:00:00Z'),
        frame(77, '2026-09-17T13:59:03Z'),
        frame(60, '2026-09-10T00:00:00Z'),
      ]),
    );

    renderResult({ rating, counts, after: null, posterUrl: '/p/new.jpg' });

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(reelPosters()).toEqual(['/p/new.jpg', '/p/60.jpg']);
  });

  it('내 필름을 읽지 못해도 새 프레임 한 장은 그리고 2.7로 넘어간다', async () => {
    vi.mocked(getMyFrames).mockRejectedValue(new Error('network'));

    renderResult({ rating, counts, after: null, posterUrl: '/p/new.jpg' });

    await finish();

    expect(reelPosters()).toEqual(['/p/new.jpg']);
    expect(screen.getByText('프레임 1개 · 팝콘 1알이 추가됐어요')).toBeInTheDocument();
  });
});
