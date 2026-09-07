/*
 * 파티 타입. API-050 내 파티 목록
 *
 * 홈(2.1)의 `참여 중인 파티` 카드는 이름과 멤버 수만 쓴다. 파티 상세 화면은
 * 이번 범위가 아니므로 필요한 필드만 둔다.
 */

export type PartyMember = {
  userId: number;
  nickname: string;
  role: 'OWNER' | 'MEMBER';
};

export type Party = {
  partyId: number;
  name: string;
  status: 'ACTIVE' | 'CLOSED';
  members: PartyMember[];
  maxMembers: number;
  createdAt: string;
};
