/*
 * 취향 분석 데이터 접근 함수.
 * 대응 명세는 docs/api/taste.md이다.
 *
 * 업무 S15P21E106-392에서 프레임 목록(API-042) · 프레임 상세(API-043) · 팝콘 버킷(API-041) ·
 * 팝콘 맛 기준정보(API-044)를 실제 API 호출로 바꿨다.
 *
 * **한 파일에 실제 호출과 목업이 섞여 있다.** 갈리는 기준은 백엔드에 해당 API가 있는지다.
 * - 위 네 개는 실제로 부른다
 * - 취향 분석(API-040)은 백엔드에 있지만 부르는 화면이 홈(2.1) · 7.2로 이번 범위 밖이라
 *   목업으로 남긴다. 연동은 후속 업무다
 * - `getTasteFlow`는 계약에 대응 API가 **없다**(CR-16)
 * - `getMockPopcornFlavors` · `getMyFrameCount` · `getMyPopcornCount`는 **평가 API가 아직
 *   없어 목업으로 남은 흐름**이 쓴다. 아래 목업 구역의 주석을 볼 것
 *
 * 평가(API-035~039)와 감상 기록(API-031~034)이 아직 없으므로 2.5 → 2.6 흐름은 목업이다.
 * 그래서 이 파일이 실제로 돌려주는 프레임과 팝콘은 **평가가 생기기 전까지 항상 비어 있다.**
 */
import { mockPopcornBucket } from '../mocks/popcornBucket';
import { mockPopcornFlavors } from '../mocks/popcornFlavors';
import { mockFrameList } from '../mocks/frames';
import { getRatings } from '../mocks/sessionStore';
import { mockTasteAnalysis, mockTasteFlow } from '../mocks/taste';
import { request } from './client';
import { respond } from './mockTransport';
import type {
  FrameDetail,
  FrameList,
  PopcornBucket,
  PopcornFlavor,
  TasteAnalysis,
} from '../types/taste';
import type { TasteFlowView } from '../types/unspecified';

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
 * 계약의 `flavorCode` · `cursor` · `size` 질의 인자는 쓰는 화면이 생기면 추가한다.
 * 3.1은 첫 페이지만 쓴다(기본 20개).
 */
export async function getMyFrames(): Promise<FrameList> {
  return request<FrameList>('/users/me/frames');
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

/* ------------------------------------------------------------------ *
 * 여기부터 목업. 대응 API가 없거나 이번 범위 밖인 것만 남는다.
 * ------------------------------------------------------------------ */

/**
 * API-040 취향 분석·키워드 조회 · GET /api/v1/users/me/taste
 *
 * 백엔드에 구현돼 있지만 부르는 화면(2.1 홈 · 7.2 프로필 편집)이 이번 범위 밖이라
 * 목업으로 둔다. 홈 추천이 실제 API로 바뀔 때 함께 연동한다.
 */
export async function getMyTaste(): Promise<TasteAnalysis> {
  return respond(mockTasteAnalysis, 'taste');
}

/**
 * 3.1 `요즘 취향 흐름` 카드. **계약에 대응 API가 없다(CR-16).**
 *
 * 흐름 문장과 키워드 근거 문장을 내려주는 필드가 없어 화면이 목업을 직접 import하지
 * 않도록 함수만 따로 둔다. 계약이 정해지면 API-040(`getMyTaste`)으로 합친다.
 */
export async function getTasteFlow(): Promise<TasteFlowView> {
  return respond(mockTasteFlow, 'taste');
}

/**
 * 목업 흐름 전용 팝콘 맛 기준정보.
 *
 * 2.6 평가 결과 · 5.8 · 5.9 파티 팝콘 · 7.2-C 취향 비교는 **집계 자체가 목업**이다.
 * 목업 집계의 맛 코드는 목업 기준정보의 코드라, 실제 API-044의 코드와 맞지 않는다.
 * 그래서 목업으로 남은 화면은 목업 기준정보를 그대로 쓴다(팀 결정 2026-09-16).
 * 각 화면이 실제 API로 바뀔 때 `getPopcornFlavors`로 옮긴다.
 */
export async function getMockPopcornFlavors(): Promise<PopcornFlavor[]> {
  return respond(mockPopcornFlavors);
}

/**
 * 2.6 · 2.7이 쓰는 현재 프레임 수. **목업이다.**
 *
 * 평가 API가 없어 2.5 → 2.6 흐름 전체가 목업이므로 수치도 목업 기준값에서 센다.
 * 실제 API를 부르는 3.1 · 7.1과는 값이 다르다.
 */
export async function getMyFrameCount(): Promise<number> {
  return mockFrameList.totalCount + getRatings().length;
}

/** 2.6 · 2.7이 쓰는 현재 팝콘 알 수. `getMyFrameCount`와 같은 이유로 목업이다 */
export async function getMyPopcornCount(): Promise<number> {
  return mockPopcornBucket.totalCount + getRatings().length;
}
