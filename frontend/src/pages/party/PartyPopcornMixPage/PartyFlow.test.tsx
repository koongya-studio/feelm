/*
 * 5.3 → 5.9 → 5.5 이어지는 흐름 (업무 S15P21E106-304 · -393 · -462 · -773)
 *
 * **화면 하나씩 열어 보면 멀쩡한데 이어서 하면 끊기던 자리다.** 파티 추천(API-058)이
 * 목업이던 동안 기준 목업의 추천은 9001에만 있어, 서버가 만든 파티는 모으기에서 실패 화면에
 * 머물렀다. 화면을 따로 확인하면 9001로 열게 되니 드러나지 않았다.
 *
 * 이 시험은 **실제로 파티를 만든 다음** 그 번호로 5.9를 열어 5.5까지 가는지 본다.
 * 파티 생성 · 조회 · 파티원 팝콘 · 추천(업무 -705부터 실제)은 가로채고, 추천이 만든 파티
 * 번호로 불리는지 본다.
 *
 * 한때 5.4 모으기와 5.9 섞기가 따로 된 화면이었고, 5.9는 `팝콘 섞기`를 눌러야 섞였다(사용자
 * 지시 2026-09-17). 업무 -773에서 둘을 한 화면으로 합쳤다 — 모으기에서 섞기까지 **누르지
 * 않고** 이어지고, 담기면 5.5로 간다(사용자 결정 2026-09-21).
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import PartySetupPage from '../PartySetupPage';
import PartyPopcornMixPage from './index';
import PartyRecommendationPage from '../PartyRecommendationPage';
import { ROUTE_PATTERN } from '../../../routes/paths';
import {
  createParty,
  getParty,
  getPartyPopcorn,
  getPartyRecommendations,
  inviteToParty,
} from '../../../api/parties';
import { getMyProfile, searchUsers } from '../../../api/users';
import { mockMyProfile } from '../../../mocks/user';
import { ME, member, party, partyPopcorn, partyRecommendations } from '../../../test/party';

/** 서버가 만든 파티 번호. 시험 기본 번호(9001)와 달라야 이 시험이 뜻을 갖는다 */
const NEW_PARTY_ID = 9137;

/** 5.3은 친구를 1명 이상 초대해야 시작할 수 있다(업무 -610). 검색으로 찾아 고른다 */
const BUDDY = { userId: 2048, nickname: '무비버디', visibility: 'PUBLIC' as const };

vi.mock('../../../api/parties', async () => {
  const actual =
    await vi.importActual<typeof import('../../../api/parties')>('../../../api/parties');

  return {
    ...actual,
    createParty: vi.fn(),
    getParty: vi.fn(),
    getPartyPopcorn: vi.fn(),
    getPartyRecommendations: vi.fn(),
    inviteToParty: vi.fn(),
  };
});

vi.mock('../../../api/users', async () => {
  const actual = await vi.importActual<typeof import('../../../api/users')>('../../../api/users');

  return { ...actual, getMyProfile: vi.fn(), searchUsers: vi.fn() };
});

beforeEach(() => {
  vi.mocked(getMyProfile).mockReset().mockResolvedValue(mockMyProfile);
  vi.mocked(searchUsers)
    .mockReset()
    .mockResolvedValue({ items: [BUDDY], nextCursor: null, hasNext: false });
  vi.mocked(inviteToParty).mockReset().mockResolvedValue({
    invitationId: 501,
    partyId: NEW_PARTY_ID,
    invitee: BUDDY,
    status: 'PENDING',
    createdAt: '2026-09-10T09:00:00Z',
  });
  vi.mocked(createParty)
    .mockReset()
    .mockResolvedValue(party({ partyId: NEW_PARTY_ID, name: '금요 심야 상영회' }));
  vi.mocked(getParty)
    .mockReset()
    .mockResolvedValue(party({ partyId: NEW_PARTY_ID, name: '금요 심야 상영회' }));
  vi.mocked(getPartyPopcorn)
    .mockReset()
    .mockResolvedValue({ ...partyPopcorn(), partyId: NEW_PARTY_ID });
  vi.mocked(getPartyRecommendations)
    .mockReset()
    .mockResolvedValue(partyRecommendations(NEW_PARTY_ID));
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
        <Route path={ROUTE_PATTERN.partyPopcornMix} element={<PartyPopcornMixPage />} />
        <Route path={ROUTE_PATTERN.partyRecommendations} element={<PartyRecommendationPage />} />
      </Routes>
      <LocationProbe />
    </MemoryRouter>,
  );
}

