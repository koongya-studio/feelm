/*
 * 파티 데이터 접근 함수.
 * 대응 명세는 docs/api/party.md이다.
 */
import { mockParties } from '../mocks/parties';
import { respond } from './mockTransport';
import type { PartyView } from '../types/unspecified';

/**
 * API-050 내 파티 목록 · GET /api/v1/parties
 * 기본은 활성 파티만 반환하며 파티가 없으면 빈 배열이다.
 */
export async function getMyParties(): Promise<PartyView[]> {
  return respond(mockParties);
}
