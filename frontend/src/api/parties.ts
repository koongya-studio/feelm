/*
 * 파티 데이터 접근 함수.
 * 대응 명세는 docs/api/party.md · docs/api/users.md(API-019)이다.
 *
 * 업무 S15P21E106-393에서 파티 생성(API-049) · 목록(API-050) · 초대(API-051) · 받은 초대
 * 목록(API-052) · 수락(API-053) · 거절(API-054) · 나가기(API-055) · 종료(API-056) ·
 * 파티원 팝콘(API-057) · 멤버 강퇴(API-069)를 실제 호출로 바꿨다.
 *
 * 파티 영화 추천(API-058)도 업무 -705에서 실제 호출로 바꿨다. **이 파일에 목업은 없다.**
 *
 * 파티 추천 이력(API-083)은 업무 -826에서 더했다. 종료 파티를 읽는 `getMyParties`의
 * `status`도 같은 업무에서 열었다.
 */
import { request } from './client';
import type {
  Party,
  PartyInvitation,
  PartyInvitationList,
  PartyInvitationState,
  PartyList,
  PartyPopcorn,
  PartyHistoryPage,
  PartyRecommendationSet,
  PartyState,
  PartyStatus,
  SentPartyInvitation,
} from '../types/party';

/**
 * 파티 정원. **계약이 정한 값이다** — API-049 처리 규칙 `파티는 파티장 포함 최대 4명`,
 * 새 파티의 정원은 2~4명 중에서 고르고, 기존 파티는 4명을 유지한다.
 *
 * `[!]` 와이어프레임 5.2는 2~6인을 고르게 하고 5.3은 `1/6명 · 최대 6명까지`라고 적었다.
 * 계약을 기준으로 4로 둔다(`screen-spec.md` 2장).
 *
 * 서버도 응답의 `maxMembers`로 정원을 내려주므로 파티가 있는 화면은 그 값을 쓴다. 이
 * 상수는 파티가 아직 없는 5.2 · 5.3이 자리 수를 그릴 때만 쓴다.
 */
export const PARTY_MAX_MEMBERS = 4;

/** 파티를 시작할 수 있는 최소 인원. 와이어프레임 5.2 · 5.3이 함께 적어 둔 값이다 */
export const PARTY_MIN_MEMBERS = 2;

/**
 * 목록 한 번에 받아 올 최대 개수. 계약의 `size` 상한이다(1~50).
 *
 * 활성 파티는 사용자당 최대 10개라(API-049 처리 규칙) 파티 목록은 한 장에 다 들어온다.
 * 받은 초대에는 상한이 없고 8.1 알림 목록이 전체 대기 초대를 보여 주므로 커서를 끝까지
 * 따라간다.
 */
const PAGE_SIZE = 50;

/**
 * API-050 내 파티 목록 · GET /api/v1/parties
 *
 * 기본은 활성 파티만 반환하며 파티가 없으면 빈 배열이다.
 *
 * 끝난 파티는 `getClosedParties`가 따로 준다(업무 -826).
 *
 * `[!]` **계약은 `{ items }` 하나인데 구현은 커서 페이지(`items` · `nextCursor` ·
 * `hasNext`)로 내려준다.** 화면이 쓰는 것은 `items`뿐이라 배열만 돌려준다.
 */
export async function getMyParties(): Promise<Party[]> {
  return listParties();
}

/**
 * API-050 내 파티 목록 · 끝난 파티 (`status=CLOSED`)
 *
 * 5.1의 `지난 파티` 구역과 5.10이 쓴다(업무 -826). 계약이 종료 파티를 내려주는 목적을
 * `추천 이력 확인용`이라고 적어 두었고, 그 목적지가 파티 추천 이력(API-083)이다.
 *
 * **활성 목록과 다른 함수로 둔다.** 계약에 둘을 함께 주는 값이 없어 어차피 두 번 불러야
 * 하고, 부르는 쪽에서 무엇을 받는지가 이름으로 드러나는 편이 낫다.
 *
 * `[!]` 종료 파티에는 상한이 없어(활성은 사용자당 10개, API-049) 한 장(50개)에 다 들어오지
 * 않을 수 있다. **첫 장만 준다** — 지난 파티를 끝까지 따라가는 화면이 아직 없고, 커서를
 * 끝까지 도는 것은 오래 쓴 사용자에게 5.1을 느리게 만든다.
 */
