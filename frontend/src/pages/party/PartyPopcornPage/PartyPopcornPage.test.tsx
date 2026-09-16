/*
 * 5.8 파티원 팝콘 (업무 S15P21E106-305 · -393)
 *
 * 두 가지를 고정한다.
 *
 * **7.2-B 진입점.** 와이어프레임 이동표에 7.2-B로 들어오는 화살표가 아예 없었다. 5.8에서
 * 들어가기로 확정하면서(2026-09-11) 이 화면에 붙였다. 진입점이 사라지면 7.2-B와 7.2-C가
 * 주소로만 닿는 화면이 된다(screen-spec-my.md 2장 8번). 내 카드에는 붙이지 않는다.
 *
 * **파티 관리.** 나가기(API-055) · 종료(API-056) · 내보내기(API-069)도 와이어프레임에
 * 자리가 없어 5.8에 두기로 했다(사용자 결정 2026-09-16). 셋 다 되돌릴 계약이 없어
 * 확인을 한 번 받고, 종료와 내보내기는 **파티장에게만** 보인다. 화면을 열어 보는 것만으로는
 * 파티장이 아닌 사람에게도 보이는지 알 수 없다.
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import PartyPopcornPage from './index';
import { renderWithRouter } from '../../../test/render';
import {
  closeParty,
  getParty,
  getPartyPopcorn,
  kickPartyMember,
  leaveParty,
} from '../../../api/parties';
import { getPopcornFlavors } from '../../../api/taste';
import { getMyProfile } from '../../../api/users';
import { mockMyProfile } from '../../../mocks/user';
import {
  BUDDY,
  flavors,
  ME,
  member,
  party,
  partyPopcorn,
  TEST_PARTY_ID,
} from '../../../test/party';

vi.mock('../../../api/parties', () => ({
  getParty: vi.fn(),
  getPartyPopcorn: vi.fn(),
  leaveParty: vi.fn(),
  closeParty: vi.fn(),
  kickPartyMember: vi.fn(),
}));
vi.mock('../../../api/taste', () => ({ getPopcornFlavors: vi.fn() }));
vi.mock('../../../api/users', () => ({ getMyProfile: vi.fn() }));

const partyState = {
  partyId: TEST_PARTY_ID,
  status: 'ACTIVE' as const,
  ownerId: ME.userId,
  closedReason: null,
  closedAt: null,
};

beforeEach(() => {
  vi.mocked(getMyProfile)
    .mockReset()
    .mockResolvedValue({ ...mockMyProfile, userId: ME.userId, nickname: ME.nickname });
  vi.mocked(getPartyPopcorn).mockReset().mockResolvedValue(partyPopcorn());
  vi.mocked(getPopcornFlavors).mockReset().mockResolvedValue(flavors());
  vi.mocked(leaveParty).mockReset().mockResolvedValue(partyState);
  vi.mocked(closeParty)
    .mockReset()
    .mockResolvedValue({ ...partyState, status: 'CLOSED', closedReason: 'OWNER_CLOSED' });
  vi.mocked(kickPartyMember).mockReset().mockResolvedValue(partyState);
  // 기본은 내가 파티장인 파티다
  vi.mocked(getParty)
    .mockReset()
    .mockResolvedValue(party({ members: [member(ME, 'OWNER'), member(BUDDY)] }));
});

async function renderPopcorn() {
  const result = renderWithRouter(<PartyPopcornPage />, {
    route: `/parties/${TEST_PARTY_ID}/popcorn`,
    path: '/parties/:partyId/popcorn',
  });
  await screen.findByText('서로 겹치는 맛');

  return result;
}

describe('5.8 파티원 팝콘 — 공개 프로필 진입점', () => {
  it('파티원을 눌러 그 사람의 공개 프로필로 갈 수 있다', async () => {
    await renderPopcorn();

    expect(await screen.findByRole('link', { name: /무비버디 님의 취향 보기/ })).toHaveAttribute(
      'href',
      `/users/${BUDDY.userId}`,
    );
  });

  it('내 카드에는 진입점을 두지 않는다', async () => {
    await renderPopcorn();

    await screen.findByRole('link', { name: /무비버디 님의 취향 보기/ });
    expect(screen.queryByRole('link', { name: /김난다 님의 취향 보기/ })).not.toBeInTheDocument();
  });
});

describe('5.8 파티 관리', () => {
  it('파티장이면 종료와 내보내기가 보인다', async () => {
    await renderPopcorn();

    expect(await screen.findByRole('button', { name: '파티 종료하기' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '내보내기' })).toBeInTheDocument();
  });

  it('파티장이 아니면 나가기만 보인다', async () => {
    vi.mocked(getParty).mockResolvedValue(party({ members: [member(BUDDY, 'OWNER'), member(ME)] }));

    await renderPopcorn();

    expect(await screen.findByRole('button', { name: '파티 나가기' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '파티 종료하기' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '내보내기' })).not.toBeInTheDocument();
  });

  it('나가기는 확인을 받은 뒤에만 보낸다', async () => {
    await renderPopcorn();

    fireEvent.click(screen.getByRole('button', { name: '파티 나가기' }));

    expect(await screen.findByText(/파티에서 나갈까요/)).toBeInTheDocument();
    expect(leaveParty).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: '네, 할게요' }));

    await waitFor(() => expect(leaveParty).toHaveBeenCalledWith(TEST_PARTY_ID));
    // 나간 파티는 더 볼 수 없다. 5.1로 보낸다
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/party'));
  });

  it('취소하면 아무것도 보내지 않는다', async () => {
    await renderPopcorn();

    fireEvent.click(screen.getByRole('button', { name: '파티 나가기' }));
    fireEvent.click(await screen.findByRole('button', { name: '취소' }));

    expect(leaveParty).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: '파티 나가기' })).toBeInTheDocument();
  });

  it('내보내기는 대상 닉네임을 확인시킨다', async () => {
    await renderPopcorn();

    fireEvent.click(screen.getByRole('button', { name: '내보내기' }));

    // API-069 처리 규칙: 실행 전에 대상 닉네임과 강퇴 사실을 확인시킨다
    expect(await screen.findByText(/무비버디 님을 파티에서 내보낼까요/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '네, 할게요' }));

    await waitFor(() => expect(kickPartyMember).toHaveBeenCalledWith(TEST_PARTY_ID, BUDDY.userId));
    // 파티는 남아 있다. 목록을 다시 읽는다
    expect(screen.getByTestId('location').textContent).toBe(`/parties/${TEST_PARTY_ID}/popcorn`);
  });

  it('종료가 거절당하면 이유를 알리고 화면에 머문다', async () => {
    const { ApiError } = await import('../../../api/apiError');
    vi.mocked(closeParty).mockRejectedValue(
      new ApiError(403, 'FORBIDDEN', '파티장만 종료할 수 있습니다.'),
    );

    await renderPopcorn();

    fireEvent.click(await screen.findByRole('button', { name: '파티 종료하기' }));
    fireEvent.click(await screen.findByRole('button', { name: '네, 할게요' }));

    expect(await screen.findByText('파티장만 종료할 수 있습니다.')).toBeInTheDocument();
    expect(screen.getByTestId('location').textContent).toBe(`/parties/${TEST_PARTY_ID}/popcorn`);
  });
});
