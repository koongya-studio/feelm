/*
 * 5.1 파티팝콘 — 데스크톱 시안 (업무 S15P21E106-785)
 *
 * 넓은 화면에서 받은 초대와 참여 중인 파티가 시안의 자리에 서고, 고른 파티가 우측 보조
 * 패널을 따라오는지를 고정한다. 셸이 그 자리를 내놓아야 페이지가 포털로 보낼 수 있으므로
 * (업무 -782) 화면만이 아니라 셸까지 함께 그린다.
 *
 * **동작은 배치가 바뀌어도 그대로다.** 초대 수락(API-053) · 파티 이동(5.8) · 파티 만들기(5.2)가
 * 넓은 화면에서도 같은 곳으로 가는지 함께 본다 — 이 업무는 배치만 바꾼다.
 *
 * **412px은 한 줄도 바뀌지 않는다.** 그 회귀는 `PartyListPage.test.tsx`가 이미 지키고 있고,
 * 여기서는 좁은 폭에서 데스크톱 전용 요소가 새지 않는지만 확인한다.
 *
 * jsdom에는 레이아웃이 없어 폭 · 겹침 · 줄바꿈은 여기서 잡히지 않는다. 그것은 브라우저
 * 확인 기록(`frontend/verification/S15P21E106-785.md`)에 있다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import PartyListPage from './index';
import AppLayout from '../../../layouts/AppLayout';
import { AuthContext } from '../../../contexts/authContext';
import { resetViewport, setViewportWidth } from '../../../test/viewport';
import {
  BUDDY,
  flavors,
  invitation,
  ME,
  member,
  party,
  partyPopcorn,
  partyRecommendations,
} from '../../../test/party';
import { markPartyRecommendationSeen } from '../../../utils/partyRecommendationSeen';
import {
  acceptInvitation,
  getClosedParties,
  getMyInvitations,
  getMyParties,
  getPartyPopcorn,
  getPartyRecommendations,
} from '../../../api/parties';
import { getPopcornFlavors } from '../../../api/taste';
import { getUnreadNotificationCount } from '../../../api/notifications';
import { getMyProfile } from '../../../api/users';
import type { AuthValue } from '../../../contexts/authContext';

vi.mock('../../../api/parties', async () => {
  const actual =
    await vi.importActual<typeof import('../../../api/parties')>('../../../api/parties');

  return {
    ...actual,
    getMyParties: vi.fn(),
    getClosedParties: vi.fn(),
    getMyInvitations: vi.fn(),
    getPartyPopcorn: vi.fn(),
    getPartyRecommendations: vi.fn(),
    acceptInvitation: vi.fn(),
  };
});

vi.mock('../../../api/taste', async () => {
  const actual = await vi.importActual<typeof import('../../../api/taste')>('../../../api/taste');

  return { ...actual, getPopcornFlavors: vi.fn() };
});

vi.mock('../../../api/notifications', () => ({
  getUnreadNotificationCount: vi.fn(),
}));

vi.mock('../../../api/users', () => ({
  getMyProfile: vi.fn(),
}));

const auth: AuthValue = {
  status: 'authenticated',
  user: { userId: ME.userId, nickname: ME.nickname, onboardingCompleted: true },
  signIn: vi.fn(),
  signInWithSocial: vi.fn(),
  completeEmailSignup: vi.fn(),
  completeSocialSignup: vi.fn(),
  signOut: vi.fn(),
};

/** 목록의 둘째 파티. 고른 파티를 바꾸는 테스트가 쓴다 */
const SECOND = party({
  partyId: 9100,
  name: '주말 영화 모임',
  // 정원이 찬 파티다. `진행 중` · `모집 중`을 가르는 것이 정원뿐이다
  maxMembers: 2,
  members: [member(BUDDY, 'OWNER'), member({ userId: 3072, nickname: '팝콘러' })],
});

