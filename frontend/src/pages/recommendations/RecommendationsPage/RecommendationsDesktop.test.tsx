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
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
      recommendationItemId: 3304,
      type: 'TASTE',
      flavorCode: 'FLAVOR_A',
      movie: {
        movieId: 552,
        title: '더 게임',
        posterUrl: null,
        releaseYear: 1997,
        genres: ['미스터리'],
        averageRating: 3.9,
        ottProviders: [],
      },
      reason: {
        summary: '비슷한 분위기의 영화를 찾으셨어요',
        factors: [{ type: 'GENRE', label: '미스터리' }],
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
    {
      recommendationItemId: 3305,
      type: 'DISCOVERY',
      flavorCode: 'FLAVOR_B',
      movie: {
        movieId: 808,
        title: '미스틱 리버',
        posterUrl: null,
        releaseYear: 2003,
        genres: ['드라마'],
        averageRating: 4.0,
        ottProviders: [],
      },
      reason: {
        summary: '아직 담지 않은 맛이에요',
        factors: [{ type: 'NEW_FLAVOR', label: '느와르' }],
      },
    },
    {
      recommendationItemId: 3306,
      type: 'DISCOVERY',
      flavorCode: 'FLAVOR_B',
      movie: {
        movieId: 809,
        title: '메멘토',
        posterUrl: null,
        releaseYear: 2000,
        genres: ['미스터리'],
        averageRating: 4.4,
        ottProviders: [],
      },
      reason: {
        summary: '취향과 이어지는 새로운 결이에요',
        factors: [{ type: 'NEW_FLAVOR', label: '비선형 서사' }],
      },
    },
  ],
};

/**
 * 취향 발견 후보가 없어 서버가 여섯 자리를 모두 맞춤으로 채운 경우(업무 -793의 대체 규칙).
 * 화면은 채워진 대로 두 묶음에 석 장씩 세운다.
 */
const filledSet: RecommendationSet = {
  ...set,
  items: set.items.map((item) => ({ ...item, type: 'TASTE' as const })),
};

/**
 * 모바일이 받는 세 편 — 취향 맞춤 둘 + 취향 발견 하나(API-045 기본값, 업무 -793).
 * 화면이 `size`로 무엇을 요청했는지에 따라 가짜 서버가 이 둘을 갈라 준다.
 */
