/*
 * 취향 분석 데이터 접근 함수.
 * 대응 명세는 docs/api/taste.md이다.
 *
 * 업무 S15P21E106-392에서 프레임 목록(API-042) · 프레임 상세(API-043) · 팝콘 버킷(API-041) ·
 * 팝콘 맛 기준정보(API-044)를 실제 API 호출로 바꿨고, 업무 S15P21E106-479에서 취향
 * 분석(API-040) 클라이언트를 추가했다.
 *
 * **이 파일에 목업은 없다.** 위 다섯 함수는 실제 API를 부른다. 다만 API-040은 현재 호출하는
 * 화면이 없다. 3.1 `요즘 취향 흐름`의 목업 함수(`getTasteFlow`)는 그 카드가 화면에서 빠진 뒤
 * 부르는 곳 없이 남아 있어 업무 -810에서 지웠다(CR-16은 결정 대기).
 *
 * 팝콘 맛 기준정보의 목업 함수(`getMockPopcornFlavors`)는 업무 S15P21E106-707에서 지웠다.
 * 목업 집계를 쓰던 5.8 파티원 팝콘은 실제 `getPopcornFlavors`(API-044)로 옮겨 갔고, 5.9
 * 팝콘 섞기는 맛 코드만 쓴다(그림은 `PopcornBucket/sprites.ts`가 코드로 짝짓는다). 7.2-C
 * 취향 비교는 업무 S15P21E106-506에서 화면 자체가 없어졌다. 목업 데이터
 * (`mocks/popcornFlavors`)는 다른 목업 집계가 맛을 채우는 데 계속 쓰므로 남는다.
 *
 * 평가 생성(API-036)이 실제 호출이 되어(업무 -491) 2.5 → 2.6 흐름이 서버에 프레임과 팝콘을
 * 만든다. 그래서 이 파일이 돌려주는 프레임 목록과 팝콘 버킷도 평가하는 만큼 채워진다.
 * 취향 분석은 다르다 — 계약은 평가 트랜잭션이 `taste_analyses` 캐시를 갱신한다고 정하지만
 * (API-040 처리 규칙) 백엔드에는 아직 조회만 있고 캐시를 채우는 쪽이 없다. 그래서 지금은
 * 평가를 얼마나 해도 모든 사용자가 `insufficientData: true`와 빈 `keywords`를 받는다.
 */
import { request } from './client';
import { isMockSession } from './mockAccount';
import { respond } from './mockTransport';
import { mockTasteAnalysis } from '../mocks/taste';
import type {
  FrameDetail,
  FrameList,
  PopcornBucket,
  PopcornFlavor,
  TasteAnalysis,
} from '../types/taste';

/**
 * API-044 팝콘 맛 기준정보 조회 · GET /api/v1/popcorn-flavors
 *
 * 공개 API다. 화면은 `code`로 매칭하고 표시명 · 색상을 하드코딩하지 않는다.
 *
 * S15P21E106-466 migration이 API-044의 A~H 기준정보를 제공한다. 배포 전이나 migration
 * 실패 시 빈 배열이 올 수 있으므로 호출부는 빈 응답도 안전하게 처리한다.
 */
export async function getPopcornFlavors(): Promise<PopcornFlavor[]> {
  const { items } = await request<{ items: PopcornFlavor[] }>('/popcorn-flavors', {
    auth: 'none',
  });

  return items;
}

/**
 * API-040 취향 분석·키워드 조회 · GET /api/v1/users/me/taste
 *
 * 7.1 MY 대시보드의 `내 취향` 구역과 `api/users.ts`의 내 프로필이 부른다(업무 -836).
 *
 * 개발 서버의 목업 계정으로 로그인했으면 서버로 보내지 않는다. 그러지 않으면 목업 세션에서
 * 호출이 실패해 `내 취향` 구역이 통째로 사라져, 백엔드 없이 그 화면을 볼 수 없다(업무 -919).
 *
 * 캐시가 없는 사용자에게도 `200`이다 — `insufficientData: true` · 빈 배열들 ·
 * `analyzedAt: null`이 온다(API-040 처리 규칙). 후속 화면은 이를 실패가 아닌 빈 상태로
 * 다뤄야 한다. 응답의 `countries` · `eras` · `directors`는 쓰는 화면이 없어 타입에
 * 두지 않았다(`types/taste.ts`).
 */
export async function getMyTaste(): Promise<TasteAnalysis> {
  if (isMockSession()) return respond(mockTasteAnalysis, 'taste');

  return request<TasteAnalysis>('/users/me/taste');
}

/**
 * API-041 팝콘 버킷 조회 · GET /api/v1/users/me/popcorn
 *
 * 맛별 집계는 `ratings JOIN movies`를 영화의 대표 맛으로 묶은 결과다. 대표 맛이 배정되지
 * 않은 영화의 평가는 어느 맛에도 담기지 않는다.
 */
export async function getPopcornBucket(): Promise<PopcornBucket> {
  return request<PopcornBucket>('/users/me/popcorn');
}

/**
 * API-042 내 필름(프레임 목록) 조회 · GET /api/v1/users/me/frames
 *
 * 최신순이고 한 장은 서버 기본값(20개)이다. 첫 장은 커서 없이, 다음 장은 직전 응답의
 * `nextCursor`로 부른다. 3.1이 필름을 끝까지 넘기면 다음 장을 이어 받는다(업무 -656).
 * 계약의 `flavorCode` · `size`는 쓰는 화면이 생기면 추가한다.
 */
export async function getMyFrames(cursor?: string): Promise<FrameList> {
  return request<FrameList>('/users/me/frames', { query: { cursor } });
}

/**
 * API-043 프레임 상세 조회 · GET /api/v1/frames/{frameId}
 *
 * 본인 프레임만 볼 수 있고 타인 프레임은 `404 RESOURCE_NOT_FOUND`다. 화면은 상태 코드를
 * 가리지 않고 실패로 처리한다.
 */
export async function getFrameDetail(frameId: number): Promise<FrameDetail> {
  return request<FrameDetail>(`/frames/${frameId}`);
}
