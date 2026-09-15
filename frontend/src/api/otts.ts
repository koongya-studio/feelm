/*
 * OTT 기준정보와 구독 설정 접근 함수.
 * 대응 명세는 docs/api/onboarding-ott.md이다.
 */
import { mockOtts } from '../mocks/otts';
import { getSubscribedOttIds, setSubscribedOttIds } from '../mocks/sessionStore';
import { respond } from './mockTransport';
import type { Ott } from '../types/ott';

/**
 * 아직 구독 OTT를 설정하지 않았을 때 쓰는 기준값.
 *
 * 와이어프레임 2.1의 `내 OTT에서 지금 볼 수 있어요`가 넷플릭스 · 티빙 · 웨이브 ·
 * 디즈니+ 배지(N · T · W · D)를 붙인 4편을 보여준다. 온보딩(1.2)을 거치지 않고 홈으로
 * 바로 들어가도 그 4편이 그대로 보이도록 이 넷을 기본으로 둔다.
 */
const DEFAULT_SUBSCRIBED_OTT_IDS = [1, 2, 3, 4];

/** API-025 OTT 목록 조회 · GET /api/v1/otts */
export async function getOtts(): Promise<Ott[]> {
  return respond(mockOtts, 'otts');
}

/**
 * API-026 내 구독 OTT 조회 · GET /api/v1/users/me/otts
 *
 * 이번 세션에 1.2에서 고른 목록이 있으면 그것을, 없으면 기준값을 돌려준다.
 */
export async function getMyOtts(): Promise<Ott[]> {
  const ottIds = getSubscribedOttIds() ?? DEFAULT_SUBSCRIBED_OTT_IDS;

  return respond(mockOtts.filter((ott) => ottIds.includes(ott.ottId)));
}

/**
 * API-027 내 구독 OTT 설정 · PUT /api/v1/users/me/otts
 *
 * 목록을 전체 교체한다. 멱등이며 빈 배열이면 전체 해제다.
 * 온보딩(1.2)과 OTT 관리 화면(6.1, 범위 밖)이 같은 API를 쓴다.
 */
export async function setMyOtts(ottIds: number[]): Promise<Ott[]> {
  setSubscribedOttIds(ottIds);

  return respond(mockOtts.filter((ott) => ottIds.includes(ott.ottId)));
}