export async function getClosedParties(): Promise<Party[]> {
  return listParties('CLOSED');
}

async function listParties(status?: PartyStatus): Promise<Party[]> {
  const page = await request<PartyList>('/parties', { query: { status, size: PAGE_SIZE } });

  return page.items;
}

/**
 * 끝난 파티 하나를 커서를 따라가며 찾는다(!683 리뷰).
 *
 * 끝난 파티에는 상한이 없어 첫 장(50개)에 없을 수 있다. 첫 장만 보고 없다고 하면 오래된 종료
 * 파티를 `나갔거나 내보내진 파티`로 잘못 안내한다 — 파티 초대 수락 알림은 90일 남는다(8.1).
 * 찾거나 마지막 장까지 본 뒤에 답한다. 같은 커서가 다시 오면 멈춘다(서버가 커서를 못 넘겨도
 * 끝없이 부르지 않는다).
 */
async function findClosedParty(partyId: number): Promise<Party | null> {
  const seen = new Set<string>();
  let cursor: string | undefined;

  for (;;) {
    const page = await request<PartyList>('/parties', {
      query: { status: 'CLOSED', size: PAGE_SIZE, cursor },
    });
    const found = page.items.find((party) => party.partyId === partyId);

    if (found) return found;
    if (!page.hasNext || !page.nextCursor || seen.has(page.nextCursor)) return null;

    seen.add(page.nextCursor);
    cursor = page.nextCursor;
  }
}

/**
 * 파티 하나. 파티 단건 조회 계약이 없어 목록에서 고른다(5.7 · 5.8이 쓴다).
 *
 * `includeClosed`면 활성에서 못 찾았을 때 종료 파티도 본다(업무 -826). 5.10은 종료 파티의
 * 이름을 머리에 적어야 하는데 그 파티는 기본 목록에 없다. 8.1은 파티 초대 수락 알림의 파티가
 * 끝났는지 나갔는지 가른다(업무 -866). 종료 파티는 첫 장만이 아니라 찾을 때까지 넘겨 본다
 * (`findClosedParty`). **기본값은 예전 그대로다** —
 * 5.7 · 5.8은 끝난 파티를 찾아내면 안 된다(5.8은 종료 파티를 다룰 화면이 아니다).
 */
export async function getParty(
  partyId: number,
  options: { includeClosed?: boolean } = {},
): Promise<Party | null> {
  const active = await getMyParties();
  const found = active.find((party) => party.partyId === partyId);

  if (found || !options.includeClosed) {
    return found ?? null;
  }

  return findClosedParty(partyId);
}

/**
 * API-049 파티 생성 · POST /api/v1/parties
 *
 * 5.2에서 고른 인원을 `maxMembers`로 보내고 서버가 파티별 정원으로 저장한다.
 *
 * 이름이 비었거나 20자를 넘으면 `400 VALIDATION_FAILED`, 활성 파티가 10개면
 * `409 ACTIVE_PARTY_LIMIT_REACHED`다.
 */
export async function createParty(name: string, maxMembers: number): Promise<Party> {
  return request<Party>('/parties', { method: 'POST', body: { name: name.trim(), maxMembers } });
}

/**
 * API-051 파티 초대 · POST /api/v1/parties/{partyId}/invitations
 *
 * 닉네임 검색(API-019)으로 찾은 `userId`를 보낸다. 현재 파티원 누구나 부를 수 있다.
 *
 * 차단 관계인 대상은 `404 RESOURCE_NOT_FOUND`로 온다 — 계약이 **없는 사람처럼**
 * 다루기로 했기 때문이다(FR-39). 화면도 차단을 드러내지 않는다.
 */
