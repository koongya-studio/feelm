/*
 * 5.8 파티원 팝콘 (업무 S15P21E106-305 · -393 · -462 · -598)
 *
 * 네 가지를 고정한다.
 *
 * **친구 고르기.** 사용자 시안(업무 -462)에서 이 화면은 한 번에 한 사람의 통을
 * 보여준다. 칩을 눌러 사람이 바뀌는지는 화면을 열어 보는 것만으로는 알 수 없다.
 *
 * **차단 진입점.** 7.2-B로 가던 `취향 보기`를 업무 -598에서 걷어냈다 — API-062가 2차
 * MVP라 실제 파티원 번호로는 늘 실패 화면이었다. 그 링크가 7.2-B의 유일한 진입점이었고
 * 차단(API-020)을 거는 자리도 7.2-B뿐이었으므로, 차단과 신고를 이 화면의 `···` 메뉴로
 * 옮겼다(사용자 결정 2026-09-18). 링크가 **없다는 것**과 차단이 **고른 친구**를 대상으로
 * 한다는 것은 화면을 열어 보는 것만으로는 알 수 없다.
 *
 * **파티팝콘 흐름의 입구.** `함께 볼 영화 추천받기`가 5.9로 간다. 모으기와 섞기가 그 한
 * 화면에서 이어지고 저절로 5.5까지 간다(업무 -773). 한때 5.4가 따로 있어 장면이 끊겼다.
 *
 * **파티 관리.** 나가기(API-055) · 종료(API-056) · 내보내기(API-069)도 와이어프레임에
 * 자리가 없어 5.8에 두기로 했다(사용자 결정 2026-09-16). 시안이 헤더 `···` 메뉴로
 * 옮겼고, 셋 다 되돌릴 계약이 없어 확인을 한 번 받는다. 종료와 내보내기는
 * **파티장에게만** 보인다.
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import PartyPopcornPage from './index';
import { renderWithRouter } from '../../../test/render';
import {
  closeParty,
  getParty,
  getPartyPopcorn,
  inviteToParty,
  kickPartyMember,
  leaveParty,
} from '../../../api/parties';
import { blockUser } from '../../../api/settings';
import { getPopcornFlavors } from '../../../api/taste';
import { getMyProfile, searchUsers } from '../../../api/users';
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

// 정원 상수(PARTY_MAX_MEMBERS)를 화면이 읽으므로 나머지는 실제 것을 남긴다
vi.mock('../../../api/parties', async () => {
  const actual =
    await vi.importActual<typeof import('../../../api/parties')>('../../../api/parties');

  return {
    ...actual,
    getParty: vi.fn(),
    getPartyPopcorn: vi.fn(),
    inviteToParty: vi.fn(),
    leaveParty: vi.fn(),
    closeParty: vi.fn(),
    kickPartyMember: vi.fn(),
  };
});
vi.mock('../../../api/taste', () => ({ getPopcornFlavors: vi.fn() }));
vi.mock('../../../api/settings', () => ({ blockUser: vi.fn() }));
// 초대 검색이 닉네임 형식 검사(isSearchableNickname)를 실제로 쓴다
vi.mock('../../../api/users', async () => {
  const actual = await vi.importActual<typeof import('../../../api/users')>('../../../api/users');

  return { ...actual, getMyProfile: vi.fn(), searchUsers: vi.fn() };
});

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
  vi.mocked(searchUsers).mockReset();
  vi.mocked(inviteToParty).mockReset();
  vi.mocked(leaveParty).mockReset().mockResolvedValue(partyState);
  vi.mocked(closeParty)
    .mockReset()
    .mockResolvedValue({ ...partyState, status: 'CLOSED', closedReason: 'OWNER_CLOSED' });
  vi.mocked(kickPartyMember).mockReset().mockResolvedValue(partyState);
  vi.mocked(blockUser)
    .mockReset()
    .mockResolvedValue({ blockedUserId: BUDDY.userId, blockedAt: '2026-09-18T00:00:00Z' });
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
  // 첫 파티원의 통이 먼저 열린다
  await screen.findByRole('heading', { name: `${ME.nickname}의 팝콘` });

  return result;
}

/** 헤더 `···`를 눌러 파티 관리 메뉴를 연다 */
async function openManageMenu() {
  fireEvent.click(screen.getByRole('button', { name: '파티 관리' }));
  await screen.findByRole('button', { name: '파티 나가기' });
}

