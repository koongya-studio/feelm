/*
 * 온보딩 — 1.1 → 1.2 실제 API 연동 (업무 S15P21E106-379)
 *
 * 두 화면이 서버와 주고받는 값을 고정한다. 눈으로는 드러나지 않지만 어긋나면 조용히
 * 잘못 저장되는 자리다.
 *
 * - **카드 장수를 화면이 정해 보낸다.** 계약 기본값은 20장인데 1.1의 배치 좌표는
 *   10자리다. 요청에서 빠지면 카드가 같은 자리에 겹쳐 쌓인다
 * - **이미 온보딩을 마친 사용자는 1.1에 머물지 않는다.** API-023이 `409`를 주며,
 *   다시 할 수 없는 화면에 실패 안내를 세워 두지 않고 홈으로 보낸다
 * - **1.2는 `ottId`를 하드코딩하지 않는다.** 화면에 선 OTT도, 저장하는 식별자도
 *   API-025 응답에서 온다. 지원 목록과 식별자는 적재 결과로 정해진다
 * - **`구독 중인 OTT가 없어요`는 빈 배열 저장이다**(API-027 — 빈 배열이면 전체 해제).
 *   아무 요청도 보내지 않으면 서버는 구독을 고르지 않은 것과 구분하지 못한다
 *
 * 1.1의 카드는 배치 영역의 크기를 재야 그려진다. jsdom의 `ResizeObserver`는 크기를
 * 통보하지 않으므로(`src/test/setup.ts`) 대부분은 카드 자체가 아니라 **요청과 이동**을
 * 본다. 카드가 실제로 그려지는지 보는 자리에서만 크기를 통보하는 관측자로 바꾼다
 * (`withBoardSize`).
 *
 * 업무 S15P21E106-449에서 더한 것 — **카드가 없는 동안의 상태**다. 요청 전과 빈 목록이
 * 둘 다 `cards`가 빈 배열이라 예전에는 같은 화면(점선 원만 남은 배치 영역)이었고,
 * 그것이 이 업무가 받은 증상이다. 세 가지를 고정한다.
 * - 받기 전에는 준비 중임을 알린다
 * - 서버가 0장을 주는 것은 실패가 아니다. 오류가 아니라 빈 목록으로 알리고 건너뛰기를
 *   남긴다 — 고를 카드가 없어도 온보딩은 끝낼 수 있어야 한다
 * - 카드가 도착하면 배치 영역이 그때 붙는데, 크기 관측이 함께 붙지 않으면
 *   `board.width`가 0에 머물러 **한 장도 그려지지 않는다**
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import OnboardingCompletePage from './OnboardingCompletePage';
import OttOnboardingPage from './OttOnboardingPage';
import TasteOnboardingPage from './TasteOnboardingPage';
import { ApiError } from '../../api/apiError';
import OnboardingLayout from '../../layouts/OnboardingLayout';
import { mockOtts } from '../../mocks/otts';
import { ROUTE_PATTERN } from '../../routes/paths';

vi.mock('../../api/onboarding', () => ({
  getOnboardingCards: vi.fn(),
  submitOnboardingPreferences: vi.fn(),
}));

vi.mock('../../api/otts', () => ({
  getOtts: vi.fn(),
  getMyOttIds: vi.fn(),
  setMyOttIds: vi.fn(),
}));

const { getOnboardingCards, submitOnboardingPreferences } = await import('../../api/onboarding');
const { getOtts, setMyOttIds } = await import('../../api/otts');

const CARD = {
  movieId: 550,
  title: '파이트 클럽',
  posterUrl: null,
  releaseYear: 1999,
  genres: ['드라마'],
  flavorCode: 'FLAVOR_A',
};

const NETFLIX = mockOtts[0];
const WATCHA = mockOtts[2];

beforeEach(() => {
  vi.mocked(getOnboardingCards).mockReset().mockResolvedValue([CARD]);
  vi.mocked(submitOnboardingPreferences)
    .mockReset()
    .mockResolvedValue({ onboardingCompleted: true, savedCount: 0, skipped: true });
  vi.mocked(getOtts).mockReset().mockResolvedValue(mockOtts);
  vi.mocked(setMyOttIds)
    .mockReset()
    .mockImplementation((ottIds: number[]) => Promise.resolve(ottIds));
});

function LocationProbe() {
  const location = useLocation();

  return <div data-testid="location">{location.pathname}</div>;
}

function renderApp(entry: string) {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <Routes>
        <Route element={<OnboardingLayout />}>
          <Route path={ROUTE_PATTERN.onboardingTaste} element={<TasteOnboardingPage />} />
          <Route path={ROUTE_PATTERN.onboardingOtt} element={<OttOnboardingPage />} />
          <Route path={ROUTE_PATTERN.onboardingComplete} element={<OnboardingCompletePage />} />
        </Route>
        <Route path={ROUTE_PATTERN.film} element={<p>필름</p>} />
      </Routes>
      <LocationProbe />
    </MemoryRouter>,
  );
}

const location = () => screen.getByTestId('location').textContent;

/**
 * 배치 영역의 크기를 통보하는 관측자로 바꾼다.
 *
 * 기본 스텁은 아무것도 통보하지 않아 `board.width`가 0에 머문다. 1.1은 그 값이 0이면
 * 카드를 그리지 않으므로, 카드가 보이는지 보려면 크기가 와야 한다. 와이어프레임의
 * 배치 영역(412 x 653)을 쓴다.
 */