export async function inviteToParty(partyId: number, userId: number): Promise<SentPartyInvitation> {
  return request<SentPartyInvitation>(`/parties/${partyId}/invitations`, {
    method: 'POST',
    body: { userId },
  });
}

/**
 * API-052 받은 초대 목록 · GET /api/v1/parties/invitations
 *
 * 기본은 대기 중(`PENDING`)인 초대만이다. 답한 초대는 목록에서 빠진다.
 */
export async function getMyInvitations(): Promise<PartyInvitation[]> {
  const invitations: PartyInvitation[] = [];
  let cursor: string | undefined;
  let hasNext: boolean;

  do {
    const page = await request<PartyInvitationList>('/parties/invitations', {
      query: { cursor, size: PAGE_SIZE },
    });
    invitations.push(...page.items);
    cursor = page.nextCursor ?? undefined;
    hasNext = page.hasNext && cursor !== undefined;
  } while (hasNext);

  return invitations;
}

/**
 * 초대 하나. **단건 조회 계약이 없다** — API-052의 목록에서 고른다.
 *
 * 그래서 이미 답한 초대는 찾을 수 없고 `null`이다. 5.6은 답하면 곧바로 다른 화면으로
 * 가므로 답한 초대를 다시 읽을 일이 없다.
 */
export async function getInvitation(invitationId: number): Promise<PartyInvitation | null> {
  const invitations = await getMyInvitations();

  return invitations.find((invitation) => invitation.invitationId === invitationId) ?? null;
}

/**
 * API-053 초대 수락 · POST /api/v1/parties/invitations/{invitationId}/accept
 *
 * 응답은 참여하게 된 파티다(API-049와 같은 본문). 이미 답한 초대는
 * `409 INVITATION_NOT_PENDING`, 정원이 찼으면 `409 PARTY_FULL`이다.
 */
export async function acceptInvitation(invitationId: number): Promise<Party> {
  return request<Party>(`/parties/invitations/${invitationId}/accept`, { method: 'POST' });
}

/**
 * API-054 초대 거절 · POST /api/v1/parties/invitations/{invitationId}/decline
 *
 * 응답은 초대 전체가 아니라 `{ invitationId, status }`뿐이다.
 */
export async function declineInvitation(invitationId: number): Promise<PartyInvitationState> {
  return request<PartyInvitationState>(`/parties/invitations/${invitationId}/decline`, {
    method: 'POST',
  });
}

/**
 * API-055 파티 나가기 · POST /api/v1/parties/{partyId}/leave
 *
 * 파티장이 나가도 남은 사람이 있으면 가장 먼저 들어온 멤버가 파티장을 물려받는다.
 * 마지막 한 명이 나가야 파티가 `CLOSED`가 된다. 어느 쪽인지는 응답의 `status`로 안다.
 */
export async function leaveParty(partyId: number): Promise<PartyState> {
  return request<PartyState>(`/parties/${partyId}/leave`, { method: 'POST' });
}

/**
 * API-056 파티 종료 · DELETE /api/v1/parties/{partyId}
 *
 * 파티장만 부를 수 있다(`403 FORBIDDEN`). 대기 중 초대는 모두 무효가 되고 파티는
 * 삭제되지 않고 `CLOSED`로 남는다.
 */
export async function closeParty(partyId: number): Promise<PartyState> {
  return request<PartyState>(`/parties/${partyId}`, { method: 'DELETE' });
}

/**
 * API-069 파티 멤버 강퇴 · DELETE /api/v1/parties/{partyId}/members/{userId}
 *
 * 파티장만 부를 수 있고 자기 자신은 대상이 될 수 없다(`400 VALIDATION_FAILED`).
 * 강퇴는 차단과 무관하며, 내보낸 사람을 다시 초대할 수 있다.
 */
export async function kickPartyMember(partyId: number, userId: number): Promise<PartyState> {
  return request<PartyState>(`/parties/${partyId}/members/${userId}`, { method: 'DELETE' });
}

