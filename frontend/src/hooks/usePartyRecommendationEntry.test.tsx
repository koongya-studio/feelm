/*
 * 파티 추천 진입 (업무 S15P21E106-855)
 *
 * 어디로 가는지(5.5 · 5.9)는 5.8 · 데스크톱 보조 패널 테스트가 본다. 여기서는 화면 테스트로
 * 잡기 어려운 것을 고정한다.
 *
 * - 확인하는 동안 다시 눌러도 한 번만 묻는다
 * - 확인하는 사이 화면을 떠났으면 끌고 가지 않는다
 * - 파티원이 모자라면 추천을 부르지 않고 5.9로 간다 — 5.9가 친구의 입장을 기다린다
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { Link, MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { usePartyRecommendationEntry } from './usePartyRecommendationEntry';
import { renderWithRouter } from '../test/render';
import { getPartyRecommendations } from '../api/parties';
import { AuthContext } from '../contexts/authContext';
import { testAuth } from '../test/auth';
import { partyRecommendations, TEST_PARTY_ID } from '../test/party';
import { markPartyRecommendationSeen } from '../utils/partyRecommendationSeen';
import type { PartyRecommendationSet } from '../types/party';

vi.mock('../api/parties', async () => {
  const actual = await vi.importActual<typeof import('../api/parties')>('../api/parties');

  return { ...actual, getPartyRecommendations: vi.fn() };
});

function Entry(props: { memberCount?: number }) {
  return (
    <AuthContext.Provider value={testAuth}>
      <EntryButton {...props} />
    </AuthContext.Provider>
  );
}

function EntryButton({ memberCount = 2 }: { memberCount?: number }) {
  const { open, checking } = usePartyRecommendationEntry();

  return (
    <button
      type="button"
      aria-busy={checking || undefined}
      onClick={() => open(TEST_PARTY_ID, memberCount)}
    >
      추천
    </button>
  );
}

const location = () => screen.getByTestId('location').textContent;

/** 시험이 직접 푸는 API-058 응답 */
function holdRecommendation() {
  let answer: (set: PartyRecommendationSet) => void = () => undefined;

  vi.mocked(getPartyRecommendations).mockImplementation(
    () =>
      new Promise((resolve) => {
        answer = resolve;
      }),
  );

  return (set: PartyRecommendationSet) => answer(set);
}

beforeEach(() => {
  vi.mocked(getPartyRecommendations).mockReset();
  // 이 계정은 지금 묶음을 이미 봤다. 확인이 끝나면 5.5로 간다
  localStorage.clear();
  markPartyRecommendationSeen(testAuth.user!.userId, TEST_PARTY_ID, partyRecommendations());
});

describe('usePartyRecommendationEntry', () => {
  it('확인하는 동안은 바쁨을 알리고, 다시 눌러도 한 번만 묻는다', async () => {
    const answer = holdRecommendation();

    renderWithRouter(<Entry />, { route: '/party' });

    fireEvent.click(screen.getByRole('button', { name: '추천' }));
    fireEvent.click(screen.getByRole('button', { name: '추천' }));

    expect(screen.getByRole('button', { name: '추천' })).toHaveAttribute('aria-busy', 'true');
    expect(getPartyRecommendations).toHaveBeenCalledTimes(1);

    await act(async () => answer(partyRecommendations()));

    expect(location()).toBe(`/parties/${TEST_PARTY_ID}/recommendations`);
  });

  it('확인하는 사이 화면을 떠나면 끌고 가지 않는다', async () => {
    const answer = holdRecommendation();

    function Probe() {
      return <div data-testid="location">{useLocation().pathname}</div>;
    }

    render(
      <MemoryRouter initialEntries={['/party']} useTransitions={false}>
        <Routes>
          <Route
            path="/party"
            element={
              <>
                <Entry />
                <Link to="/film">필름</Link>
              </>
            }
          />
          <Route path="/film" element={<p>필름 화면</p>} />
        </Routes>
        <Probe />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('button', { name: '추천' }));
    // 이력이 오기 전에 다른 탭으로 옮겼다
    fireEvent.click(screen.getByRole('link', { name: '필름' }));
    expect(location()).toBe('/film');

    await act(async () => answer(partyRecommendations()));

    expect(location()).toBe('/film');
  });

  it('파티원이 모자라면 추천을 부르지 않고 5.9로 간다', () => {
    renderWithRouter(<Entry memberCount={1} />, { route: '/party' });

    fireEvent.click(screen.getByRole('button', { name: '추천' }));

    expect(location()).toBe(`/parties/${TEST_PARTY_ID}/popcorn/mix`);
    expect(getPartyRecommendations).not.toHaveBeenCalled();
  });
});
