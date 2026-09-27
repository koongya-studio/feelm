/*
 * 파티 타입. API-049 ~ API-058 · API-069 · API-083
 *
 * 업무 S15P21E106-393에서 파티 화면이 실제 API를 부르게 되면서 **필드 이름은 계약 응답
 * 그대로다.** 이름이 다르면 값이 `undefined`로 들어온다(`api/users.ts` 머리말과 같은
 * 이유).
 */

/**
 * 파티 응답 곳곳의 사용자 요약. `userId` · `nickname` 둘뿐이다.
 *
 * 초대 쪽(API-051 `invitee` · API-052 `party.owner` · API-050 `pendingInvitations[].invitee`)이
 * 이 모양이다. 사진은 오지 않는다.
 */
export type PartyUser = {
  userId: number;
  nickname: string;
};

/**
 * 사진까지 오는 사용자 요약(업무 -811 · -762). 파티 응답의 `owner` · `members[]`와 API-057
 * `members[].user`다. 사진이 없으면 `null`이고, 있으면 API-014와 같은 공개 · 비만료 주소다.
 */
export type PartyAvatarUser = PartyUser & {
  avatarUrl: string | null;
};

export type PartyMember = PartyAvatarUser & {
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
/** 파티 상태. API-050의 `status` 질의값이기도 하다(업무 -826) */
export type PartyStatus = 'ACTIVE' | 'CLOSED';

export type Party = {
  partyId: number;
  name: string;
  status: PartyStatus;
  owner: PartyAvatarUser;
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
  status: PartyStatus;
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
  user: PartyAvatarUser;
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
export type RecommendationMode = 'PERSONALIZED' | 'POPULARITY';
export type PartyPopcornSnapshot = {
  recommendationSetId: number;
  exposedAt: string;
  recommendationMode: RecommendationMode;
  members: PartyMemberPopcorn[];
  totalCount: number;
  flavors: PartyMemberFlavor[];
};
export type PartyPopcorn = {
  latestMix?: PartyPopcornSnapshot | null;
  partyId: number;
  members: PartyMemberPopcorn[];
};

/**
 * API-058 파티 영화 추천 `items[]`.
 *
 * 계약에 추천 이유(`reason`)가 없다. 한때 `reason.summary` · `memberFit[]`을 두고 목업이
 * 채웠는데, 실제 응답(업무 -705)에 없어 지웠다.
 */
export type PartyRecommendationItem = {
  recommendationItemId: number;
  type: 'PARTY';
  movie: {
    movieId: number;
    title: string;
    posterUrl: string | null;
    ottProviders: string[];
  };
};

/**
 * API-058 파티 영화 추천.
 *
 * `hasNext`는 다시 받기(`refresh=true`)로 더 보여줄 후보가 남아 있는지다(업무 -802). `false`면
 * 저장 후보를 다 썼거나 남은 것이 전부 파티원 기준으로 제외된 상태라, 5.5는 `다른 영화
 * 추천받기`를 감추고 더 없다고 알린다.
 */
export type PartyRecommendationSet = {
  popcorn?: PartyPopcornSnapshot | null;
  recommendationMode?: RecommendationMode;
  recommendationSetId: number;
  partyId: number;
  generatedAt: string;
  items: PartyRecommendationItem[];
  hasNext: boolean;
};

/**
 * API-083 파티 추천 이력 `items[].items[].movie`.
 *
 * **API-058과 같은 영화 요약이다**(계약). `PartyRecommendationItem.movie`는 5.5가 쓰는
 * 네 필드만 적어 두었지만, 이력 화면은 개봉 연도 · 장르를 함께 보여주므로 서버가 주는
 * 대로 적는다. 개봉 연도와 평점은 값이 없을 수 있다(백엔드 `RecommendationMovieResponse`의
 * `Integer` · `BigDecimal`).
 *
 * 장르 · 평점 · OTT는 **조회 시점 값이며 이력 스냅샷이 아니다**(API-083 처리 규칙).
 */
export type PartyHistoryMovie = {
  movieId: number;
  title: string;
  posterUrl: string | null;
  releaseYear: number | null;
  genres: string[];
  averageRating: number | null;
  ottProviders: string[];
};

/**
 * API-083 `items[].items[]`.
 *
 * API-058 항목에 `exposedAt`이 붙은 모양이다. **다시 받기로 한 번에 노출된 항목은
 * `exposedAt`이 같다** — 화면은 이 값으로 묶음을 나눈다. 개인 이력(API-048)의
 * `reason` · `dismissed` · `watchStatus`는 오지 않는다(파티 이력은 요청자 개인 기준 값을
 * 싣지 않는다).
 */
export type PartyHistoryItem = {
  recommendationItemId: number;
  type: 'PARTY';
  exposedAt: string;
  movie: PartyHistoryMovie;
};

/**
 * API-083 `items[]` — 추천 세트 하나.
 *
 * 세트 최신순(세트 ID 내림차순)으로 오고, 세트 안 항목은 `exposedAt` 오름차순이다.
 * 같은 영화는 **가장 최신 세트에만** 들어 있어 페이지를 넘겨도 두 번 나오지 않는다.
 */
export type PartyHistorySet = {
  popcornSnapshots?: PartyPopcornSnapshot[];
  recommendationSetId: number;
  generatedAt: string;
  items: PartyHistoryItem[];
};

/** API-083 응답. 페이지는 세트 단위다(API-048과 같다) */
export type PartyHistoryPage = {
  items: PartyHistorySet[];
  nextCursor: string | null;
  hasNext: boolean;
};
