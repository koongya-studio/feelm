/*
 * 파티 목업. API-050 내 파티 목록
 *
 * 2.1은 `참여 중인 파티 · 금요 심야 상영회`와 `멤버 4명 · 새 팝콘 2개`를 표시한다.
 * `newPopcornCount`는 API-050에 없는 필드이며 types/unspecified.ts에 기록했다.
 */
import type { PartyView } from '../types/unspecified';

export const mockParties: PartyView[] = [
  {
    partyId: 9001,
    name: '금요 심야 상영회',
    status: 'ACTIVE',
    members: [
      { userId: 1024, nickname: '김난다', role: 'OWNER' },
      { userId: 2048, nickname: '멤버2', role: 'MEMBER' },
      { userId: 2049, nickname: '멤버3', role: 'MEMBER' },
      { userId: 2050, nickname: '멤버4', role: 'MEMBER' },
    ],
    maxMembers: 4,
    createdAt: '2026-09-01T00:00:00Z',
    // 계약에 없음 (types/unspecified.ts)
    newPopcornCount: 2,
  },
];
