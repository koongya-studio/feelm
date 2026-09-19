/*
 * 나에게 맞는 OTT 찾기 — 이동 경로 (업무 S15P21E106-308 · 연동 -564)
 *
 * EXT-01 → EXT-02 → EXT-03 → EXT-04 사슬과 두 갈래(EXT-05 데이터 부족 · 6.1 OTT 관리)를 걷는다.
 *
 * 사슬의 출발은 **7.8 설정의 `나에게 맞는 OTT 찾기` 배너**다. 홈에 있던 것을 홈을 없애면서
 * `OTT 관리`(6.1) 옆으로 옮겼다(업무 -461).
 *
 * - EXT-02는 응답을 기다린 뒤 EXT-03으로 **바꿔 끼운다.** 뒤로 가기가 분석 중으로 돌아오면
 *   분석이 다시 돈다
 * - 화면이 그리는 수는 계약이 주는 `matchCount`(편수)다. 일치도 %는 화면에서 뺐다(업무 -563)
 * - 구독 여부도 응답의 `subscribed`다. API-026을 따로 부르지 않는다
 * - EXT-05는 목업 입력이 아니라 **응답**으로 연다 — `insufficientData`이거나 `items`가 빌 때다
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import OttAnalyzingPage from './OttAnalyzingPage';
import OttFinderPage from './OttFinderPage';
import OttInsufficientPage from './OttInsufficientPage';
import OttManagePage from './OttManagePage';
import OttResultDetailPage from './OttResultDetailPage';
import OttResultPage from './OttResultPage';
import SettingsPage from '../my/SettingsPage';
import { ApiError } from '../../api/apiError';
import { AuthContext } from '../../contexts/authContext';
import { getMyProfile } from '../../api/users';
import { getOttRecommendation, getOttRecommendations } from '../../api/ottRecommendations';
import { mockOtts } from '../../mocks/otts';
import { resetSessionStore } from '../../mocks/sessionStore';
import { mockMyProfile } from '../../mocks/user';
import { ROUTE_PATTERN } from '../../routes/paths';
import type { AuthValue } from '../../contexts/authContext';
import type { OttRecommendation } from '../../types/ott';

vi.mock('../../api/users', () => ({
  getMyProfile: vi.fn(),
}));

vi.mock('../../api/otts', () => ({
  getOtts: vi.fn(),
  getMyOttIds: vi.fn(),
  setMyOttIds: vi.fn(),
}));

/*
 * 두 함수를 함께 세운다. `getOttRecommendation`이 목록에서 찾는다는 규칙은 화면의 일이
 * 아니라 도메인 함수의 일이라 `api/ottRecommendations.test.ts`가 따로 잡는다.
 */
vi.mock('../../api/ottRecommendations', () => ({
  getOttRecommendations: vi.fn(),
  getOttRecommendation: vi.fn(),
}));

const { getOtts, getMyOttIds } = await import('../../api/otts');

/** API-025가 주는 확정 5종. 화면도 시험 데이터도 이 식별자를 쓴다 */
const NETFLIX = mockOtts[0];
const TVING = mockOtts[4];

function ottOf({ ottId, code, name, logoUrl }: (typeof mockOtts)[number]) {
  return { ottId, code, name, logoUrl };
}

/** 계약이 주는 모양 그대로다 — `matchCount` 내림차순, 일치도 %나 이유 문장은 없다 */
const recommendation: OttRecommendation = {
  insufficientData: false,
  aggregatedAt: '2026-09-19T00:00:00Z',
  items: [
    {
      ott: ottOf(NETFLIX),
      matchCount: 68,
      subscribed: false,
      topMovies: [
        { movieId: 807, title: '세븐', posterUrl: null, releaseYear: 1995 },
        { movieId: 101, title: '다크 나이트', posterUrl: null, releaseYear: 2008 },
      ],
    },
    { ott: ottOf(TVING), matchCount: 52, subscribed: true, topMovies: [] },
  ],
};

beforeEach(() => {
  resetSessionStore();
  vi.mocked(getMyProfile).mockReset().mockResolvedValue(mockMyProfile);
  vi.mocked(getOtts).mockReset().mockResolvedValue(mockOtts);
  // 6.1 OTT 관리가 쓴다. 구독을 설정한 적이 없는 사용자로 시작한다(API-026 — 빈 배열)
  vi.mocked(getMyOttIds).mockReset().mockResolvedValue([]);
  vi.mocked(getOttRecommendations).mockReset().mockResolvedValue(recommendation);
  vi.mocked(getOttRecommendation)
    .mockReset()
    .mockImplementation((ottId) => {
      const item = recommendation.items.find((candidate) => candidate.ott.ottId === ottId);

      return item
        ? Promise.resolve(item)
        : Promise.reject(new ApiError(404, 'RESOURCE_NOT_FOUND', 'OTT 추천을 찾을 수 없습니다.'));
    });
});

