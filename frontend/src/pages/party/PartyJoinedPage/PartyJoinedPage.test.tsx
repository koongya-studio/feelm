/*
 * 5.7 파티 초대 수락 완료 (업무 S15P21E106-304)
 *
 * 이동표의 `5.7 → 5.4`와 `5.7 ⇢ 5.1`을 고정하고, 수락자 추정이 실제 화면 문구까지
 * 이어지는지 본다. 규칙 자체는 `joinedMember.test.ts`가 따로 본다.
 */
import { describe, expect, it, beforeEach } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import PartyJoinedPage from './index';
import { renderWithRouter } from '../../../test/render';
import { ROUTE_PATTERN } from '../../../routes/paths';
import { resetSessionStore } from '../../../mocks/sessionStore';

/** `mocks/parties.ts`의 기준 파티. 김난다(나) 외 멤버2 · 멤버3 · 멤버4가 있다 */
const PARTY_ID = 9001;

beforeEach(() => {
  resetSessionStore();
});

function render(partyId: number = PARTY_ID) {
  return renderWithRouter(<PartyJoinedPage />, {
    route: `/parties/${partyId}/joined`,
    path: ROUTE_PATTERN.partyJoined,
  });
}

describe('5.7 파티 초대 수락 완료', () => {
  it('마지막에 들어온 사람을 수락자로 알린다', async () => {
    render();

    expect(await screen.findByText('멤버4 님이 초대를 수락했어요!')).toBeInTheDocument();
    expect(screen.getByText('김난다 × 멤버2 × 멤버3 × 멤버4')).toBeInTheDocument();
  });

  it('파티 모드 시작하기는 5.4로 간다', async () => {
    render();
    await screen.findByText('멤버4 님이 초대를 수락했어요!');

    fireEvent.click(screen.getByRole('button', { name: '파티 모드 시작하기' }));

    expect(screen.getByTestId('location').textContent).toBe(`/parties/${PARTY_ID}/analyzing`);
  });

  it('나중에 할게요는 5.1로 간다', async () => {
    render();
    await screen.findByText('멤버4 님이 초대를 수락했어요!');

    fireEvent.click(screen.getByRole('button', { name: '나중에 할게요' }));

    expect(screen.getByTestId('location').textContent).toBe('/party');
  });

  it('없는 파티는 안내로 바꾼다', async () => {
    render(9999);

    expect(await screen.findByText('파티를 찾을 수 없어요')).toBeInTheDocument();
  });
});
