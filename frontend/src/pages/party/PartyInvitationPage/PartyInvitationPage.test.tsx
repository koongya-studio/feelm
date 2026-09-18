/*
 * 5.6 파티 초대 수신 — 수락·거절 분기 (업무 S15P21E106-304 · -393)
 *
 * 초대는 한 번만 답할 수 있다(API-053 · API-054). 화면이 두 동작을 같은 자리에 두고 있어
 * 어느 쪽을 눌렀는지가 눈으로는 확인되지 않는다. 수락과 거절이 서로 다른 API를 부르고
 * 서로 다른 곳으로 가는 것을 고정한다.
 *
 * **초대 단건 조회 계약이 없다.** 목록(API-052)에서 번호로 고르므로 이미 답한 초대는
 * 목록에 없어 찾지 못한 상태가 된다.
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import PartyInvitationPage from './index';
import { renderWithRouter } from '../../../test/render';
import { acceptInvitation, declineInvitation, getInvitation } from '../../../api/parties';
import { invitation, party } from '../../../test/party';

const INVITATION_ID = 501;

vi.mock('../../../api/parties', () => ({
  getInvitation: vi.fn(),
  acceptInvitation: vi.fn(),
  declineInvitation: vi.fn(),
}));

beforeEach(() => {
  vi.mocked(getInvitation).mockReset().mockResolvedValue(invitation());
  vi.mocked(acceptInvitation).mockReset().mockResolvedValue(party());
  vi.mocked(declineInvitation)
    .mockReset()
    .mockResolvedValue({ invitationId: INVITATION_ID, status: 'DECLINED' });
});

function render(invitationId: number = INVITATION_ID) {
  return renderWithRouter(<PartyInvitationPage />, {
    route: `/parties/invitations/${invitationId}`,
    path: '/parties/invitations/:invitationId',
  });
}

describe('5.6 파티 초대 수신', () => {
  it('초대한 사람과 파티를 보여준다', async () => {
    render();

    expect(await screen.findByText('무비버디 님의 파티')).toBeInTheDocument();
    // `2인`은 나까지 센 값이다. API-052 `memberCount`는 1(파티장뿐)이다
    expect(screen.getByText('2인 · 오늘 저녁 같이 볼 영화')).toBeInTheDocument();
  });

  it('수락하면 API-053을 부르고 내 파티로 간다', async () => {
    render();
    await screen.findByText('무비버디 님의 파티');

    fireEvent.click(screen.getByRole('button', { name: '초대 수락하기' }));

    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/party'));
    expect(acceptInvitation).toHaveBeenCalledWith(INVITATION_ID);
    expect(declineInvitation).not.toHaveBeenCalled();
  });

  /*
   * 거절도 5.1로 간다(업무 -595). API-070 알림도 서버에서 닫히고, 5.1은 API-052를 다시
   * 읽어 답한 초대를 제외한다.
   */
  it('거절하면 API-054를 부르고 파티 시작으로 간다', async () => {
    render();
    await screen.findByText('무비버디 님의 파티');

    fireEvent.click(screen.getByRole('button', { name: '거절' }));

    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/party'));
    expect(declineInvitation).toHaveBeenCalledWith(INVITATION_ID);
    expect(acceptInvitation).not.toHaveBeenCalled();
  });

  it('이미 답한 초대는 목록에 없어 안내로 바꾼다', async () => {
    vi.mocked(getInvitation).mockResolvedValue(null);

    render(999);

    expect(await screen.findByText('초대를 확인하지 못했어요')).toBeInTheDocument();
  });

  it('수락이 거절당하면 안내로 바꾼다', async () => {
    vi.mocked(acceptInvitation).mockRejectedValue(new Error('PARTY_FULL'));

    render();
    await screen.findByText('무비버디 님의 파티');

    fireEvent.click(screen.getByRole('button', { name: '초대 수락하기' }));

    expect(await screen.findByText('초대를 확인하지 못했어요')).toBeInTheDocument();
  });
});
