/*
 * 5.3 → 5.4 → 5.9 → 5.5 이어지는 흐름 (업무 S15P21E106-304 · -393 · -462)
 *
 * **화면 하나씩 열어 보면 멀쩡한데 이어서 하면 끊기던 자리다.** 파티 추천(API-058)은
 * 백엔드에 아직 없어 목업으로 남아 있고, 기준 목업의 추천은 9001에만 있다. 서버가 만든
 * 파티는 다른 번호를 받으므로 그대로 두면 5.4가 언제나 실패 화면에 머문다. 화면을 따로
 * 확인하면 9001로 열게 되니 드러나지 않는다.
 *
 * 이 시험은 **실제로 파티를 만든 다음** 그 번호로 5.4를 열어 5.5까지 가는지 본다.
 * 파티 생성 · 조회 · 파티원 팝콘은 실제 API라 가로채고, 추천은 목업 그대로 둔다.
 *
 * 사용자 시안(업무 -462)에서 가운데에 5.9 팝콘 섞기가 들어왔다. 5.4는 스스로 넘어가고
 * **5.9는 `팝콘 섞기`를 눌러야** 섞는 장면이 돈 뒤 5.5로 간다(사용자 지시 2026-09-17).
 * 한 군데만 끊겨도 흐름이 멈추는데, 화면을 따로 열면 드러나지 않는다.
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import PartySetupPage from '../PartySetupPage';
import PartyAnalyzingPage from './index';
import PartyPopcornMixPage from '../PartyPopcornMixPage';
import PartyRecommendationPage from '../PartyRecommendationPage';
import { ROUTE_PATTERN } from '../../../routes/paths';
import { createParty, getParty, getPartyPopcorn } from '../../../api/parties';
import { getMyProfile } from '../../../api/users';
import { mockMyProfile } from '../../../mocks/user';
import { party, partyPopcorn } from '../../../test/party';

/** 서버가 만든 파티 번호. 목업 추천(9001)과 다른 번호여야 이 시험이 뜻을 갖는다 */
const NEW_PARTY_ID = 9137;

vi.mock('../../../api/parties', async () => {
  const actual =
    await vi.importActual<typeof import('../../../api/parties')>('../../../api/parties');

  return {
    ...actual,
    createParty: vi.fn(),
    getParty: vi.fn(),
    getPartyPopcorn: vi.fn(),
    inviteToParty: vi.fn(),
  };
});

vi.mock('../../../api/users', async () => {
  const actual = await vi.importActual<typeof import('../../../api/users')>('../../../api/users');

  return { ...actual, getMyProfile: vi.fn(), searchUsers: vi.fn() };
});

beforeEach(() => {
  vi.mocked(getMyProfile).mockReset().mockResolvedValue(mockMyProfile);
  vi.mocked(createParty)
    .mockReset()
    .mockResolvedValue(party({ partyId: NEW_PARTY_ID, name: '금요 심야 상영회' }));
  vi.mocked(getParty)
    .mockReset()
    .mockResolvedValue(party({ partyId: NEW_PARTY_ID, name: '금요 심야 상영회' }));
  vi.mocked(getPartyPopcorn)
    .mockReset()
    .mockResolvedValue({ ...partyPopcorn(), partyId: NEW_PARTY_ID });
});

function LocationProbe() {
  const location = useLocation();

  return <div data-testid="location">{location.pathname}</div>;
}

/** 네 화면을 실제 라우트 구조로 함께 건다. 화면 사이 이동이 이 시험의 대상이다 */
function renderFlow() {
  return render(
    <MemoryRouter initialEntries={[ROUTE_PATTERN.partySetup]}>
      <Routes>
        <Route path={ROUTE_PATTERN.partySetup} element={<PartySetupPage />} />
        <Route path={ROUTE_PATTERN.partyAnalyzing} element={<PartyAnalyzingPage />} />
        <Route path={ROUTE_PATTERN.partyPopcornMix} element={<PartyPopcornMixPage />} />
        <Route path={ROUTE_PATTERN.partyRecommendations} element={<PartyRecommendationPage />} />
      </Routes>
      <LocationProbe />
    </MemoryRouter>,
  );
}

describe('파티를 만들고 추천까지', () => {
  it('새로 만든 파티도 5.4 · 5.9를 지나 5.5에 닿는다', async () => {
    renderFlow();

    fireEvent.change(screen.getByLabelText('파티 이름'), {
      target: { value: '금요 심야 상영회' },
    });
    fireEvent.click(screen.getByRole('button', { name: '파티 시작하기' }));

    // 5.4 취향 모으기
    expect(await screen.findByText('우리의 취향을 모아요')).toBeInTheDocument();

    // 5.9 팝콘 섞기. 통이 보이는 시간(1초)이 있어 넉넉히 기다린다
    expect(
      await screen.findByText('취향을 섞어 볼까요?', undefined, { timeout: 5000 }),
    ).toBeInTheDocument();

    // 섞는 장면은 사용자가 시작한다. 버튼은 파티원 팝콘을 받은 뒤에 나온다
    fireEvent.click(await screen.findByRole('button', { name: '팝콘 섞기' }));

    expect(await screen.findByText('취향이 섞이는 중')).toBeInTheDocument();

    // 5.5 추천 완성. 섞는 장면(4.3초)까지 기다린다
    expect(
      await screen.findByText('우리에게 어울리는 3편', undefined, { timeout: 8000 }),
    ).toBeInTheDocument();

    expect(screen.getByTestId('location').textContent).toBe(
      `/parties/${NEW_PARTY_ID}/recommendations`,
    );
    // 5.4의 노출 시간과 5.9의 섞는 장면을 합치면 기본 제한(5초)을 넘는다
  }, 15000);

  it('실패 화면에 머물지 않는다', async () => {
    renderFlow();

    fireEvent.change(screen.getByLabelText('파티 이름'), { target: { value: '테스트 파티' } });
    fireEvent.click(screen.getByRole('button', { name: '파티 시작하기' }));

    await screen.findByText('우리의 취향을 모아요');

    await waitFor(
      () => expect(screen.queryByText('파티원의 팝콘을 모으지 못했어요')).not.toBeInTheDocument(),
      { timeout: 5000 },
    );
    await waitFor(
      () => expect(screen.queryByText('팝콘을 섞지 못했어요')).not.toBeInTheDocument(),
      {
        timeout: 5000,
      },
    );
  });
});