afterEach(() => {
  window.history.replaceState({}, '', '/');
});

function LocationProbe() {
  const location = useLocation();

  return (
    <>
      <div data-testid="location">{location.pathname}</div>
      {/* MY의 탭은 라우트가 아니라 `?tab=`이라 검색 문자열도 본다(업무 -495) */}
      <div data-testid="search">{location.search}</div>
    </>
  );
}

/** 7.8 설정이 로그아웃에 `useAuth`를 쓴다. 이 시험은 EXT 사슬만 보므로 값만 세운다 */
const auth: AuthValue = {
  status: 'authenticated',
  user: { userId: 1024, nickname: '김난다', onboardingCompleted: true },
  signIn: vi.fn(),
  signInWithSocial: vi.fn(),
  completeEmailSignup: vi.fn(),
  completeSocialSignup: vi.fn(),
  signOut: vi.fn(),
};

function renderApp(entries: string[]) {
  return render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={entries} initialIndex={entries.length - 1}>
        <Routes>
          <Route path={ROUTE_PATTERN.settings} element={<SettingsPage />} />
          <Route path={ROUTE_PATTERN.ottManage} element={<OttManagePage />} />
          <Route path={ROUTE_PATTERN.ottFinder} element={<OttFinderPage />} />
          <Route path={ROUTE_PATTERN.ottAnalyzing} element={<OttAnalyzingPage />} />
          <Route path={ROUTE_PATTERN.ottResult} element={<OttResultPage />} />
          <Route path={ROUTE_PATTERN.ottInsufficient} element={<OttInsufficientPage />} />
          <Route path={ROUTE_PATTERN.ottResultDetail} element={<OttResultDetailPage />} />
          <Route path="*" element={<p>다른 화면</p>} />
        </Routes>
        <LocationProbe />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

const location = () => screen.getByTestId('location').textContent;
const search = () => screen.getByTestId('search').textContent;

describe('7.8 → EXT-01 → EXT-02 → EXT-03 → EXT-04', () => {
  /*
   * EXT-02 분석 연출을 기다리는 `waitFor`가 5초까지 쓰는데 vitest의 기본 제한도 5초라,
   * 전체 실행처럼 부하가 있으면 안에서 기다리다 밖에서 끊긴다(develop에서도 같다).
   * 분석을 기다리는 테스트의 제한을 늘린다.
   */
  it('분석을 마치면 결과로 바꿔 끼우고, 상세와 비교를 오간다', async () => {
    renderApp([ROUTE_PATTERN.settings]);

    fireEvent.click(await screen.findByRole('link', { name: 'OTT 추천받기' }));
    expect(location()).toBe('/otts/recommend');

    // EXT-01 요약은 API-014의 평가 수 하나다(업무 -563)
    expect(await screen.findByText('100개')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '내 OTT 취향 분석하기' }));
    expect(location()).toBe('/otts/recommend/analyzing');
    expect(screen.getByText('필름을 감는 중이에요 · 잠시만요')).toBeInTheDocument();

    await waitFor(() => expect(location()).toBe('/otts/recommend/result'), { timeout: 5000 });

    // 1위 카드의 큰 수는 일치도 %가 아니라 계약의 `matchCount`다
    const top = within(await screen.findByRole('region', { name: '1위 넷플릭스' }));
    expect(top.getByText('68편')).toBeInTheDocument();
    expect(top.getByText('김난다 님이 좋아할 만한 작품이 가장 많아요')).toBeInTheDocument();
    expect(screen.queryByText(/%/)).not.toBeInTheDocument();

    fireEvent.click(top.getByRole('link', { name: '상세 보기' }));
    expect(location()).toBe(`/otts/recommend/${NETFLIX.ottId}`);

    expect(await screen.findByRole('heading', { name: '넷플릭스' })).toBeInTheDocument();
    expect(screen.getByText('좋아할 만한 미감상 영화 · 미구독')).toBeInTheDocument();

    // 계약이 준 `movieId`라 2.4로 열 수 있다(목업 시절에는 막아 두었다)
    expect(screen.getByRole('link', { name: /세븐/ })).toHaveAttribute('href', '/movies/807');

    fireEvent.click(screen.getByRole('button', { name: '다른 OTT와 비교하기' }));
    expect(location()).toBe('/otts/recommend/result');

    fireEvent.click(await screen.findByRole('link', { name: /티빙/ }));
    expect(location()).toBe(`/otts/recommend/${TVING.ottId}`);

    fireEvent.click(await screen.findByRole('button', { name: '이 OTT 작품을 더 추천받기' }));
    expect(location()).toBe('/recommendations');
  }, 15000);

  it('분석 중 화면은 기록에 남지 않는다', async () => {
    renderApp([ROUTE_PATTERN.settings, ROUTE_PATTERN.ottFinder]);

    fireEvent.click(await screen.findByRole('button', { name: '내 OTT 취향 분석하기' }));
    await waitFor(() => expect(location()).toBe('/otts/recommend/result'), { timeout: 5000 });

    fireEvent.click(screen.getByRole('button', { name: '뒤로 가기' }));
    await waitFor(() => expect(location()).toBe('/otts/recommend'));
  }, 15000);
});

