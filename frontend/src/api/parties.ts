/*
 * 파티 데이터 접근 함수.
 * 대응 명세는 docs/api/party.md이다.
 */
import { findMockPartyMix, findMockPartyPopcorn } from '../mocks/partyPopcorn';
import { mockParties } from '../mocks/parties';
import { mockPartyInvitations } from '../mocks/partyInvitations';
import {
  mockPartyRecommendationReasons,
  mockPartyRecommendations,
} from '../mocks/partyRecommendations';
import { mockMyProfile } from '../mocks/user';
import {
  addCreatedParty,
  getCreatedParties,
  getInvitationStatus,
  nextPartyId,
  setInvitationStatus,
} from '../mocks/sessionStore';
import { respond, respondError } from './mockTransport';
import type { PartyInvitation, PartyPopcorn, PartyRecommendationSet } from '../types/party';
import type { PartyMixView, PartyView } from '../types/unspecified';

/**
 * 파티 정원. **계약이 정한 값이다** — API-049 처리 규칙 `파티는 파티장 포함 최대 4명`,
 * 응답의 `maxMembers: 4`, API-051이 4명에서 `PARTY_FULL`.
 *
 * `[!]` 와이어프레임 5.2는 2~6인을 고르게 하고 5.3은 `1/6명 · 최대 6명까지`라고 적었다.
 * 계약을 기준으로 4로 둔다(`screen-spec.md` 2장).
 */
export const PARTY_MAX_MEMBERS = 4;

/** 파티를 시작할 수 있는 최소 인원. 와이어프레임 5.2 · 5.3이 함께 적어 둔 값이다 */
export const PARTY_MIN_MEMBERS = 2;

/**
 * API-050 내 파티 목록 · GET /api/v1/parties
 * 기본은 활성 파티만 반환하며 파티가 없으면 빈 배열이다.
 */
export async function getMyParties(): Promise<PartyView[]> {
  return respond([...getCreatedParties(), ...mockParties], 'parties');
}

/**
 * API-049 파티 생성 · POST /api/v1/parties
 *
 * 요청 본문은 `{ name }`뿐이다. 와이어프레임 5.2가 고르게 하는 **인원 수를 받는 필드가
 * 계약에 없다** — 정원은 응답의 `maxMembers`로 서버가 정한다. 화면이 고른 값은 초대할
 * 친구 수를 정하는 데만 쓰고 요청에 넣지 않는다.
 */
export async function createParty(name: string): Promise<PartyView> {
  const trimmed = name.trim();

  if (!trimmed || trimmed.length > 20) {
    return respondError(400, 'VALIDATION_FAILED', '파티 이름은 20자 이하로 입력해 주세요.');
  }

  const party: PartyView = {
    partyId: nextPartyId(),
    name: trimmed,
    status: 'ACTIVE',
    members: [{ userId: mockMyProfile.userId, nickname: mockMyProfile.nickname, role: 'OWNER' }],
    maxMembers: PARTY_MAX_MEMBERS,
    createdAt: new Date().toISOString(),
    // 계약에 없음 (types/unspecified.ts). 방금 만든 파티에는 새 팝콘이 없다
    newPopcornCount: 0,
  };

  addCreatedParty(party);

  return respond(party, 'parties');
}

/**
 * API-052 받은 초대 목록 · GET /api/v1/parties/invitations
 *
 * 이번 세션에 답한 초대는 `PENDING`이 아니므로 목록에서 빠진다.
 */
export async function getMyInvitations(): Promise<PartyInvitation[]> {
  const pending = mockPartyInvitations.filter(
    (invitation) => getInvitationStatus(invitation.invitationId) === null,
  );

  return respond(pending, 'parties');
}

/** 초대 하나. 답한 뒤에도 5.6 · 5.7이 그 초대를 계속 보여줘야 해서 목록과 따로 둔다 */
export async function getInvitation(invitationId: number): Promise<PartyInvitation> {
  const invitation = mockPartyInvitations.find((item) => item.invitationId === invitationId);

  if (!invitation) {
    return respondError(404, 'RESOURCE_NOT_FOUND', '초대를 찾을 수 없습니다.');
  }

  const answered = getInvitationStatus(invitationId);

  return respond(answered ? { ...invitation, status: answered } : invitation, 'parties');
}

/**
 * API-053 초대 수락 · POST /api/v1/parties/invitations/{invitationId}/accept
 *
 * **수락하면 그 파티의 멤버가 되므로 내 파티 목록(API-050)에도 들어간다.** 계약에서는
 * 서버가 두 곳을 함께 바꾸지만 목업은 저장소가 나뉘어 있어 여기서 같이 넣어 준다.
 * 넣지 않으면 5.6에서 수락하고 5.1로 갔을 때 방금 참여한 파티가 없다.
 *
 * 멤버는 파티장과 나로 만든다. API-052가 주는 것은 `memberCount`(수 하나)뿐이라 다른
 * 참여자의 이름을 알 수 없다. 수가 더 크면 이름 없는 자리로 채운다.
 */
