/*
 * OTT 기준정보 접근 함수.
 * 대응 명세는 docs/api/onboarding-ott.md이다.
 */
import { mockOtts } from '../mocks/otts';
import { respond } from './mockTransport';
import type { Ott } from '../types/ott';

/** API-025 OTT 목록 조회 · GET /api/v1/otts */
export async function getOtts(): Promise<Ott[]> {
  return respond(mockOtts);
}
