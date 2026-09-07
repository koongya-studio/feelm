/*
 * 취향 분석 데이터 접근 함수.
 * 대응 명세는 docs/api/taste.md이다.
 */
import { mockPopcornFlavors } from '../mocks/popcornFlavors';
import { mockTasteAnalysis } from '../mocks/taste';
import { respond } from './mockTransport';
import type { PopcornFlavor, TasteAnalysis } from '../types/taste';

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