export async function acceptInvitation(invitationId: number): Promise<PartyInvitation> {
  const answered = getInvitationStatus(invitationId);

  if (answered) {
    return respondError(409, 'INVITATION_ALREADY_ANSWERED', '이미 응답한 초대입니다.');
  }

  const invitation = mockPartyInvitations.find((item) => item.invitationId === invitationId);

  if (!invitation) {
    return respondError(404, 'RESOURCE_NOT_FOUND', '초대를 찾을 수 없습니다.');
  }

  setInvitationStatus(invitationId, 'ACCEPTED');

  const { party } = invitation;
  const others = Array.from({ length: Math.max(0, party.memberCount - 1) }, (_, index) => ({
    userId: -(index + 1),
    nickname: `멤버${index + 2}`,
    role: 'MEMBER' as const,
  }));

  addCreatedParty({
    partyId: party.partyId,
    name: party.name,
    status: 'ACTIVE',
    members: [
      { userId: party.owner.userId, nickname: party.owner.nickname, role: 'OWNER' },
      ...others,
      { userId: mockMyProfile.userId, nickname: mockMyProfile.nickname, role: 'MEMBER' },
    ],
    maxMembers: PARTY_MAX_MEMBERS,
    createdAt: invitation.createdAt,
    newPopcornCount: 0,
  });

  return getInvitation(invitationId);
}

/** API-054 초대 거절 · POST /api/v1/parties/invitations/{invitationId}/decline */
export async function declineInvitation(invitationId: number): Promise<PartyInvitation> {
  const answered = getInvitationStatus(invitationId);

  if (answered) {
    return respondError(409, 'INVITATION_ALREADY_ANSWERED', '이미 응답한 초대입니다.');
  }

  setInvitationStatus(invitationId, 'DECLINED');

  return getInvitation(invitationId);
}

/**
 * API-058 파티 영화 추천 · GET /api/v1/parties/{partyId}/recommendations
 *
 * 계약은 동기 응답이다. 5.4 `공통 필름 현상 중`은 이 응답을 기다리는 동안 보여 주는
 * 화면이지 별도 계약이 아니다.
 */
export async function getPartyRecommendations(partyId: number): Promise<PartyRecommendationSet> {
  const parties = [...getCreatedParties(), ...mockParties];

  if (!parties.some((party) => party.partyId === partyId)) {
    return respondError(404, 'RESOURCE_NOT_FOUND', '파티를 찾을 수 없습니다.');
  }

  /*
   * 기준 목업은 9001 하나뿐이다. 이 화면에서 만든 파티는 9100부터 번호를 받으므로
   * 그대로 두면 **방금 만든 파티는 언제나 404**가 되어 5.3 → 5.4 → 5.5가 끊긴다.
   * 실제 서버는 어떤 파티에도 추천을 준다. 없는 번호는 기준 목업의 추천을 파티 번호만
   * 갈아 끼워 내려준다.
   */
  const base = mockPartyRecommendations[partyId] ?? mockPartyRecommendations[9001];

  return respond({ ...base, partyId }, 'parties');
}

/**
 * 5.5의 `왜 같이 추천됐나요?` 문장.
 *
 * **계약에 대응 필드가 없다.** API-058 `reason`은 카드별 `summary` 하나뿐이고, 추천 묶음
 * 전체를 설명하는 문장이 없다. 화면이 목업을 직접 import하지 않도록 함수로 감싼다
 * (`getPartyMix`와 같은 이유).
 */
export async function getPartyRecommendationReasons(partyId: number): Promise<string[]> {
  const reasons = mockPartyRecommendationReasons[partyId] ?? mockPartyRecommendationReasons[9001];

  return respond(reasons ?? [], 'parties');
}

/**
 * API-057 파티원 팝콘 조회 · GET /api/v1/parties/{partyId}/popcorn
 *
 * 응답에 파티 이름이 없다. 5.8의 부제는 `getMyParties`에서 이름을 가져온다.
 */
export async function getPartyPopcorn(partyId: number): Promise<PartyPopcorn> {
  const popcorn = findMockPartyPopcorn(partyId);

  if (!popcorn) {
    return respondError(404, 'RESOURCE_NOT_FOUND', '파티를 찾을 수 없습니다.');
  }

  return respond(popcorn, 'parties');
}

/**
 * 5.9 파티 팝콘 섞기 결과. **계약에 대응 API가 없다(CR-17).**
 *
 * 참여자별 취향 비율과 공통 취향 일치도를 내려주는 필드가 없어 화면이 목업을 직접
 * import하지 않도록 함수만 따로 둔다. 계약이 정해지면 API-057로 합친다.
 */
export async function getPartyMix(partyId: number): Promise<PartyMixView> {
  const mix = findMockPartyMix(partyId);

  if (!mix) {
    return respondError(404, 'RESOURCE_NOT_FOUND', '파티를 찾을 수 없습니다.');
  }

  return respond(mix, 'parties');
}
