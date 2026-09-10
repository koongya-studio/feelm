/*
 * 5.3 → 5.4 → 5.5 이어지는 흐름 (업무 S15P21E106-304)
 *
 * **화면 하나씩 열어 보면 멀쩡한데 이어서 하면 끊기던 자리다.** 기준 목업의 추천은
 * 파티 9001에만 있고 새로 만든 파티는 9100부터 번호를 받는다. 그래서 5.3에서 파티를
 * 만들고 5.4로 들어가면 추천 조회가 404가 되어 실패 화면에 머물렀다. 화면을 따로
 * 확인하면 9001로 열게 되니 드러나지 않는다.
 *
 * 이 시험은 **실제로 파티를 만든 다음** 그 번호로 5.4를 열어 5.5까지 가는지 본다.
 */
import { describe, expect, it, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { render } from '@testing-library/react';
import PartySetupPage from '../PartySetupPage';
import PartyAnalyzingPage from './index';
import PartyRecommendationPage from '../PartyRecommendationPage';
import { ROUTE_PATTERN } from '../../../routes/paths';
import { resetSessionStore } from '../../../mocks/sessionStore';

beforeEach(() => {
  resetSessionStore();
});

function LocationProbe() {
  const location = useLocation();

  return <div data-testid="location">{location.pathname}</div>;
}

/** 세 화면을 실제 라우트 구조로 함께 건다. 화면 사이 이동이 이 시험의 대상이다 */
function renderFlow() {
  return render(
    <MemoryRouter initialEntries={[ROUTE_PATTERN.partySetup]}>
      <Routes>
        <Route path={ROUTE_PATTERN.partySetup} element={<PartySetupPage />} />
        <Route path={ROUTE_PATTERN.partyAnalyzing} element={<PartyAnalyzingPage />} />
        <Route path={ROUTE_PATTERN.partyRecommendations} element={<PartyRecommendationPage />} />
      </Routes>
      <LocationProbe />
    </MemoryRouter>,
  );
}

describe('파티를 만들고 추천까지', () => {
  it('새로 만든 파티도 5.4를 지나 5.5에 닿는다', async () => {
    renderFlow();

    fireEvent.change(screen.getByLabelText('파티 이름'), {
      target: { value: '금요 심야 상영회' },
    });
    fireEvent.click(screen.getByRole('button', { name: '파티 시작하기' }));

    // 5.4 공통 필름 현상 중
    expect(await screen.findByText('공통 필름 현상 중')).toBeInTheDocument();

    // 5.5 추천 결과. 최소 노출 시간(1.2초)이 있어 넉넉히 기다린다
    expect(
      await screen.findByText('우리의 취향 필름이 완성됐어요', undefined, { timeout: 5000 }),
    ).toBeInTheDocument();

    expect(screen.getByText('함께 보기 1위')).toBeInTheDocument();
    expect(screen.getByTestId('location').textContent).toMatch(/^\/parties\/\d+\/recommendations$/);
  });

  it('실패 화면에 머물지 않는다', async () => {
    renderFlow();

    fireEvent.change(screen.getByLabelText('파티 이름'), { target: { value: '테스트 파티' } });
    fireEvent.click(screen.getByRole('button', { name: '파티 시작하기' }));

    await screen.findByText('공통 필름 현상 중');

    await waitFor(
      () => expect(screen.queryByText('공통 취향을 분석하지 못했어요')).not.toBeInTheDocument(),
      { timeout: 5000 },
    );
  });
});
