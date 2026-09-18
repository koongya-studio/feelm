/*
 * 2.7 필름 추가 완료 — 수치와 문구 (업무 S15P21E106-599)
 *
 * 고정하는 것은 하나다. **이 화면은 실제로 움직인 만큼만 말한다.**
 *
 * 예전에는 추가 전 수치에 `+1`을 더해 추가 후를 지어냈다. 팝콘 집계는 `대표 맛이 있는
 * 영화의 평가`를 세므로 평가가 아니라 대표 맛 배정만으로도 알 수가 늘고, 그때 화면만
 * 거짓말을 한다. 그래서 2.5가 저장 뒤 다시 읽은 실측(`after`)을 쓰고, 그것을 받지 못했을
 * 때만 계약이 보장하는 증가량(프레임 1개 · 대표 맛이 있으면 팝콘 1알)으로 물러선다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import RatingResultPage from './index';
import { ROUTE_PATTERN } from '../../../routes/paths';
import type { RatingCounts } from '../../../api/watch';
import type { Rating } from '../../../types/watch';

vi.mock('../../../api/taste', () => ({ getPopcornFlavors: vi.fn() }));

const { getPopcornFlavors } = await import('../../../api/taste');

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

beforeEach(() => {
  vi.useFakeTimers();
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

function renderResult(state: { rating: Rating; counts: RatingCounts; after: RatingCounts | null }) {
  render(
    <MemoryRouter
      initialEntries={[
        {
          pathname: '/movies/550/rate/result',
          state: { ...state, movieTitle: '영화', posterUrl: null },
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
