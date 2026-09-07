/*
 * 취향 분석 데이터 접근 함수.
 * 대응 명세는 docs/api/taste.md이다.
 */
import { findMockFrameDetail, mockFrameList } from '../mocks/frames';
import { mockPopcornBucket } from '../mocks/popcornBucket';
import { mockPopcornFlavors } from '../mocks/popcornFlavors';
import { mockTasteAnalysis, mockTasteFlow } from '../mocks/taste';
import { respond, respondError } from './mockTransport';
import type { FrameList, PopcornBucket, PopcornFlavor, TasteAnalysis } from '../types/taste';
import type { FrameDetailView, TasteFlowView } from '../types/unspecified';

/** API-040 취향 분석·키워드 조회 · GET /api/v1/users/me/taste */
export async function getMyTaste(): Promise<TasteAnalysis> {
  return respond(mockTasteAnalysis);
}

/**
 * API-044 팝콘 맛 기준정보 조회 · GET /api/v1/popcorn-flavors
 *
 * 화면은 `code`로 매칭하고 표시명 · 색상을 하드코딩하지 않는다. 8종의 표시명과 색상은
 * API-044 미정 사항이므로 목업 값은 잠정값이다.
 */
export async function getPopcornFlavors(): Promise<PopcornFlavor[]> {
  return respond(mockPopcornFlavors);
}

/**
 * API-041 팝콘 버킷 조회 · GET /api/v1/users/me/popcorn
 *
 * 3.1은 `totalCount`(팝콘 알 수)만 쓴다. 맛별 분포는 3.2에서 쓴다.
 */
export async function getPopcornBucket(): Promise<PopcornBucket> {
  return respond(mockPopcornBucket);
}

/**
 * API-042 내 필름(프레임 목록) 조회 · GET /api/v1/users/me/frames
 *
 * 계약의 `flavorCode` · `cursor` · `size` 질의 인자는 쓰는 화면이 생기면 추가한다.
 * 3.1은 첫 페이지만 쓴다.
 */
export async function getMyFrames(): Promise<FrameList> {
  return respond(mockFrameList);
}

/** API-043 프레임 상세 조회 · GET /api/v1/frames/{frameId} */
export async function getFrameDetail(frameId: number): Promise<FrameDetailView> {
  const frame = findMockFrameDetail(frameId);

  if (!frame) {
    return respondError(404, 'RESOURCE_NOT_FOUND', '프레임을 찾을 수 없습니다.');
  }

  return respond(frame);
}

/**
 * 3.1 `요즘 취향 흐름` 카드. **계약에 대응 API가 없다(CR-16).**
 *
 * 흐름 문장과 키워드 근거 문장을 내려주는 필드가 없어 화면이 목업을 직접 import하지
 * 않도록 함수만 따로 둔다. 계약이 정해지면 API-040(`getMyTaste`)으로 합친다.
 */
export async function getTasteFlow(): Promise<TasteFlowView> {
  return respond(mockTasteFlow);
}
