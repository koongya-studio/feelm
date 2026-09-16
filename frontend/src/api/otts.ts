/*
 * OTT 기준정보와 구독 설정 접근 함수.
 * 대응 명세는 docs/api/onboarding-ott.md이다.
 *
 * 목업을 걷어내고 실제 API를 부른다(업무 S15P21E106-379). 지원 OTT 5종과 각 `ottId`는
 * 적재 결과로 정해지는 값이라 화면도 이 파일도 하드코딩하지 않는다. **화면은 API-025
 * 응답의 식별자를 그대로 쓴다.**
 *
 * API-026 · API-027의 응답은 `Ott[]`가 아니라 **식별자 배열**(`{ "ottIds": [1, 3] }`)이다.
 * 목업 시절에는 두 API가 `Ott[]`를 돌려주는 것처럼 꾸며 두었는데, 계약은 기준정보를
 * 다시 실어 보내지 않는다. 이름과 로고가 필요한 화면은 API-025 목록과 이 식별자를
 * 맞춰 쓴다.
 */
import { request } from './client';
import type { Ott } from '../types/ott';

/** API-025 응답 */
type OttListResponse = { items: Ott[] };

/** API-026 · API-027 응답. 둘의 본문이 같다 */
type UserOttResponse = { ottIds: number[] };

/**
 * 진행 중이거나 성공한 API-025 조회.
 *
 * 기준정보를 여러 화면이 함께 읽는다 — 홈 · 추천 · 검색 · OTT 관리에 더해 배지가 로고를
 * 찾는 기준표(`contexts/OttCatalogProvider`)까지다. 서버가 하루짜리 공유 캐시를 허용하므로
 * 브라우저가 두 번째 요청부터 막아 주지만, 앱이 뜰 때 동시에 나가는 요청은 캐시가 채워지기
 * 전이라 그대로 나간다. 성공한 응답만 기억하고 실패하면 지워서 다시 부를 수 있게 둔다
 * (업무 S15P21E106-441).
 */
let ottsRequest: Promise<Ott[]> | null = null;

/**
 * API-025 OTT 목록 조회 · GET /api/v1/otts
 *
 * 공개 API다. 로그인 전 화면도 부를 수 있고, 기준정보라 서버가 하루짜리 공유 캐시를
 * 허용한다(응답의 `Cache-Control`).
 *
 * 같은 앱 실행 안에서는 한 번만 실제로 나간다. 지원 목록은 적재가 하루 단위로 도는
 * 기준정보라 화면을 보는 동안 바뀌지 않는다.
 */
export async function getOtts(): Promise<Ott[]> {
  ottsRequest ??= request<OttListResponse>('/otts', { auth: 'none' })
    .then((response) => response.items)
    .catch((error: unknown) => {
      ottsRequest = null;

      throw error;
    });

  return ottsRequest;
}

/** 검증과 개발 확인용. 화면에서는 쓰지 않는다 */
export function resetOttCacheForTest(): void {
  ottsRequest = null;
}

/**
 * API-026 내 구독 OTT 조회 · GET /api/v1/users/me/otts
 *
 * **설정한 적이 없으면 빈 배열이다.** 목업은 와이어프레임 2.1의 배지 네 개를 보이려고
 * 기준값 `[1, 2, 3, 4]`를 돌려줬지만 계약에는 그런 기본 구독이 없다. 구독을 고르지
 * 않은 사용자는 구독이 없는 것이 맞고, 화면도 그 상태를 그대로 보여준다.
 */
export async function getMyOttIds(): Promise<number[]> {
  const response = await request<UserOttResponse>('/users/me/otts');

  return response.ottIds;
}

/**
 * API-027 내 구독 OTT 설정 · PUT /api/v1/users/me/otts
 *
 * 목록을 전체 교체한다. 멱등이며 빈 배열이면 전체 해제다. 온보딩(1.2)과 OTT 관리
 * 화면(6.1)이 같은 API를 쓰므로 두 화면의 구독 상태는 저절로 일치한다.
 *
 * 응답은 서버가 저장한 결과다. 중복을 보내면 제거된 채로 돌아오므로 화면은 보낸 값이
 * 아니라 **돌려받은 값**을 반영한다.
 */
export async function setMyOttIds(ottIds: number[]): Promise<number[]> {
  const response = await request<UserOttResponse>('/users/me/otts', {
    method: 'PUT',
    body: { ottIds },
  });

  return response.ottIds;
}