describe('5.8 파티원 팝콘 — 친구 고르기', () => {
  it('첫 파티원의 팝콘을 보여주고 칩으로 사람을 바꾼다', async () => {
    await renderPopcorn();

    // 통의 이름이 알 수를 말한다. 그림은 30알로 요약해 그린다(`design-concept.md` 2.10)
    expect(screen.getByRole('img', { name: `${ME.nickname}의 팝콘 32알` })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: new RegExp(BUDDY.nickname) }));

    expect(
      await screen.findByRole('heading', { name: `${BUDDY.nickname}의 팝콘` }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('heading', { name: `${ME.nickname}의 팝콘` }),
    ).not.toBeInTheDocument();
  });

  /*
   * 통은 30알로 요약해 그린다(`design-concept.md` 2.10). 실제 알 수는 범례가 말해야
   * 하므로 펼친 목록이 사라지면 그 수를 아는 방법이 없어진다.
   */
  it('펼치면 8가지 맛 개수를 보여준다', async () => {
    await renderPopcorn();

    fireEvent.click(screen.getByRole('button', { name: /펼쳐서 8가지 맛 개수 보기/ }));

    expect(await screen.findByText('맛1 12알')).toBeInTheDocument();
    expect(screen.getByText('맛8 0알')).toBeInTheDocument();
  });
});

describe('5.8 파티원 팝콘 — 공개 프로필과 차단', () => {
  /*
   * API-062가 2차 MVP라 `getPublicProfile`은 목업이고, 목업에 없는 실제 파티원 번호로
   * 들어가면 7.2-B가 `프로필을 불러오지 못했어요`로 끝났다(2026-09-17 여정 QA).
   * 계약이 열릴 때까지 파티 여정에서 그 화면으로 빠지는 길을 두지 않는다.
   */
  it('공개 프로필로 가는 링크를 두지 않는다', async () => {
    await renderPopcorn();

    fireEvent.click(screen.getByRole('button', { name: new RegExp(BUDDY.nickname) }));
    await screen.findByRole('heading', { name: `${BUDDY.nickname}의 팝콘` });

    expect(screen.queryByRole('link', { name: /취향 보기/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /무비버디/ })).not.toBeInTheDocument();
  });

  it('고른 친구를 차단하면 7.8-D로 간다', async () => {
    await renderPopcorn();

    fireEvent.click(screen.getByRole('button', { name: new RegExp(BUDDY.nickname) }));
    await screen.findByRole('heading', { name: `${BUDDY.nickname}의 팝콘` });
    await openManageMenu();

    fireEvent.click(screen.getByRole('button', { name: /무비버디 님 차단하기/ }));

    // API-020 처리 규칙: 클라이언트는 차단 전에 공동 파티 탈퇴 가능성을 안내한다
    expect(await screen.findByText('무비버디 님을 차단할까요?')).toBeInTheDocument();
    expect(blockUser).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: '차단하기' }));

    await waitFor(() => expect(blockUser).toHaveBeenCalledWith(BUDDY.userId));
    await waitFor(() =>
      expect(screen.getByTestId('location').textContent).toBe('/my/settings/blocks'),
    );
  });

  /*
   * 두 탭에서 같은 파티를 열어 둔 경우다. 이미 차단한 사람(409 `ALREADY_BLOCKED`)은
   * 바라던 상태이므로 실패로 다루지 않는다(7.2-B와 같은 판정).
   */
  it('이미 차단한 사람이어도 7.8-D로 간다', async () => {
    const { ApiError } = await import('../../../api/apiError');
    vi.mocked(blockUser).mockRejectedValue(
      new ApiError(409, 'ALREADY_BLOCKED', '이미 차단한 사용자입니다.'),
    );

    await renderPopcorn();

    fireEvent.click(screen.getByRole('button', { name: new RegExp(BUDDY.nickname) }));
    await screen.findByRole('heading', { name: `${BUDDY.nickname}의 팝콘` });
    await openManageMenu();

    fireEvent.click(screen.getByRole('button', { name: /무비버디 님 차단하기/ }));
    fireEvent.click(await screen.findByRole('button', { name: '차단하기' }));

    await waitFor(() =>
      expect(screen.getByTestId('location').textContent).toBe('/my/settings/blocks'),
    );
  });

  // 계약도 자기 차단을 `400 CANNOT_BLOCK_SELF`로 막는다(API-020)
  it('내 팝콘을 보고 있으면 차단이 없다', async () => {
    await renderPopcorn();
    await openManageMenu();

    expect(screen.queryByRole('button', { name: /차단하기/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '신고하기' })).not.toBeInTheDocument();
  });
});

describe('5.8 파티팝콘 흐름 입구', () => {
  it('함께 볼 영화 추천받기는 5.9 모으기 · 섞기로 간다', async () => {
    await renderPopcorn();

    fireEvent.click(screen.getByRole('button', { name: '함께 볼 영화 추천받기' }));

    expect(screen.getByTestId('location').textContent).toBe(
      `/parties/${TEST_PARTY_ID}/popcorn/mix`,
    );
  });
});