function withBoardSize() {
  const original = window.ResizeObserver;

  window.ResizeObserver = class {
    /* `private` 매개변수 속성은 `erasableSyntaxOnly`에서 쓸 수 없어 필드로 둔다 */
    notify: ResizeObserverCallback;

    constructor(notify: ResizeObserverCallback) {
      this.notify = notify;
    }

    observe(target: Element) {
      this.notify(
        [{ target, contentRect: { width: 412, height: 653 } } as ResizeObserverEntry],
        this as unknown as ResizeObserver,
      );
    }

    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;

  return () => {
    window.ResizeObserver = original;
  };
}

/** 1.1을 건너뛰어 1.2까지 간다. 1.2는 1.1을 거치지 않으면 되돌아간다 */
async function skipTaste() {
  fireEvent.click(await screen.findByRole('button', { name: '차차 찾아갈래요 (건너뛰기)' }));
  await waitFor(() => expect(location()).toBe(ROUTE_PATTERN.onboardingOtt));
}

describe('1.1 영화 취향 초기 설정', () => {
  it('화면이 늘어놓을 수 있는 장수를 요청한다', async () => {
    renderApp(ROUTE_PATTERN.onboardingTaste);

    await waitFor(() => expect(getOnboardingCards).toHaveBeenCalledWith(10));
  });

  it('건너뛰면 고른 값 없이 건너뛰기로 제출하고 1.2로 간다', async () => {
    renderApp(ROUTE_PATTERN.onboardingTaste);

    await skipTaste();

    expect(submitOnboardingPreferences).toHaveBeenCalledWith([], true);
  });

  it('카드가 도착하면 배치 영역에 그린다', async () => {
    const restore = withBoardSize();

    try {
      renderApp(ROUTE_PATTERN.onboardingTaste);

      // 카드의 표면 버튼이다. `⊖`(`제목 목록에서 제거`)와 갈라 보려면 이름을 그대로 쓴다
      expect(
        await screen.findByRole('button', { name: `${CARD.title}, 선택 안 함` }),
      ).toBeInTheDocument();
    } finally {
      restore();
    }
  });

  it('카드를 받기 전에는 준비 중임을 알린다', async () => {
    vi.mocked(getOnboardingCards).mockReturnValue(new Promise(() => {}));

    renderApp(ROUTE_PATTERN.onboardingTaste);

    expect(await screen.findByText('영화 카드를 준비하고 있어요')).toBeInTheDocument();
    expect(screen.queryByText('영화 카드를 불러오지 못했어요')).not.toBeInTheDocument();
  });

  it('서버가 0장을 주면 실패가 아니라 빈 목록으로 알리고 건너뛸 수 있게 한다', async () => {
    vi.mocked(getOnboardingCards).mockResolvedValue([]);

    renderApp(ROUTE_PATTERN.onboardingTaste);

    expect(await screen.findByText('지금은 보여드릴 영화가 없어요')).toBeInTheDocument();
    expect(screen.queryByText('영화 카드를 불러오지 못했어요')).not.toBeInTheDocument();

    await skipTaste();

    expect(submitOnboardingPreferences).toHaveBeenCalledWith([], true);
  });

  it('이미 온보딩을 마쳤으면 필름으로 보낸다', async () => {
    vi.mocked(getOnboardingCards).mockRejectedValue(
      new ApiError(409, 'ONBOARDING_ALREADY_COMPLETED', '이미 온보딩을 마쳤습니다.'),
    );

    renderApp(ROUTE_PATTERN.onboardingTaste);

    await waitFor(() => expect(location()).toBe(ROUTE_PATTERN.film));
    expect(screen.queryByText('영화 카드를 불러오지 못했어요')).not.toBeInTheDocument();
  });

  it('그 밖의 실패는 다시 시도할 수 있게 알린다', async () => {
    vi.mocked(getOnboardingCards).mockRejectedValue(new Error('실패'));

    renderApp(ROUTE_PATTERN.onboardingTaste);

    expect(await screen.findByText('영화 카드를 불러오지 못했어요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
  });
});

describe('1.2 구독 OTT 초기 설정', () => {
  it('API-025가 준 목록을 세우고 고른 식별자를 그대로 저장한다', async () => {
    renderApp(ROUTE_PATTERN.onboardingTaste);
    await skipTaste();

    // 지원 목록은 서버가 정한다. 화면이 개수나 식별자를 들고 있지 않다
    for (const ott of mockOtts) {
      expect(await screen.findByRole('button', { name: new RegExp(ott.name) })).toBeInTheDocument();
    }

    fireEvent.click(screen.getByRole('button', { name: new RegExp(NETFLIX.name) }));
    fireEvent.click(screen.getByRole('button', { name: new RegExp(WATCHA.name) }));
    fireEvent.click(screen.getByRole('button', { name: '선택 완료 (2개)' }));

    await waitFor(() => expect(setMyOttIds).toHaveBeenCalledWith([NETFLIX.ottId, WATCHA.ottId]));
    await waitFor(() => expect(location()).toBe(ROUTE_PATTERN.onboardingComplete));
  });

  it('`구독 중인 OTT가 없어요`는 빈 배열로 저장한다', async () => {
    renderApp(ROUTE_PATTERN.onboardingTaste);
    await skipTaste();

    fireEvent.click(await screen.findByRole('button', { name: '구독 중인 OTT가 없어요' }));

    await waitFor(() => expect(setMyOttIds).toHaveBeenCalledWith([]));
    await waitFor(() => expect(location()).toBe(ROUTE_PATTERN.onboardingComplete));
  });

  it('저장에 실패하면 알리고 그 자리에 머문다', async () => {
    vi.mocked(setMyOttIds).mockRejectedValue(
      new ApiError(400, 'VALIDATION_FAILED', '존재하지 않는 OTT입니다.'),
    );

    renderApp(ROUTE_PATTERN.onboardingTaste);
    await skipTaste();

    fireEvent.click(await screen.findByRole('button', { name: '구독 중인 OTT가 없어요' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('존재하지 않는 OTT입니다.');
    expect(location()).toBe(ROUTE_PATTERN.onboardingOtt);
  });
});
