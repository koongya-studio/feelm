/*
 * OTT 기준정보와 구독 설정 접근 함수.
 * 대응 명세는 docs/api/onboarding-ott.md이다.
 */
import { mockOtts } from '../mocks/otts';
import { respond } from './mockTransport';
import type { Ott } from '../types/ott';

/** API-025 OTT 목록 조회 · GET /api/v1/otts */
export async function getOtts(): Promise<Ott[]> {
  return respond(mockOtts);
}

/**
 * API-027 내 구독 OTT 설정 · PUT /api/v1/users/me/otts
 *
 * 목록을 전체 교체한다. 멱등이며 빈 배열이면 전체 해제다.
 * 온보딩(1.2)과 OTT 관리 화면(6.1, 범위 밖)이 같은 API를 쓴다.
 */
export async function setMyOtts(ottIds: number[]): Promise<Ott[]> {
  return respond(mockOtts.filter((ott) => ottIds.includes(ott.ottId)));
}