describe('6.1 ⇢ EXT-01 · EXT-03 ⇢ 6.1', () => {
  it('구독 표시는 응답의 subscribed를 따른다', async () => {
    renderApp([ROUTE_PATTERN.ottResult]);

    const top = within(await screen.findByRole('region', { name: '1위 넷플릭스' }));
    expect(top.getByText('1위 · 미구독')).toBeInTheDocument();

    // 구독 중인 것만 남긴다
    fireEvent.click(screen.getByRole('button', { name: '현재 구독 중인 OTT와 비교하기' }));
    const list = screen.getAllByRole('listitem');
    expect(list).toHaveLength(1);
    expect(list[0]).toHaveTextContent('티빙');

    fireEvent.click(screen.getByRole('link', { name: 'OTT 관리로 이동' }));
    expect(location()).toBe('/otts');

    fireEvent.click(await screen.findByRole('link', { name: '나에게 맞는 OTT 찾기' }));
    expect(location()).toBe('/otts/recommend');
  });
});

describe('EXT-02 ⇢ EXT-05', () => {
  /*
   * 계약은 현재 Candidate가 있으면 취향 입력이 0건이어도 `insufficientData: false`를 준다.
   * 그래서 화면은 두 경우를 함께 본다(업무 -563).
   */
  it.each([
    ['insufficientData가 참이면', { ...recommendation, insufficientData: true, items: [] }],
    ['items가 비어 있으면', { ...recommendation, items: [] }],
  ])(
    '%s 안내로 보내고, MY 안 남긴 영화 · 3.1로 이어진다',
    async (_name, response) => {
      vi.mocked(getOttRecommendations).mockResolvedValue(response);
      const { unmount } = renderApp([ROUTE_PATTERN.ottFinder]);

      fireEvent.click(await screen.findByRole('button', { name: '내 OTT 취향 분석하기' }));
      await waitFor(() => expect(location()).toBe('/otts/recommend/insufficient'), {
        timeout: 5000,
      });

      expect(screen.getByText(/영화를 더 평가하면/)).toBeInTheDocument();
      // 기준 평가 수가 계약에 없어 진행도 막대를 뺐다(업무 -563)
      expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();

      /*
       * 7.7 화면을 지우고 MY의 탭으로 옮겼다(업무 -495). 탭은 라우트가 아니라 `?tab=`이라
       * 경로는 `/my`이고 탭은 검색 문자열에 있다.
       */
      fireEvent.click(screen.getByRole('button', { name: '영화 평가하러 가기' }));
      expect(location()).toBe('/my');
      expect(search()).toBe('?tab=unrated');
      unmount();

      renderApp([ROUTE_PATTERN.ottInsufficient]);
      fireEvent.click(await screen.findByRole('button', { name: '취향 영화 추가하기' }));
      expect(location()).toBe('/film');
    },
    15000,
  );
});

describe('503 RECOMMENDATION_UNAVAILABLE', () => {
  const unavailable = () =>
    new ApiError(503, 'RECOMMENDATION_UNAVAILABLE', '추천 서비스를 일시적으로 사용할 수 없습니다.');

  /* 후보가 아직 없는 상태다. 장애가 아니라 준비 중으로 안내한다(2.2-A와 같은 처리) */
  it('EXT-03이 준비 중 안내를 보여준다', async () => {
    vi.mocked(getOttRecommendations).mockRejectedValue(unavailable());
    renderApp([ROUTE_PATTERN.ottResult]);

    expect(await screen.findByText('취향에 맞는 OTT 추천을 준비하고 있어요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 확인하기' })).toBeInTheDocument();
  });

  it('EXT-02는 결과로 넘어가지 않고 같은 안내로 멈춘다', async () => {
    vi.mocked(getOttRecommendations).mockRejectedValue(unavailable());
    renderApp([ROUTE_PATTERN.ottAnalyzing]);

    expect(
      await screen.findByRole('heading', { name: '취향에 맞는 OTT 추천을 준비하고 있어요' }),
    ).toBeInTheDocument();
    expect(location()).toBe('/otts/recommend/analyzing');
  }, 15000);
});