/**
 * API-057 파티원 팝콘 조회 · GET /api/v1/parties/{partyId}/popcorn
 *
 * 응답에 파티 이름이 없다. 5.8의 부제는 `getMyParties`에서 이름을 가져온다.
 * 파티가 없거나 내가 멤버가 아니면 `404 RESOURCE_NOT_FOUND`다.
 */
export async function getPartyPopcorn(partyId: number): Promise<PartyPopcorn> {
  return request<PartyPopcorn>(`/parties/${partyId}/popcorn`);
}

/**
 * API-058 파티 영화 추천 · GET /api/v1/parties/{partyId}/recommendations
 *
 * 개수(`size`)는 계약 기본값 3을 쓴다. 5.5 시안이 세 편이다. **호출마다 같은 `size`를 써야
 * 한다** — 묶음의 크기는 그것을 만든 요청의 `size`이고, 더 작게 조회하면 나머지는 노출된 채
 * 다시 볼 수 없다(계약 처리 규칙).
 *
 * 파티원만 부를 수 있다. 오류는 `404 RESOURCE_NOT_FOUND`(없는 파티 · 파티원 아님) ·
 * `409 PARTY_CLOSED` · `503 RECOMMENDATION_UNAVAILABLE`(파티원 취향이 아직 준비 안 됨)이다.
 *
 * **일반 조회는 현재 묶음을 그대로 돌려준다.** 같은 파티원 · 같은 취향이면 서버가 이전 추천
 * 세트를 다시 쓴다 — 5.9가 미리 부르고 5.5가 다시 불러도 같은 세 편이다. **`refresh`가 참이면
 * 다시 받기다**(백엔드 -792 · 업무 -802). 아직 노출하지 않은 후보로 다음 묶음을 만들며 직전
 * 응답과 겹치지 않는다. 5.5의 `다른 영화 추천받기`로 5.9에 들어온 경우에만 붙인다 — 파티
 * 목록에서의 첫 진입 · 5.5의 본요청 · 재시도까지 붙이면 들를 때마다 묶음이 앞으로 가 버린다.
 * 다 소진한 다시 받기는 커서를 움직이지 않고 현재 묶음에 `hasNext: false`를 돌려준다.
 */
export async function getPartyRecommendations(
  partyId: number,
  options: { refresh?: boolean } = {},
): Promise<PartyRecommendationSet> {
  return request<PartyRecommendationSet>(`/parties/${partyId}/recommendations`, {
    query: { refresh: options.refresh ? true : undefined },
  });
}

/**
 * API-083 파티 추천 이력 조회 · GET /api/v1/parties/{partyId}/recommendations/history
 *
 * 그 파티가 **화면에 노출한** 영화를 세트 최신순으로 준다. 저장만 되고 노출되지 않은 후보는
 * 이력이 아니다. 페이지는 **세트 단위**이고(API-048과 같다) `size`도 세트 수다 — 생략하면
 * 서버 기본값(20)이다. 첫 장은 커서 없이, 다음 장은 직전 응답의 `nextCursor`로 부른다.
 *
 * **파티 상태를 보지 않는다.** 그 파티의 멤버십 행이 있으면 활성 · 종료 모두 읽고, 없으면
 * `404 RESOURCE_NOT_FOUND`다(파티가 없는 것과 구분하지 않는다 — 계약이 파티 존재를
 * 노출하지 않기로 했다). 마지막 멤버가 나가서 종료된 파티는 멤버십이 남지 않아 아무도
 * 읽을 수 없다. 깨진 커서와 범위 밖 `size`는 `400 VALIDATION_FAILED`다.
 *
 * 같은 영화는 그 파티에서 **가장 최신 세트 1건**만 온다. 페이지를 넘겨도 같은 영화가 두 번
 * 나오지 않으므로 화면은 받은 세트를 그대로 이어 붙이면 된다.
 */
export async function getPartyRecommendationHistory(
  partyId: number,
  options: { cursor?: string; size?: number } = {},
): Promise<PartyHistoryPage> {
  return request<PartyHistoryPage>(`/parties/${partyId}/recommendations/history`, {
    query: { cursor: options.cursor, size: options.size },
  });
}