async function pickBuddy() {
  fireEvent.change(screen.getByLabelText('닉네임으로 친구 찾기'), { target: { value: '무비' } });
  fireEvent.click(screen.getByRole('button', { name: '검색' }));
  fireEvent.click(await screen.findByRole('button', { name: /무비버디/ }));
}

describe('파티를 만들고 추천까지', () => {
  it('새로 만든 파티는 초대 수락을 기다리고, 참여 확인 뒤 섞어 5.5에 닿는다', async () => {
    vi.mocked(getParty)
      .mockResolvedValueOnce(party({ partyId: NEW_PARTY_ID, members: [member(ME, 'OWNER')] }))
      .mockResolvedValueOnce(party({ partyId: NEW_PARTY_ID, members: [member(ME, 'OWNER')] }))
      .mockResolvedValue(party({ partyId: NEW_PARTY_ID }));
    renderFlow();

    fireEvent.change(screen.getByLabelText('파티 이름'), {
      target: { value: '금요 심야 상영회' },
    });
    await pickBuddy();
    fireEvent.click(screen.getByRole('button', { name: '파티 시작하기' }));

    expect(await screen.findByText('친구가 오면 함께 섞어요')).toBeInTheDocument();
    expect(screen.getByText('친구, 입장 대기')).toBeInTheDocument();
    expect(screen.getByText('2명 이상 모이면 자동으로 팝콘을 섞기 시작해요.')).toBeInTheDocument();
    expect(getPartyRecommendations).not.toHaveBeenCalled();
    expect(getPartyPopcorn).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '참여 확인하기' }));
    expect(
      await screen.findByText('아직 친구가 입장하지 않았어요. 계속 확인하고 있어요.'),
    ).toBeInTheDocument();
    expect(getPartyRecommendations).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '참여 확인하기' }));

    // 친구가 들어온 뒤 5.9 모으기. 따로 된 5.4 화면을 지나지 않는다
    expect(await screen.findByText('우리의 취향을 모아요')).toBeInTheDocument();
    expect(screen.getByTestId('location').textContent).toBe(`/parties/${NEW_PARTY_ID}/popcorn/mix`);
    expect(screen.queryByRole('button', { name: '팝콘 섞기' })).not.toBeInTheDocument();

    // 누르지 않아도 섞기로 이어진다
    expect(
      await screen.findByText('취향이 섞이는 중', undefined, { timeout: 5000 }),
    ).toBeInTheDocument();

    // 5.5 추천 완성. 섞는 장면(4.8초)까지 기다린다
    expect(
      await screen.findByText('우리에게 어울리는 3편', undefined, { timeout: 8000 }),
    ).toBeInTheDocument();

    expect(screen.getByTestId('location').textContent).toBe(
      `/parties/${NEW_PARTY_ID}/recommendations`,
    );
    expect(getPartyRecommendations).toHaveBeenCalledWith(NEW_PARTY_ID);
    // 모으기와 섞는 장면을 합치면 기본 제한(5초)을 넘는다
  }, 15000);

  it('실패 화면에 머물지 않는다', async () => {
    renderFlow();

    fireEvent.change(screen.getByLabelText('파티 이름'), { target: { value: '테스트 파티' } });
    await pickBuddy();
    fireEvent.click(screen.getByRole('button', { name: '파티 시작하기' }));

    await screen.findByText('우리의 취향을 모아요');

    await waitFor(
      () => expect(screen.queryByText('파티원의 팝콘을 모으지 못했어요')).not.toBeInTheDocument(),
      { timeout: 5000 },
    );
    await waitFor(
      () =>
        expect(screen.queryByText('함께 볼 영화를 아직 고르지 못했어요')).not.toBeInTheDocument(),
      { timeout: 5000 },
    );
  });
});
