/*
 * 파티 데이터 접근 함수.
 * 대응 명세는 docs/api/party.md이다.
 */
import { findMockPartyMix, findMockPartyPopcorn } from '../mocks/partyPopcorn';
import { mockParties } from '../mocks/parties';
import { respond, respondError } from './mockTransport';
import type { PartyPopcorn } from '../types/party';
import type { PartyMixView, PartyView } from '../types/unspecified';

/**
 * API-050 내 파티 목록 · GET /api/v1/parties
 * 기본은 활성 파티만 반환하며 파티가 없으면 빈 배열이다.
 */
export async function getMyParties(): Promise<PartyView[]> {
  return respond(mockParties, 'parties');
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
