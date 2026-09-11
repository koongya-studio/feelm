/*
 * 5.8 파티원 팝콘 → 7.2-B 다른 사용자 공개 프로필 (업무 S15P21E106-305)
 *
 * **와이어프레임 이동표에 7.2-B로 들어오는 화살표가 아예 없었다.** 5.8에서 들어가기로
 * 확정하면서(2026-09-11) 이 화면에 진입점을 붙였다. 진입점이 사라지면 7.2-B와 7.2-C가
 * 주소로만 닿는 화면이 되므로 고정한다(screen-spec-my.md 2장 8번).
 *
 * 내 카드에는 붙이지 않는다. 내 공개 프로필은 7.2-A로 보는 것이다.
 *
 * 이 화면은 `getMyProfile`(실제 호출)로 `나` 표시를 정한다. 가로채지 않으면 테스트가
 * 네트워크를 두드리고, 실패하면 `나`가 누구인지 몰라 내 카드에도 링크가 붙는다.
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { screen } from '@testing-library/react';
import PartyPopcornPage from './index';
import { renderWithRouter } from '../../../test/render';
import { getMyProfile } from '../../../api/users';
import { mockMyProfile } from '../../../mocks/user';

/** `mocks/partyPopcorn.ts`가 담고 있는 파티 */
const PARTY_ID = 9001;

vi.mock('../../../api/users', () => ({
  getMyProfile: vi.fn(),
}));

beforeEach(() => {
  vi.mocked(getMyProfile).mockReset().mockResolvedValue(mockMyProfile);
});

async function renderPopcorn() {
  const result = renderWithRouter(<PartyPopcornPage />, {
    route: `/parties/${PARTY_ID}/popcorn`,
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
      '/users/2048',
    );
  });

  it('내 카드에는 진입점을 두지 않는다', async () => {
    await renderPopcorn();

    // 내 닉네임(김난다)으로는 링크가 생기지 않는다
    await screen.findByRole('link', { name: /무비버디 님의 취향 보기/ });
    expect(screen.queryByRole('link', { name: /김난다 님의 취향 보기/ })).not.toBeInTheDocument();
  });
});
