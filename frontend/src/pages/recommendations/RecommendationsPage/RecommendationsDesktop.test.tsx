/*
 * 2.2 추천 영화 — 데스크톱 시안 (업무 S15P21E106-783)
 *
 * 넓은 화면에서 카드가 취향 맞춤 · 취향 발견 두 필름으로 갈리고, 오른쪽에 추천 기준과 최근 본
 * 영화가 서는지를 고정한다. 셸이 우측 보조 패널 자리를 내놓아야 페이지가 그리로 카드를 보낼 수
 * 있으므로(업무 -782) 화면만이 아니라 셸까지 함께 그린다.
 *
 * **관심없음(API-047)은 넓은 화면에서도 그대로다.** 배치만 바뀌는 업무이므로 동작이 따라
 * 바뀌지 않았는지 함께 본다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import RecommendationsPage from './index';
import AppLayout from '../../../layouts/AppLayout';
import { AuthContext } from '../../../contexts/authContext';
import { resetViewport, setViewportWidth } from '../../../test/viewport';
import type { AuthValue } from '../../../contexts/authContext';
import type { RecommendationSet } from '../../../types/recommendation';
import type { WatchRecordList } from '../../../types/watch';

vi.mock('../../../api/recommendations', () => ({
  getRecommendations: vi.fn(),
  dismissMovie: vi.fn(),
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

const { dismissMovie, getRecommendations } = await import('../../../api/recommendations');
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

const set: RecommendationSet = {
  recommendationSetId: 7001,
  generatedAt: '2026-09-16T00:00:00Z',
  items: [
    {
      recommendationItemId: 3301,
      type: 'TASTE',
      flavorCode: 'FLAVOR_A',
      movie: {
        movieId: 550,
        title: '파이트 클럽',
        posterUrl: null,
        releaseYear: 1999,
        genres: ['드라마'],
        averageRating: 4.3,
        ottProviders: ['NETFLIX'],
      },
      reason: {
        summary: '스릴러를 높게 평가하셨어요',
        factors: [{ type: 'GENRE', label: '스릴러' }],
      },
    },
    {
      recommendationItemId: 3302,
      type: 'TASTE',
      flavorCode: 'FLAVOR_A',
      movie: {
        movieId: 551,
        title: '조디악',
        posterUrl: null,
        releaseYear: 2007,
        genres: ['범죄'],
        averageRating: 4.1,
        ottProviders: [],
      },
      reason: {
        summary: '같은 감독의 영화를 좋아하셨어요',
        factors: [{ type: 'DIRECTOR', label: '데이비드 핀처' }],
      },
    },
    {
      recommendationItemId: 3303,
      type: 'DISCOVERY',
      flavorCode: 'FLAVOR_B',
      movie: {
        movieId: 807,
        title: '세븐',
        posterUrl: null,
        releaseYear: 1995,
        genres: ['스릴러'],
        averageRating: 4.5,
        ottProviders: [],
      },
      reason: {
        summary: '새로운 맛을 여는 영화예요',
        factors: [{ type: 'NEW_FLAVOR', label: '느와르' }],
      },
    },
  ],
};

const watched: WatchRecordList = {
  items: [
    {
      watchRecordId: 91,
      movie: { movieId: 700, title: '조커', posterUrl: null },
      status: 'RATED',
      ott: { ottId: 1, code: 'NETFLIX' },
      clickedAt: '2026-09-14T10:00:00Z',
      confirmDueAt: null,
      expiresAt: null,
      watchedAt: '2026-09-15T10:00:00Z',
      ratingId: 5,
    },
  ],
  nextCursor: null,
  hasNext: false,
};

function renderRecommendations(width: number) {
  setViewportWidth(width);

  return render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={['/recommendations']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/recommendations" element={<RecommendationsPage />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

beforeEach(() => {
  vi.mocked(getRecommendations).mockReset().mockResolvedValue(set);
  vi.mocked(dismissMovie).mockReset().mockResolvedValue(undefined);
  vi.mocked(getOtts).mockReset().mockResolvedValue([]);
  vi.mocked(getWatchRecords).mockReset().mockResolvedValue(watched);
  vi.mocked(getUnreadNotificationCount).mockReset().mockResolvedValue(0);
  vi.mocked(getMyProfile).mockReset().mockRejectedValue(new Error('쓰지 않는다'));
});

afterEach(() => {
  resetViewport();
});

describe('RecommendationsPage 데스크톱 시안', () => {
  it('카드를 취향 맞춤 · 취향 발견 두 필름으로 나눈다', async () => {
    renderRecommendations(1440);

    const taste = await screen.findByRole('group', { name: '취향 맞춤 추천 카드' });
    const discovery = screen.getByRole('group', { name: '취향 발견 추천 카드' });

    expect(within(taste).getAllByText('파이트 클럽')).toHaveLength(2);
    expect(within(taste).getByText('조디악')).toBeInTheDocument();
    expect(within(discovery).getAllByText('세븐')).toHaveLength(3);
    expect(within(discovery).queryByText('파이트 클럽')).not.toBeInTheDocument();
    // 계약의 맞춤 2편 · 발견 1편은 데스크톱 묶음 안에서만 석 장씩 채운다
    expect(within(taste).getAllByRole('article')).toHaveLength(3);
    expect(within(discovery).getAllByRole('article')).toHaveLength(3);
  });

  it('숨겨 두었던 화면 제목과 오늘 날짜를 드러낸다', async () => {
    renderRecommendations(1440);

    expect(await screen.findByRole('heading', { level: 1, name: '오늘의 추천' })).toBeVisible();
    expect(screen.getByText('9월 16일 (수)')).toBeInTheDocument();
  });

  it('우측 보조 패널에 추천 기준과 최근 본 영화를 보낸다', async () => {
    renderRecommendations(1440);

    const aside = await screen.findByRole('complementary', { name: '보조 정보' });

    expect(await within(aside).findByText('#스릴러')).toBeInTheDocument();
    expect(within(aside).getByText('#데이비드 핀처')).toBeInTheDocument();
    expect(within(aside).getByText('#느와르')).toBeInTheDocument();

    expect(await within(aside).findByText('조커')).toBeInTheDocument();
    // 시안의 시청 시간과 진행 막대는 없는 데이터다(사용자 결정 2026-09-22)
    expect(within(aside).getByText('9월 15일 감상')).toBeInTheDocument();
  });

  it('탭으로 누르라는 안내를 넓은 화면에서 뺀다', async () => {
    renderRecommendations(1440);

    await screen.findByRole('group', { name: '취향 맞춤 추천 카드' });

    expect(screen.queryByText(/포스터를 탭하면/)).not.toBeInTheDocument();
  });

  it('관심없음은 넓은 화면에서도 그대로 동작한다', async () => {
    renderRecommendations(1440);

    const dismissButtons = await screen.findAllByRole('button', {
      name: '파이트 클럽 관심없음',
    });
    fireEvent.click(dismissButtons[0]);

    await waitFor(() => expect(dismissMovie).toHaveBeenCalledWith(550, 3301));
  });

  it('412px에서는 지금까지의 화면 그대로다', async () => {
    renderRecommendations(412);

    expect(await screen.findByText(/포스터를 탭하면/)).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: '취향 맞춤 추천 카드' })).not.toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: /상세 보기/ })).toHaveLength(set.items.length);
    expect(screen.getAllByRole('button', { name: /관심없음/ })).toHaveLength(set.items.length);
    expect(getWatchRecords).not.toHaveBeenCalled();
  });
});