const mobileSet: RecommendationSet = {
  ...set,
  items: [set.items[0], set.items[1], set.items[3]],
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
  vi.mocked(getRecommendations)
    .mockReset()
    .mockImplementation((size) => Promise.resolve(size === 6 ? set : mobileSet));
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
  it('OTT 배지만 서비스로 연결하고 영화 포스터는 상세로 연결한다', async () => {
    vi.mocked(getOtts).mockResolvedValue([
      { ottId: 1, code: 'NETFLIX', name: '넷플릭스', logoUrl: null },
    ]);
    renderRecommendations(1440);

    const taste = await screen.findByRole('group', { name: '취향 맞춤 추천 카드' });
    const ottLink = within(taste).getByRole('link', {
      name: '넷플릭스에서 파이트 클럽 보기 (새 창)',
    });
    expect(ottLink).toHaveAttribute(
      'href',
      'https://www.netflix.com/search?q=%ED%8C%8C%EC%9D%B4%ED%8A%B8%20%ED%81%B4%EB%9F%BD',
    );
    expect(ottLink).toHaveAttribute('target', '_blank');
    expect(within(taste).getByRole('link', { name: '파이트 클럽 상세 보기' })).toHaveAttribute(
      'href',
      '/movies/550',
    );
  });

  it('카드를 취향 맞춤 · 취향 발견 두 필름으로 나누고 묶음마다 석 장을 세운다', async () => {
    renderRecommendations(1440);

    const taste = await screen.findByRole('group', { name: '취향 맞춤 추천 카드' });
    const discovery = screen.getByRole('group', { name: '취향 발견 추천 카드' });

    // 넓은 화면은 여섯 편을 요청한다(API-045 `size=6`, 업무 -794)
    expect(getRecommendations).toHaveBeenCalledWith(6);

    expect(within(taste).getByText('파이트 클럽')).toBeInTheDocument();
    expect(within(taste).getByText('조디악')).toBeInTheDocument();
    expect(within(taste).getByText('더 게임')).toBeInTheDocument();
    expect(within(discovery).getByText('세븐')).toBeInTheDocument();
    expect(within(discovery).getByText('미스틱 리버')).toBeInTheDocument();
    expect(within(discovery).getByText('메멘토')).toBeInTheDocument();
    expect(within(discovery).queryByText('파이트 클럽')).not.toBeInTheDocument();

    /*
     * **같은 영화가 두 번 서지 않는다**(업무 -794). 계약이 맞춤 둘 · 발견 하나를 주던 동안
     * -783이 시안의 석 장을 채우려고 카드를 되풀이했고, 그것을 걷어낸 것이 이 업무다.
     */
    expect(within(taste).getAllByRole('article')).toHaveLength(3);
    expect(within(discovery).getAllByRole('article')).toHaveLength(3);
    expect(screen.getAllByRole('article')).toHaveLength(6);
  });

  /*
   * **발견 묶음은 자리의 이름이다**(사용자 결정 2026-09-22).
   *
   * 유형으로만 가르면 대체분이 맞춤 묶음으로 몰려 발견 묶음이 비고, 좌우 두 단이 한 단으로
   * 무너진다. 카드 수는 늘었는데 화면은 예전보다 못해지므로 채워진 대로 세운다.
   */
  it('발견 후보가 모자라 맞춤으로 채워 와도 두 묶음이 석 장씩 선다', async () => {
    vi.mocked(getRecommendations).mockImplementation(() => Promise.resolve(filledSet));

    renderRecommendations(1440);

    const taste = await screen.findByRole('group', { name: '취향 맞춤 추천 카드' });
    const discovery = screen.getByRole('group', { name: '취향 발견 추천 카드' });

    expect(within(taste).getAllByRole('article')).toHaveLength(3);
    expect(within(discovery).getAllByRole('article')).toHaveLength(3);
    // 채워진 카드가 발견 묶음 자리에 그대로 선다
    expect(within(discovery).getByText('세븐')).toBeInTheDocument();
    expect(within(discovery).getByText('메멘토')).toBeInTheDocument();
    expect(screen.getAllByRole('article')).toHaveLength(6);
  });

  it('숨겨 두었던 화면 제목과 오늘 날짜를 드러낸다', async () => {
    renderRecommendations(1440);

    // 첫 조회 중 로딩 안내가 표시되므로 추천 응답의 날짜가 도착한 뒤 완료 화면을 확인한다.
    expect(await screen.findByText('9월 16일 (수)')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: '오늘의 추천' })).toBeVisible();
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

  it('대표·작은 카드 모두 관심없음만 표시하고 대표 버튼만 크게 그린다', async () => {
    renderRecommendations(1440);

    const taste = await screen.findByRole('group', { name: '취향 맞춤 추천 카드' });
    const [lead, compact] = within(taste).getAllByRole('article');
    const leadDismiss = within(lead).getByRole('button', { name: '파이트 클럽 관심없음' });
    const compactDismiss = within(compact).getByRole('button', { name: '조디악 관심없음' });

    // 아이콘만 두지 않고 글자를 함께 보인다(업무 -873). 대표 카드만 한 단계 크다
    expect(leadDismiss).toHaveTextContent('관심없음');
    expect(compactDismiss).toHaveTextContent('관심없음');
    expect(leadDismiss).toHaveAttribute('data-size', 'lead');
    expect(compactDismiss).not.toHaveAttribute('data-size');
    expect(leadDismiss.className).toBe(compactDismiss.className);
    expect(
      within(lead)
        .getByRole('link', { name: 'NETFLIX에서 파이트 클럽 보기 (새 창)' })
        .closest('ul')
        ?.parentElement?.querySelector('h3'),
    ).not.toBeNull();
    expect(within(lead).queryByRole('button', { name: '파이트 클럽 OTT 메뉴' })).toBeNull();
    expect(within(compact).queryByRole('button', { name: '조디악 OTT 메뉴' })).toBeNull();
    expect(within(taste).queryByRole('button', { name: /OTT 메뉴/ })).toBeNull();
  });

  it('관심없음 처리 중 모바일로 바뀌면 현재 폭의 세 편으로 보충한다', async () => {
    let resolveDismiss!: () => void;
    vi.mocked(dismissMovie).mockReturnValue(
      new Promise<void>((resolve) => {
        resolveDismiss = resolve;
      }),
    );

    renderRecommendations(1440);

    const dismissButtons = await screen.findAllByRole('button', {
      name: '파이트 클럽 관심없음',
    });
    fireEvent.click(dismissButtons[0]);
    await waitFor(() => expect(dismissMovie).toHaveBeenCalledWith(550, 3301));

    act(() => setViewportWidth(412));
    await waitFor(() => expect(getRecommendations).toHaveBeenCalledWith(3));

    await act(async () => resolveDismiss());

    // 셸 전환으로 기존 페이지는 사라졌으므로 그 페이지의 6편 보충 요청은 이어지지 않는다.
    expect(vi.mocked(getRecommendations).mock.calls.map(([size]) => size)).toEqual([6, 3]);
    expect(screen.getAllByRole('link', { name: /상세 보기/ })).toHaveLength(3);
  });

  it('412px에서는 모바일 카드 세 편을 요청한다', async () => {
    renderRecommendations(412);

    expect(await screen.findByText(/카드를 탭하면/)).toBeInTheDocument();
    // 모바일은 편수를 싣지 않는다. 계약의 기본값이 3이다(업무 -793)
    expect(getRecommendations).toHaveBeenCalledWith(3);
    expect(screen.queryByRole('group', { name: '취향 맞춤 추천 카드' })).not.toBeInTheDocument();
    expect(await screen.findAllByRole('link', { name: /상세 보기/ })).toHaveLength(
      mobileSet.items.length,
    );
    expect(screen.getAllByRole('button', { name: /관심없음/ })).toHaveLength(
      mobileSet.items.length,
    );
    expect(getWatchRecords).not.toHaveBeenCalled();
  });
});
