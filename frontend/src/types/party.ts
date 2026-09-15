/*
 * 파티 타입. API-050 내 파티 목록 · API-057 파티원 팝콘 조회
 *
 * 홈(2.1)의 `참여 중인 파티` 카드는 이름과 멤버 수만 쓴다. 파티 만들기 · 초대 화면은
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

/** API-057 파티원 팝콘 조회 `members[].flavors[]` */
export type PartyMemberFlavor = {
  flavorCode: string;
  popcornCount: number;
  averageScore: number | null;
};

/**
 * API-057 파티원 팝콘 조회 `members[]`
 *
 * 개별 프레임·평가는 내려오지 않는다. 맛별 집계만 있다(API-057 처리 규칙).
 */
export type PartyMemberPopcorn = {
  user: { userId: number; nickname: string };
  /** 팝콘 알 총 수 */
  totalCount: number;
  flavors: PartyMemberFlavor[];
};

/**
 * API-057 파티원 팝콘 조회
 *
 * 응답에 파티 이름이 없다. 5.8의 `금요 심야 상영회 · 멤버 2명` 부제는 API-050에서
 * 가져온다.
 */
export type PartyPopcorn = {
  partyId: number;
  members: PartyMemberPopcorn[];
};

/** API-052 받은 초대 목록 `items[].party` */
export type InvitationParty = {
  partyId: number;
  name: string;
  owner: { userId: number; nickname: string };
  memberCount: number;
};

/** API-052 받은 초대 목록 `items[]` */
export type PartyInvitation = {
  invitationId: number;
  party: InvitationParty;
  status: 'PENDING' | 'ACCEPTED' | 'DECLINED';
  createdAt: string;
};

/** API-058 파티 영화 추천 `items[].reason.memberFit[]` */
export type PartyMemberFit = {
  userId: number;
  /** 0~1 */
  score: number;
};

/** API-058 파티 영화 추천 `items[]` */
export type PartyRecommendationItem = {
  recommendationItemId: number;
  type: 'PARTY';
  movie: {
    movieId: number;
    title: string;
    posterUrl: string | null;
    ottProviders: string[];
  };
  reason: {
    summary: string;
    memberFit: PartyMemberFit[];
  };
};

/** API-058 파티 영화 추천 */
export type PartyRecommendationSet = {
  recommendationSetId: number;
  partyId: number;
  generatedAt: string;
  items: PartyRecommendationItem[];
};
