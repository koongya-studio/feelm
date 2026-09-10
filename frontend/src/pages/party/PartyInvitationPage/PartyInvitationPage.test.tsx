/*
 * 5.6 파티 초대 수신 — 수락·거절 분기 (업무 S15P21E106-304)
 *
 * 초대는 한 번만 답할 수 있다(API-053 · API-054). 화면이 두 동작을 모두 같은 자리에
 * 두고 있어, 어느 쪽을 눌렀는지가 저장소에 제대로 남는지가 눈으로는 확인되지 않는다.
 * 수락과 거절이 서로 다른 상태로 남는 것과, 답한 초대가 목록에서 빠지는 것을 고정한다.
 */
import { describe, expect, it, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import PartyInvitationPage from './index';
import { renderWithRouter } from '../../../test/render';
import { getMyInvitations, getMyParties } from '../../../api/parties';
import { getInvitationStatus, resetSessionStore } from '../../../mocks/sessionStore';

/** `mocks/partyInvitations.ts`가 담고 있는 초대 */
const INVITATION_ID = 501;

beforeEach(() => {
  resetSessionStore();
});

function render() {
  return renderWithRouter(<PartyInvitationPage />, {
    route: `/parties/invitations/${INVITATION_ID}`,
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

  it('수락하면 ACCEPTED로 남고 내 파티로 간다', async () => {
    render();
    await screen.findByText('무비버디 님의 파티');

    fireEvent.click(screen.getByRole('button', { name: '초대 수락하기' }));

    await waitFor(() => expect(getInvitationStatus(INVITATION_ID)).toBe('ACCEPTED'));
    expect(screen.getByTestId('location').textContent).toBe('/party');
  });

  it('수락하면 그 파티가 내 파티 목록에 들어간다', async () => {
    // 상태만 바꾸고 목록에 넣지 않으면, 수락하고 5.1로 가도 방금 참여한 파티가 없다
    expect(await getMyParties()).toHaveLength(1);

    render();
    await screen.findByText('무비버디 님의 파티');

    fireEvent.click(screen.getByRole('button', { name: '초대 수락하기' }));
    await waitFor(() => expect(getInvitationStatus(INVITATION_ID)).toBe('ACCEPTED'));

    const parties = await getMyParties();
    expect(parties.map((party) => party.name)).toContain('오늘 저녁 같이 볼 영화');

    // 파티장과 내가 함께 들어간다. 5.7의 `김난다 × 무비버디`와 같은 구성이다
    const joined = parties.find((party) => party.name === '오늘 저녁 같이 볼 영화');
    expect(joined?.members.map((member) => member.nickname)).toEqual(['무비버디', '김난다']);
  });

  it('거절하면 파티 목록이 그대로다', async () => {
    render();
    await screen.findByText('무비버디 님의 파티');

    fireEvent.click(screen.getByRole('button', { name: '거절' }));
    await waitFor(() => expect(getInvitationStatus(INVITATION_ID)).toBe('DECLINED'));

    expect(await getMyParties()).toHaveLength(1);
  });

  it('거절하면 DECLINED로 남는다', async () => {
    render();
    await screen.findByText('무비버디 님의 파티');

    fireEvent.click(screen.getByRole('button', { name: '거절' }));

    await waitFor(() => expect(getInvitationStatus(INVITATION_ID)).toBe('DECLINED'));
  });

  it('답한 초대는 받은 초대 목록에서 빠진다', async () => {
    expect(await getMyInvitations()).toHaveLength(1);

    render();
    await screen.findByText('무비버디 님의 파티');

    fireEvent.click(screen.getByRole('button', { name: '거절' }));
    await waitFor(() => expect(getInvitationStatus(INVITATION_ID)).toBe('DECLINED'));

    expect(await getMyInvitations()).toHaveLength(0);
  });

  it('없는 초대는 안내로 바꾼다', async () => {
    renderWithRouter(<PartyInvitationPage />, {
      route: '/parties/invitations/999',
      path: '/parties/invitations/:invitationId',
    });

    expect(await screen.findByText('초대를 확인하지 못했어요')).toBeInTheDocument();
  });
});
