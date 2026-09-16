/*
 * 파티 타입. API-049 ~ API-058 · API-069
 *
 * 업무 S15P21E106-393에서 파티 화면이 실제 API를 부르게 되면서 **필드 이름은 계약 응답
 * 그대로다.** 이름이 다르면 값이 `undefined`로 들어온다(`api/users.ts` 머리말과 같은
 * 이유).
 */

/** 파티 응답 곳곳의 사용자 요약. `userId` · `nickname` 둘뿐이다 */
export type PartyUser = {
  userId: number;
  nickname: string;
};

export type PartyMember = PartyUser & {
  role: 'OWNER' | 'MEMBER';
  joinedAt: string;
};

/** API-050 `items[].pendingInvitations[]`. 내가 현재 멤버인 파티에만 온다 */
export type PartyPendingInvitation = {
  invitationId: number;
  invitee: PartyUser;
  createdAt: string;
};

/**
 * API-049 파티 생성 · API-050 내 파티 목록 · API-053 초대 수락의 파티 응답.
 *
 * `pendingInvitations`는 **API-050에만 있다.** 생성·수락 응답에는 자리가 없어 선택
 * 필드로 둔다.
 */
export type Party = {
  partyId: number;
  name: string;
  status: 'ACTIVE' | 'CLOSED';
  owner: PartyUser;
  members: PartyMember[];
  maxMembers: number;
  pendingInvitations?: PartyPendingInvitation[];
  createdAt: string;
};

/** API-050 응답. 계약은 `items` 하나지만 구현은 커서 페이지로 내려준다 */
export type PartyList = {
  items: Party[];
  nextCursor: string | null;
  hasNext: boolean;
};

/**
 * API-055 파티 나가기 · API-056 파티 종료 · API-069 멤버 강퇴의 공통 응답.
 *
 * 파티 내용이 아니라 **바뀐 뒤의 상태**만 준다. 화면은 이 응답으로 목록을 다시 읽을지
 * 파티를 떠날지 정한다.
 */
export type PartyState = {
  partyId: number;
  status: 'ACTIVE' | 'CLOSED';
  ownerId: number;
  closedReason: 'INSUFFICIENT_MEMBERS' | 'OWNER_CLOSED' | null;
  closedAt: string | null;
};

export type InvitationStatus = 'PENDING' | 'ACCEPTED' | 'DECLINED';

/** API-051 파티 초대 응답. 보낸 쪽이 받는 모양이다 */
export type SentPartyInvitation = {
  invitationId: number;
  partyId: number;
  invitee: PartyUser;
  status: InvitationStatus;
  createdAt: string;
};

/** API-052 받은 초대 목록 `items[].party` */
export type InvitationParty = {
  partyId: number;
  name: string;
  owner: PartyUser;
  memberCount: number;
};

/** API-052 받은 초대 목록 `items[]` */
export type PartyInvitation = {
  invitationId: number;
  party: InvitationParty;
  status: InvitationStatus;
  createdAt: string;
};

/** API-052 응답 */
export type PartyInvitationList = {
  items: PartyInvitation[];
  nextCursor: string | null;
  hasNext: boolean;
};

/** API-054 초대 거절 응답. 초대 전체가 아니라 상태만 준다 */
export type PartyInvitationState = {
  invitationId: number;
  status: InvitationStatus;
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
  user: PartyUser;
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
