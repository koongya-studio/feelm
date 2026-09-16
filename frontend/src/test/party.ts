/*
 * 파티 화면 테스트가 함께 쓰는 응답 조각 (업무 S15P21E106-393).
 *
 * 파티 화면은 실제 API를 부르므로 화면 테스트마다 API-049 · API-050 · API-052 · API-057
 * 응답을 손으로 만들어야 한다. 여섯 화면이 같은 모양을 각자 적으면 계약이 바뀔 때 고칠
 * 자리가 여섯 곳이 된다. 여기 한 곳에 둔다.
 *
 * **필드는 계약 응답 그대로다.** 빠뜨린 필드가 있으면 화면이 아니라 여기가 틀린 것이다.
 */
import type {
  Party,
  PartyInvitation,
  PartyMember,
  PartyMemberPopcorn,
  PartyPopcorn,
} from '../types/party';
import type { PopcornFlavor } from '../types/taste';

/** 테스트가 쓰는 파티 번호. 목업이 아니라 서버가 준 번호라고 가정한다 */
export const TEST_PARTY_ID = 9001;

/** 나 */
export const ME = { userId: 1024, nickname: '김난다' };

/** 다른 파티원 */
export const BUDDY = { userId: 2048, nickname: '무비버디' };

export function member(
  user: { userId: number; nickname: string },
  role: 'OWNER' | 'MEMBER' = 'MEMBER',
): PartyMember {
  return { ...user, role, joinedAt: '2026-09-01T00:00:00Z' };
}

export function party(overrides: Partial<Party> = {}): Party {
  const members = overrides.members ?? [member(ME, 'OWNER'), member(BUDDY)];

  return {
    partyId: TEST_PARTY_ID,
    name: '금요 심야 상영회',
    status: 'ACTIVE',
    owner: members.find((item) => item.role === 'OWNER') ?? member(ME, 'OWNER'),
    maxMembers: 4,
    createdAt: '2026-09-01T00:00:00Z',
    ...overrides,
    members,
  };
}

export function invitation(overrides: Partial<PartyInvitation> = {}): PartyInvitation {
  return {
    invitationId: 501,
    party: {
      partyId: 9002,
      name: '오늘 저녁 같이 볼 영화',
      owner: BUDDY,
      // API-052는 지금 참여 중인 사람 수만 준다. 나는 아직 들어가지 않았다
      memberCount: 1,
    },
    status: 'PENDING',
    createdAt: '2026-09-10T09:00:00Z',
    ...overrides,
  };
}

/** 8종 맛 자리를 모두 채운 한 사람의 팝콘. 계약이 0알도 내려주기로 정했다(API-057) */
export function memberPopcorn(
  user: { userId: number; nickname: string },
  counts: number[],
): PartyMemberPopcorn {
  return {
    user,
    totalCount: counts.reduce((sum, count) => sum + count, 0),
    flavors: FLAVOR_CODES.map((flavorCode, index) => ({
      flavorCode,
      popcornCount: counts[index] ?? 0,
      averageScore: (counts[index] ?? 0) === 0 ? null : 4,
    })),
  };
}

export const FLAVOR_CODES = [
  'FLAVOR_CAPER',
  'FLAVOR_THRILL',
  'FLAVOR_NOIR',
  'FLAVOR_CRIME',
  'FLAVOR_ACTION',
  'FLAVOR_DRAMA',
  'FLAVOR_COMEDY',
  'FLAVOR_SF',
];

export function partyPopcorn(members?: PartyMemberPopcorn[]): PartyPopcorn {
  return {
    partyId: TEST_PARTY_ID,
    members: members ?? [
      memberPopcorn(ME, [12, 8, 5, 3, 2, 1, 1, 0]),
      memberPopcorn(BUDDY, [9, 11, 4, 2, 2, 1, 0, 0]),
    ],
  };
}

/** API-044 기준정보. 이름이 없으면 화면에 맛 코드가 남는다 */
export function flavors(): PopcornFlavor[] {
  return FLAVOR_CODES.map((code, index) => ({
    code,
    displayName: `맛${index + 1}`,
    color: '#fbf1cf',
    representativeKeywords: [],
    sortOrder: index + 1,
  }));
}
