/*
 * 나에게 맞는 OTT 찾기 — 이동 경로 (업무 S15P21E106-308)
 *
 * EXT-01 → EXT-02 → EXT-03 → EXT-04 사슬과 두 갈래(EXT-05 데이터 부족 · 6.1 OTT 관리)를 걷는다.
 *
 * - EXT-02는 응답을 기다린 뒤 EXT-03으로 **바꿔 끼운다.** 뒤로 가기가 분석 중으로 돌아오면
 *   분석이 다시 돈다
 * - 구독 여부는 6.1에서 바꾼 값이어야 한다. 목업이 고정값을 들고 있으면 6.1과 어긋난다
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
import HomePage from '../home/HomePage';
import { setMyOtts } from '../../api/otts';
import { getMyProfile } from '../../api/users';
import { resetSessionStore } from '../../mocks/sessionStore';
import { mockMyProfile } from '../../mocks/user';
import { ROUTE_PATTERN } from '../../routes/paths';

vi.mock('../../api/users', () => ({
  getMyProfile: vi.fn(),
}));

beforeEach(() => {
  resetSessionStore();
  vi.mocked(getMyProfile).mockReset().mockResolvedValue(mockMyProfile);
});

afterEach(() => {
  window.history.replaceState({}, '', '/');
});

function LocationProbe() {
  const location = useLocation();

  return <div data-testid="location">{location.pathname}</div>;
}

function renderApp(entries: string[]) {
  return render(
    <MemoryRouter initialEntries={entries} initialIndex={entries.length - 1}>
      <Routes>
        <Route path={ROUTE_PATTERN.home} element={<HomePage />} />
        <Route path={ROUTE_PATTERN.ottManage} element={<OttManagePage />} />
        <Route path={ROUTE_PATTERN.ottFinder} element={<OttFinderPage />} />
        <Route path={ROUTE_PATTERN.ottAnalyzing} element={<OttAnalyzingPage />} />
        <Route path={ROUTE_PATTERN.ottResult} element={<OttResultPage />} />
        <Route path={ROUTE_PATTERN.ottInsufficient} element={<OttInsufficientPage />} />
        <Route path={ROUTE_PATTERN.ottResultDetail} element={<OttResultDetailPage />} />
        <Route path="*" element={<p>다른 화면</p>} />
      </Routes>
      <LocationProbe />
    </MemoryRouter>,
  );
}

const location = () => screen.getByTestId('location').textContent;

describe('2.1 → EXT-01 → EXT-02 → EXT-03 → EXT-04', () => {
  it('분석을 마치면 결과로 바꿔 끼우고, 상세와 비교를 오간다', async () => {
    renderApp([ROUTE_PATTERN.home]);

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
    expect(location()).toBe('/otts/recommend/2');

    fireEvent.click(await screen.findByRole('button', { name: '이 OTT 작품을 더 추천받기' }));
    expect(location()).toBe('/recommendations');
  });

  it('분석 중 화면은 기록에 남지 않는다', async () => {
    renderApp([ROUTE_PATTERN.home, ROUTE_PATTERN.ottFinder]);

    fireEvent.click(await screen.findByRole('button', { name: '내 OTT 취향 분석하기' }));
    await waitFor(() => expect(location()).toBe('/otts/recommend/result'), { timeout: 5000 });

    fireEvent.click(screen.getByRole('button', { name: '뒤로 가기' }));
    await waitFor(() => expect(location()).toBe('/otts/recommend'));
  });
});

describe('6.1 ⇢ EXT-01 · EXT-03 ⇢ 6.1', () => {
  it('구독 여부는 6.1에서 바꾼 값을 따른다', async () => {
    // 넷플릭스를 끄고 티빙만 구독한다
    await setMyOtts([2]);
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
  it('평가가 부족하면 분석 대신 안내로 가고, 7.7 · 3.1로 이어진다', async () => {
    window.history.replaceState({}, '', '/?mock=ott-insufficient');
    const { unmount } = renderApp([ROUTE_PATTERN.ottFinder]);

    expect(await screen.findByText('6개')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '내 OTT 취향 분석하기' }));
    expect(location()).toBe('/otts/recommend/insufficient');

    expect(await screen.findByRole('progressbar', { name: '평가 진행도' })).toHaveAttribute(
      'aria-valuenow',
      '6',
    );

    fireEvent.click(screen.getByRole('button', { name: '영화 평가하러 가기' }));
    expect(location()).toBe('/my/ratings/unrated');
    unmount();

    renderApp([ROUTE_PATTERN.ottInsufficient]);
    fireEvent.click(await screen.findByRole('button', { name: '취향 영화 추가하기' }));
    expect(location()).toBe('/film');
  });
});
