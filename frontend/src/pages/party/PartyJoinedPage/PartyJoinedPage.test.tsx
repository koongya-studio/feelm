/*
 * 5.7 파티 초대 수락 완료 (업무 S15P21E106-304 · -393)
 *
 * 이동표의 `5.7 → 5.9`와 `5.7 ⇢ 5.1`을 고정하고, 수락자 추정이 실제 화면 문구까지
 * 이어지는지 본다. 규칙 자체는 `joinedMember.test.ts`가 따로 본다.
 *
 * 누가 수락했는지 고르려면 **내가 누구인지** 알아야 한다(API-014). 내 번호를 받지 못하면
 * 나를 수락자로 부를 수 있으므로 함께 가로챈다.
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import PartyJoinedPage from './index';
import { renderWithRouter } from '../../../test/render';
import { ROUTE_PATTERN } from '../../../routes/paths';
import { getParty } from '../../../api/parties';
import { getMyProfile } from '../../../api/users';
import { mockMyProfile } from '../../../mocks/user';
import { BUDDY, ME, member, party, TEST_PARTY_ID } from '../../../test/party';

vi.mock('../../../api/parties', () => ({ getParty: vi.fn() }));
vi.mock('../../../api/users', () => ({ getMyProfile: vi.fn() }));

beforeEach(() => {
  vi.mocked(getMyProfile)
    .mockReset()
    .mockResolvedValue({ ...mockMyProfile, userId: ME.userId, nickname: ME.nickname });
  vi.mocked(getParty)
    .mockReset()
    .mockResolvedValue(party({ members: [member(ME, 'OWNER'), member(BUDDY)] }));
});

function render(partyId: number = TEST_PARTY_ID) {
  return renderWithRouter(<PartyJoinedPage />, {
    route: `/parties/${partyId}/joined`,
    path: ROUTE_PATTERN.partyJoined,
  });
}

describe('5.7 파티 초대 수락 완료', () => {
  it('마지막에 들어온 사람을 수락자로 알린다', async () => {
    render();

    expect(await screen.findByText('무비버디 님이 초대를 수락했어요!')).toBeInTheDocument();
    expect(screen.getByText('김난다 × 무비버디')).toBeInTheDocument();
  });

  it('파티팝콘 시작하기는 5.9로 간다', async () => {
    render();
    await screen.findByText('무비버디 님이 초대를 수락했어요!');

    fireEvent.click(screen.getByRole('button', { name: '파티팝콘 시작하기' }));

    expect(screen.getByTestId('location').textContent).toBe(
      `/parties/${TEST_PARTY_ID}/popcorn/mix`,
    );
  });

  it('나중에 할게요는 5.1로 간다', async () => {
    render();
    await screen.findByText('무비버디 님이 초대를 수락했어요!');

    fireEvent.click(screen.getByRole('button', { name: '나중에 할게요' }));

    expect(screen.getByTestId('location').textContent).toBe('/party');
  });

  it('내 파티가 아니면 안내로 바꾼다', async () => {
    vi.mocked(getParty).mockResolvedValue(null);

    render(9999);

    expect(await screen.findByText('파티를 찾을 수 없어요')).toBeInTheDocument();
  });
});