describe('5.8 친구 초대', () => {
  it('찾은 친구에게 곧바로 초대를 보낸다', async () => {
    vi.mocked(searchUsers).mockResolvedValue({
      items: [{ userId: 3072, nickname: '필름러버', visibility: 'PUBLIC' }],
      nextCursor: null,
      hasNext: false,
    });
    vi.mocked(inviteToParty).mockResolvedValue({
      invitationId: 777,
      partyId: TEST_PARTY_ID,
      invitee: { userId: 3072, nickname: '필름러버' },
      status: 'PENDING',
      createdAt: '2026-09-17T00:00:00Z',
    });

    await renderPopcorn();

    fireEvent.click(screen.getByRole('button', { name: '친구 초대하기' }));

    fireEvent.change(await screen.findByLabelText('닉네임으로 친구 찾기'), {
      target: { value: '필름러버' },
    });
    fireEvent.click(screen.getByRole('button', { name: '검색' }));

    fireEvent.click(await screen.findByRole('button', { name: /필름러버/ }));

    await waitFor(() => expect(inviteToParty).toHaveBeenCalledWith(TEST_PARTY_ID, 3072));
    expect(await screen.findByText('필름러버 님에게 초대를 보냈어요')).toBeInTheDocument();
  });
});

describe('5.8 파티 관리', () => {
  it('파티장이면 종료와 내보내기가 보인다', async () => {
    await renderPopcorn();

    // 내보내기는 고른 친구를 대상으로 한다. 내 통을 보고 있으면 나오지 않는다
    fireEvent.click(screen.getByRole('button', { name: new RegExp(BUDDY.nickname) }));
    await screen.findByRole('heading', { name: `${BUDDY.nickname}의 팝콘` });
    await openManageMenu();

    expect(screen.getByRole('button', { name: '파티 종료하기' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /무비버디 님 내보내기/ })).toBeInTheDocument();
  });

  /*
   * 내보내기는 **고른 친구**를 대상으로 한다. 내 통을 보고 있을 때 내보내기가 보이면
   * 자기 자신을 강퇴하게 되고, 계약도 그것을 `400`으로 막는다(API-069).
   */
  it('내 팝콘을 보고 있으면 내보내기가 없다', async () => {
    await renderPopcorn();
    await openManageMenu();

    expect(screen.queryByRole('button', { name: /김난다 님 내보내기/ })).not.toBeInTheDocument();
  });

  it('파티장이 아니면 종료와 내보내기가 없다', async () => {
    vi.mocked(getParty).mockResolvedValue(party({ members: [member(BUDDY, 'OWNER'), member(ME)] }));

    await renderPopcorn();
    await openManageMenu();

    expect(screen.getByRole('button', { name: '파티 나가기' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '파티 종료하기' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /내보내기/ })).not.toBeInTheDocument();
  });

  it('나가기는 확인을 받은 뒤에만 보낸다', async () => {
    await renderPopcorn();
    await openManageMenu();

    fireEvent.click(screen.getByRole('button', { name: '파티 나가기' }));

    expect(await screen.findByText('파티에서 나갈까요?')).toBeInTheDocument();
    expect(leaveParty).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: '나가기' }));

    await waitFor(() => expect(leaveParty).toHaveBeenCalledWith(TEST_PARTY_ID));
    // 나간 파티는 더 볼 수 없다. 5.1로 보낸다
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/party'));
  });

  it('취소하면 아무것도 보내지 않는다', async () => {
    await renderPopcorn();
    await openManageMenu();

    fireEvent.click(screen.getByRole('button', { name: '파티 나가기' }));
    fireEvent.click(await screen.findByRole('button', { name: '취소' }));

    expect(leaveParty).not.toHaveBeenCalled();
    expect(screen.queryByText('파티에서 나갈까요?')).not.toBeInTheDocument();
  });

  it('내보내기는 대상 닉네임을 확인시킨다', async () => {
    await renderPopcorn();

    fireEvent.click(screen.getByRole('button', { name: new RegExp(BUDDY.nickname) }));
    await screen.findByRole('heading', { name: `${BUDDY.nickname}의 팝콘` });
    await openManageMenu();

    fireEvent.click(screen.getByRole('button', { name: /무비버디 님 내보내기/ }));

    // API-069 처리 규칙: 실행 전에 대상 닉네임과 강퇴 사실을 확인시킨다
    expect(await screen.findByText('무비버디 님을 내보낼까요?')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '내보내기' }));

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
    await openManageMenu();

    fireEvent.click(screen.getByRole('button', { name: '파티 종료하기' }));
    fireEvent.click(await screen.findByRole('button', { name: '종료하기' }));

    expect(await screen.findByText('파티장만 종료할 수 있습니다.')).toBeInTheDocument();
    expect(screen.getByTestId('location').textContent).toBe(`/parties/${TEST_PARTY_ID}/popcorn`);
  });
});
