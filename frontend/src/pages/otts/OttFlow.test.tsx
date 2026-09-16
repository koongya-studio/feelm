/*
 * 나에게 맞는 OTT 찾기 — 이동 경로 (업무 S15P21E106-308)
 *
 * EXT-01 → EXT-02 → EXT-03 → EXT-04 사슬과 두 갈래(EXT-05 데이터 부족 · 6.1 OTT 관리)를 걷는다.
 *
 * 사슬의 출발은 **7.8 설정의 `나에게 맞는 OTT 찾기` 배너**다. 홈에 있던 것을 홈을 없애면서
 * `OTT 관리`(6.1) 옆으로 옮겼다(업무 -461).
 *
 * - EXT-02는 응답을 기다린 뒤 EXT-03으로 **바꿔 끼운다.** 뒤로 가기가 분석 중으로 돌아오면
 *   분석이 다시 돈다
 * - 구독 여부는 6.1에서 바꾼 값이어야 한다. 목업이 고정값을 들고 있으면 6.1과 어긋난다.
 *   연동(-379) 뒤에는 그 값이 API-026이므로 OTT 도메인 함수를 대신 세운다
 * - EXT-05는 목업 입력(`?mock=ott-insufficient`)으로 연다. 그 입력은 브라우저 주소에서 읽으므로
 *   jsdom 주소를 바꾼다
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
import { AuthContext } from '../../contexts/authContext';
import { getMyProfile } from '../../api/users';
import { mockOtts } from '../../mocks/otts';
import { resetSessionStore } from '../../mocks/sessionStore';
import { mockMyProfile } from '../../mocks/user';
import { ROUTE_PATTERN } from '../../routes/paths';
import type { AuthValue } from '../../contexts/authContext';

vi.mock('../../api/users', () => ({
  getMyProfile: vi.fn(),
}));

vi.mock('../../api/otts', () => ({
  getOtts: vi.fn(),
  getMyOttIds: vi.fn(),
  setMyOttIds: vi.fn(),
}));

const { getOtts, getMyOttIds } = await import('../../api/otts');

/** API-025가 주는 확정 5종. 화면도 목업 데이터도 이 식별자를 쓴다 */
const TVING = mockOtts[4];

beforeEach(() => {
  resetSessionStore();
  vi.mocked(getMyProfile).mockReset().mockResolvedValue(mockMyProfile);
  vi.mocked(getOtts).mockReset().mockResolvedValue(mockOtts);
  // 구독을 설정한 적이 없는 사용자로 시작한다(API-026 — 빈 배열)
  vi.mocked(getMyOttIds).mockReset().mockResolvedValue([]);
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
   * 분석을 기다리는 두 테스트의 제한을 늘린다.
   */
  it('분석을 마치면 결과로 바꿔 끼우고, 상세와 비교를 오간다', async () => {
    renderApp([ROUTE_PATTERN.settings]);

    fireEvent.click(await screen.findByRole('link', { name: 'OTT 추천받기' }));
    expect(location()).toBe('/otts/recommend');

    fireEvent.click(await screen.findByRole('button', { name: '내 OTT 취향 분석하기' }));
    expect(location()).toBe('/otts/recommend/analyzing');
    expect(screen.getByText('필름을 감는 중이에요 · 잠시만요')).toBeInTheDocument();

    await waitFor(() => expect(location()).toBe('/otts/recommend/result'), { timeout: 5000 });

    const top = within(await screen.findByRole('region', { name: '1위 넷플릭스' }));
    expect(top.getByText('92%')).toBeInTheDocument();
    expect(top.getByText('김난다 님이 좋아할 만한 작품 68편')).toBeInTheDocument();

    fireEvent.click(top.getByRole('link', { name: '상세 보기' }));
    expect(location()).toBe('/otts/recommend/1');

    expect(
      await screen.findByRole('heading', { name: '넷플릭스 · 취향 일치 92%' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('progressbar', { name: '캐릭터 취향' })).toHaveAttribute(
      'aria-valuenow',
      '91',
    );

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
  it('구독 여부는 6.1에서 바꾼 값을 따른다', async () => {
    // 넷플릭스를 끄고 티빙만 구독한다
    vi.mocked(getMyOttIds).mockResolvedValue([TVING.ottId]);
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

describe('EXT-01 ⇢ EXT-05', () => {
  it('평가가 부족하면 분석 대신 안내로 가고, MY 안 남긴 영화 · 3.1로 이어진다', async () => {
    window.history.replaceState({}, '', '/?mock=ott-insufficient');
    const { unmount } = renderApp([ROUTE_PATTERN.ottFinder]);

    expect(await screen.findByText('6개')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '내 OTT 취향 분석하기' }));
    expect(location()).toBe('/otts/recommend/insufficient');

    expect(await screen.findByRole('progressbar', { name: '평가 진행도' })).toHaveAttribute(
      'aria-valuenow',
      '6',
    );

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
  });
});