function LocationProbe() {
  const location = useLocation();

  return <div data-testid="location">{location.pathname}</div>;
}

function renderParty(width: number) {
  setViewportWidth(width);

  return render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={['/party']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/party" element={<PartyListPage />} />
            <Route path="/party/new" element={<p>5.2 파티 만들기</p>} />
            <Route path="/parties/:partyId/popcorn" element={<p>5.8 파티원 팝콘</p>} />
            <Route path="/parties/:partyId/popcorn/mix" element={<p>5.9 팝콘 섞기</p>} />
            <Route path="/parties/:partyId/recommendations" element={<p>5.5 추천 완성</p>} />
            <Route path="/parties/invitations/:invitationId" element={<p>5.6 파티 초대 수신</p>} />
          </Route>
        </Routes>
        <LocationProbe />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

beforeEach(() => {
  vi.mocked(getMyParties).mockReset().mockResolvedValue([party(), SECOND]);
  vi.mocked(getClosedParties).mockReset().mockResolvedValue([]);
  vi.mocked(getMyInvitations).mockReset().mockResolvedValue([invitation()]);
  vi.mocked(getPartyPopcorn).mockReset().mockResolvedValue(partyPopcorn());
  vi.mocked(getPartyRecommendations)
    .mockReset()
    .mockResolvedValue(partyRecommendations(party().partyId));
  // 기본은 아무 묶음도 본 적 없는 기기다
  localStorage.clear();
  vi.mocked(acceptInvitation).mockReset().mockResolvedValue(party());
  vi.mocked(getPopcornFlavors).mockReset().mockResolvedValue(flavors());
  vi.mocked(getUnreadNotificationCount).mockReset().mockResolvedValue(0);
  vi.mocked(getMyProfile).mockReset().mockRejectedValue(new Error('쓰지 않는다'));
});

afterEach(() => {
  resetViewport();
});

describe('5.1 파티팝콘 데스크톱 시안', () => {
  it('계약에 없는 초대 코드 참여 버튼을 노출하지 않는다', async () => {
    renderParty(1440);

    await screen.findByRole('heading', { name: '파티팝콘' });
    expect(screen.queryByRole('button', { name: '초대 코드로 참여하기' })).toBeNull();
  });

  it('받은 초대를 방장 · 때 · 인원과 함께 카드로 세운다', async () => {
    renderParty(1440);

    const invitations = await screen.findByRole('region', { name: /받은 초대/ });

    expect(within(invitations).getByText('무비버디 님의 파티')).toBeInTheDocument();
    // `memberCount`는 지금 참여 중인 사람 수라 나까지 세면 하나 더다(5.6과 같은 계산)
    expect(within(invitations).getByText(/2명 참여 중/)).toBeInTheDocument();
    expect(within(invitations).getByText('오늘 저녁 같이 볼 영화')).toBeInTheDocument();
    expect(within(invitations).getByRole('button', { name: '수락하기' })).toBeInTheDocument();
  });

  it('초대 카드에서 바로 수락하고 목록을 다시 읽는다', async () => {
    renderParty(1440);

    fireEvent.click(await screen.findByRole('button', { name: '수락하기' }));

    await waitFor(() => expect(acceptInvitation).toHaveBeenCalledWith(501));
    // 수락하면 참여 중인 파티와 받은 초대가 모두 달라진다
    await waitFor(() => expect(getMyParties).toHaveBeenCalledTimes(2));
    expect(getMyInvitations).toHaveBeenCalledTimes(2);
  });

  it('`자세히 보기`는 지금까지와 같이 5.6으로 간다', async () => {
    renderParty(1440);

    fireEvent.click(await screen.findByRole('button', { name: /자세히 보기/ }));

    expect(screen.getByTestId('location')).toHaveTextContent('/parties/invitations/501');
  });

  it('참여 중인 파티를 모두 카드로 세우고 첫 파티를 고른 것으로 둔다', async () => {
    renderParty(1440);

    const joined = await screen.findByRole('region', { name: /참여 중인 파티/ });
    const cards = within(joined).getAllByRole('button', { name: /상영회|모임/ });

    expect(cards).toHaveLength(2);
    expect(cards[0]).toHaveAttribute('aria-pressed', 'true');
    expect(cards[1]).toHaveAttribute('aria-pressed', 'false');
  });

  it('정원이 찼는지에 따라 `진행 중` · `모집 중`을 나눠 적는다', async () => {
    renderParty(1440);

    const joined = await screen.findByRole('region', { name: /참여 중인 파티/ });

    // 금요 심야 상영회는 2 / 4명, 주말 영화 모임은 2 / 2명이다
    expect(within(joined).getByText('모집 중')).toBeInTheDocument();
    expect(within(joined).getByText('진행 중')).toBeInTheDocument();
  });

  it('우측 보조 패널이 고른 파티의 멤버 · 공통 취향 · 인원 현황을 말한다', async () => {
    renderParty(1440);

    const aside = await screen.findByRole('complementary', { name: '보조 정보' });

    expect(within(aside).getByRole('heading', { name: '금요 심야 상영회' })).toBeInTheDocument();
    expect(within(aside).getByText('방장')).toBeInTheDocument();
    expect(within(aside).getByText('2 / 4명 참여')).toBeInTheDocument();
    // 공통 취향은 파티 팝콘(API-057)을 맛별로 합쳐 많은 셋이다
    expect(within(aside).getByText('맛1')).toBeInTheDocument();
    expect(within(aside).getByRole('button', { name: /파티 추천 보기/ })).toBeInTheDocument();
  });

  /*
   * 업무 -855. 5.8의 `함께 볼 영화 추천받기`와 같은 규칙이다 — 내가 이미 본 묶음이면 5.5를
   * 바로 열고, 아니면 5.9 팝콘 섞기로 간다(`PartyPopcornPage.test.tsx`가 5.8 쪽을 자세히 본다).
   */
  it('파티 추천 보기는 내가 아직 못 본 묶음이면 5.9 팝콘 섞기로 간다', async () => {
    renderParty(1440);

    const aside = await screen.findByRole('complementary', { name: '보조 정보' });
    fireEvent.click(within(aside).getByRole('button', { name: /파티 추천 보기/ }));

    expect(await screen.findByText('5.9 팝콘 섞기')).toBeInTheDocument();
    expect(screen.getByTestId('location').textContent).toBe(
      `/parties/${party().partyId}/popcorn/mix`,
    );
    expect(getPartyRecommendations).toHaveBeenCalledWith(party().partyId);
  });

  it('파티 추천 보기는 내가 이미 본 묶음이면 5.5를 바로 연다', async () => {
    markPartyRecommendationSeen(ME.userId, party().partyId, partyRecommendations(party().partyId));
    renderParty(1440);

    const aside = await screen.findByRole('complementary', { name: '보조 정보' });
    fireEvent.click(within(aside).getByRole('button', { name: /파티 추천 보기/ }));

    expect(await screen.findByText('5.5 추천 완성')).toBeInTheDocument();
    expect(screen.getByTestId('location').textContent).toBe(
      `/parties/${party().partyId}/recommendations`,
    );
    // 다시 받기(refresh)가 아니라 일반 조회 한 번이다
    expect(getPartyRecommendations).toHaveBeenCalledTimes(1);
    expect(getPartyRecommendations).toHaveBeenCalledWith(party().partyId);
  });

  it('우측 패널의 얼굴은 사진이 있으면 사진, 없으면 첫 글자다 (업무 -762)', async () => {
    const photo = '/media/avatars/nanda.jpg';
    vi.mocked(getMyParties).mockResolvedValue([
      party({ members: [member({ ...ME, avatarUrl: photo }, 'OWNER'), member(BUDDY)] }),
      SECOND,
    ]);
    renderParty(1440);

    const aside = await screen.findByRole('complementary', { name: '보조 정보' });

    await waitFor(() => expect(aside.querySelector(`img[src="${photo}"]`)).not.toBeNull());
    expect(within(aside).getByText('무')).toBeInTheDocument();
  });

  it('다른 파티 카드를 고르면 우측 패널이 그 파티를 말한다', async () => {
    renderParty(1440);

    const joined = await screen.findByRole('region', { name: /참여 중인 파티/ });

    fireEvent.click(within(joined).getByRole('button', { name: /주말 영화 모임/ }));

    const aside = screen.getByRole('complementary', { name: '보조 정보' });

    expect(within(aside).getByRole('heading', { name: '주말 영화 모임' })).toBeInTheDocument();
    expect(within(aside).getByText('2 / 2명 참여')).toBeInTheDocument();
  });

  it('`파티로 이동`은 지금까지와 같이 5.8로 간다', async () => {
    renderParty(1440);

    const joined = await screen.findByRole('region', { name: /참여 중인 파티/ });

    fireEvent.click(within(joined).getAllByRole('button', { name: /파티로 이동/ })[0]);

    expect(screen.getByTestId('location')).toHaveTextContent('/parties/9001/popcorn');
  });

  it('`파티 만들기`는 지금까지와 같이 5.2로 간다', async () => {
    renderParty(1440);

    fireEvent.click((await screen.findAllByRole('button', { name: /파티 만들기/ }))[0]);

    expect(screen.getByTestId('location')).toHaveTextContent('/party/new');
  });

  it('파티도 초대도 없으면 가운데에 안내를 둔다', async () => {
    vi.mocked(getMyParties).mockResolvedValue([]);
    vi.mocked(getMyInvitations).mockResolvedValue([]);

    renderParty(1440);

    expect(await screen.findByText('아직 참여 중인 파티가 없어요')).toBeInTheDocument();
    // 파티가 없어도 만들기를 권하는 카드는 오른쪽에 남는다(시안)
    const aside = screen.getByRole('complementary', { name: '보조 정보' });

    expect(
      within(aside).getByRole('heading', { name: '새로운 파티를 만들어보세요' }),
    ).toBeInTheDocument();
  });

  it('412px에서는 데스크톱 시안의 요소가 나오지 않는다', async () => {
    renderParty(412);

    // 412px은 강조 카드 하나와 그 뒤의 카드 줄이다(업무 -723)
    expect(await screen.findByRole('heading', { name: '금요 심야 상영회' })).toBeInTheDocument();
    expect(screen.queryByText('PARTY POPCORN')).not.toBeInTheDocument();
    expect(screen.queryByRole('complementary', { name: '보조 정보' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '수락하기' })).not.toBeInTheDocument();
  });
});

/*
 * 지난 파티 (업무 S15P21E106-826). 종료 파티로 들어가는 길이 **폭에 따라 있다가 없으면
 * 안 된다** — 412px과 같은 구역을 같은 자리에 둔다.
 */
describe('지난 파티', () => {
  it('넓은 화면에도 지난 파티 구역을 두고 5.10으로 보낸다', async () => {
    vi.mocked(getClosedParties).mockResolvedValue([
      party({ partyId: 9301, name: '지난달 심야 상영회', status: 'CLOSED' }),
    ]);

    renderParty(1440);

    expect(await screen.findByText('지난 파티 1')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /지난달 심야 상영회/ }));
    expect(screen.getByTestId('location').textContent).toBe(
      '/parties/9301/recommendations/history',
    );
  });

  it('끝난 파티가 없으면 구역을 그리지 않는다', async () => {
    renderParty(1440);

    expect(await screen.findByText('참여 중인 파티 2')).toBeInTheDocument();
    expect(screen.queryByText(/지난 파티/)).toBeNull();
  });
});
