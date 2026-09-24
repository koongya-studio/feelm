/*
 * 3.1 취향 필름 — 데스크톱 시안 (업무 S15P21E106-783)
 *
 * 넓은 화면에서 무엇이 어디에 서는지를 고정한다. 셸이 우측 보조 패널 자리를 내놓아야
 * 페이지가 그리로 카드를 보낼 수 있으므로(업무 -782) 화면만이 아니라 셸까지 함께 그린다.
 *
 * **모바일이 그대로인지도 같은 파일에서 본다.** 이 업무는 넓은 화면을 더하는 것이지 412px을
 * 바꾸는 것이 아니며, `필름 늘리기`처럼 넓은 화면에서 뺀 것이 좁은 화면에도 사라지면
 * 휴대폰에서 2.2로 가는 유일한 상시 진입점이 없어진다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import FilmPage from './index';
import AppLayout from '../../../layouts/AppLayout';
import { AuthContext } from '../../../contexts/authContext';
import { resetViewport, setViewportWidth } from '../../../test/viewport';
import type { AuthValue } from '../../../contexts/authContext';
import type { FrameList, PopcornBucket } from '../../../types/taste';
import type { RecommendationSet } from '../../../types/recommendation';
import type { WatchRecordList } from '../../../types/watch';

vi.mock('../../../api/taste', () => ({
  getMyFrames: vi.fn(),
  getPopcornBucket: vi.fn(),
}));

vi.mock('../../../api/reports', () => ({
  getLatestReadyReport: vi.fn(),
}));

vi.mock('../../../api/recommendations', () => ({
  getRecommendations: vi.fn(),
}));

vi.mock('../../../api/otts', () => ({
  getOtts: vi.fn(),
}));

vi.mock('../../../api/watch', () => ({
  getWatchRecords: vi.fn(),
}));

vi.mock('../../../api/notifications', () => ({
  getUnreadNotificationCount: vi.fn(),
}));

vi.mock('../../../api/users', () => ({
  getMyProfile: vi.fn(),
}));

const { getMyFrames, getPopcornBucket } = await import('../../../api/taste');
const { getLatestReadyReport } = await import('../../../api/reports');
const { getRecommendations } = await import('../../../api/recommendations');
const { getOtts } = await import('../../../api/otts');
const { getWatchRecords } = await import('../../../api/watch');
const { getUnreadNotificationCount } = await import('../../../api/notifications');
const { getMyProfile } = await import('../../../api/users');

const auth: AuthValue = {
  status: 'authenticated',
  user: { userId: 7, nickname: '김난다', onboardingCompleted: true },
  signIn: vi.fn(),
  signInWithSocial: vi.fn(),
  completeEmailSignup: vi.fn(),
  completeSocialSignup: vi.fn(),
  signOut: vi.fn(),
};

const frames: FrameList = {
  totalCount: 73,
  items: Array.from({ length: 6 }, (_, index) => ({
    frameId: index + 1,
    movie: { movieId: 100 + index, title: `영화 ${index + 1}`, posterUrl: null },
    score: 4,
    flavorCode: 'FLAVOR_A',
    createdAt: '2026-09-01T00:00:00Z',
  })),
  nextCursor: null,
  hasNext: false,
};

const bucket: PopcornBucket = {
  totalCount: 10,
  flavors: [
    {
      flavorCode: 'ACTION',
      displayName: '액션 · 모험',
      color: '#c98a4b',
      popcornCount: 7,
      averageScore: 4.2,
      discoveredAt: null,
    },
    {
      flavorCode: 'DRAMA',
      displayName: '드라마 · 휴먼',
      color: '#d9d2c5',
      popcornCount: 3,
      averageScore: 4.4,
      discoveredAt: null,
    },
  ],
};

const recommendations: RecommendationSet = {
  recommendationSetId: 1,
  generatedAt: '2026-09-22T00:00:00Z',
  items: [
    {
      recommendationItemId: 11,
      type: 'TASTE',
      flavorCode: 'ACTION',
      movie: {
        movieId: 201,
        title: '그랜드 부다페스트 호텔',
        posterUrl: null,
        releaseYear: 2014,
        genres: ['코미디', '드라마'],
        averageRating: 4.3,
        ottProviders: ['NETFLIX'],
      },
      reason: {
        summary: '좋아한 케이퍼 무비와 가장 가까운 한 편이에요',
        factors: [{ type: 'GENRE', label: '케이퍼 무비' }],
      },
    },
    {
      recommendationItemId: 12,
      type: 'DISCOVERY',
      flavorCode: 'DRAMA',
      movie: {
        movieId: 202,
        title: '라라랜드',
        posterUrl: null,
        releaseYear: 2016,
        genres: ['로맨스', '드라마'],
        averageRating: 4.5,
        ottProviders: ['NETFLIX'],
      },
      reason: {
        summary: '기존 취향과 연결되는 새로운 맛이에요',
        factors: [{ type: 'NEW_FLAVOR', label: '음악' }],
      },
    },
  ],
};

const pendingWatch: WatchRecordList = {
  items: [
    {
      watchRecordId: 55,
      movie: { movieId: 203, title: '인터스텔라', posterUrl: null },
      status: 'LINK_CLICKED',
      ott: { ottId: 1, code: 'NETFLIX' },
      clickedAt: '2026-09-20T12:00:00Z',
      confirmDueAt: '2026-09-21T12:00:00Z',
      expiresAt: null,
      watchedAt: null,
      ratingId: null,
    },
  ],
  nextCursor: null,
  hasNext: false,
};

function renderFilm(width: number) {
  setViewportWidth(width);

  return render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={['/film']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/film" element={<FilmPage />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

beforeEach(() => {
  vi.mocked(getMyFrames).mockReset().mockResolvedValue(frames);
  vi.mocked(getPopcornBucket).mockReset().mockResolvedValue(bucket);
  vi.mocked(getLatestReadyReport).mockReset().mockResolvedValue(null);
  vi.mocked(getRecommendations).mockReset().mockResolvedValue(recommendations);
  vi.mocked(getOtts).mockReset().mockResolvedValue([]);
  vi.mocked(getWatchRecords).mockReset().mockResolvedValue(pendingWatch);
  vi.mocked(getUnreadNotificationCount).mockReset().mockResolvedValue(0);
  vi.mocked(getMyProfile).mockReset().mockRejectedValue(new Error('쓰지 않는다'));
});

afterEach(() => {
  resetViewport();
});

describe('FilmPage 데스크톱 시안', () => {
  it('필름 아래에 오늘의 추천 카드를 세운다', async () => {
    renderFilm(1440);

    expect(await screen.findByRole('heading', { name: '오늘의 추천' })).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: '그랜드 부다페스트 호텔 상세 보기' }),
    ).toBeInTheDocument();
    // 받은 카드를 세어 적는다. 개수를 화면에 박아 두지 않는다
    expect(screen.getByRole('link', { name: /맞춤 1 : 확장 1/ })).toHaveAttribute(
      'href',
      '/recommendations',
    );
  });

  it('우측 보조 패널에 팝콘 · 리포트 · 감상 확인 카드를 보낸다', async () => {
    renderFilm(1440);

    const aside = await screen.findByRole('complementary', { name: '보조 정보' });

    expect(await screen.findByRole('heading', { name: '나의 취향 팝콘' })).toBeInTheDocument();
    expect(aside).toContainElement(screen.getByRole('heading', { name: '나의 취향 팝콘' }));
    expect(aside).toContainElement(screen.getByRole('heading', { name: '취향 리포트' }));

    // 감상량은 비율, 선호도는 별점 그대로다(사용자 결정 2026-09-22)
    expect(screen.getByText('70%')).toBeInTheDocument();
    expect(screen.getByText('★ 4.2')).toBeInTheDocument();

    expect(await screen.findByRole('heading', { name: '보셨나요?' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '감상 확인하기' })).toHaveAttribute(
      'href',
      '/watch-records/55/confirm',
    );
  });

  it('팝콘 요약과 필름 늘리기를 본문에서 뺀다', async () => {
    renderFilm(1440);

    await screen.findByRole('heading', { name: '오늘의 추천' });

    expect(screen.getByRole('link', { name: '본 영화 평가하기' })).toHaveAttribute(
      'href',
      '/search',
    );
    expect(screen.queryByText('옆으로 넘겨보세요')).not.toBeInTheDocument();
    // 본문의 팝콘 줄. 같은 내용이 우측 카드로 갔다
    expect(screen.queryByTestId('popcorn-summary-kernels')).not.toBeInTheDocument();
  });

  it('제목 옆에서 지금 보는 프레임 구간을 말한다', async () => {
    renderFilm(1440);

    /*
     * 레이아웃이 없는 환경이라 칸 폭이 0이고, 그때 릴은 첫 칸만 보인다고 본다
     * (`visibleFrameRange`). 여기서 고정하는 것은 수가 아니라 **구간으로 말한다**는 것이다.
     */
    expect(await screen.findByText('1 – 1 / 73')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '이전 프레임 보기' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '다음 프레임 보기' })).toBeEnabled();
  });

  it('1280px 미만에서는 보조 패널을 그리지 않는다', async () => {
    renderFilm(1100);

    await screen.findByRole('heading', { name: '오늘의 추천' });

    expect(screen.queryByRole('heading', { name: '나의 취향 팝콘' })).not.toBeInTheDocument();
    expect(getWatchRecords).not.toHaveBeenCalled();
  });

  it('412px에서는 평가와 추천 버튼을 나란히 제공한다', async () => {
    renderFilm(412);

    expect(await screen.findByRole('button', { name: '본 영화 평가하기' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '영화 추천받기' })).toBeInTheDocument();
    expect(screen.getByTestId('popcorn-summary-kernels')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '오늘의 추천' })).not.toBeInTheDocument();

    await waitFor(() => expect(getRecommendations).not.toHaveBeenCalled());
  });
});
