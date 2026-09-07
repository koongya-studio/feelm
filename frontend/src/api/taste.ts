/*
 * 취향 분석 데이터 접근 함수.
 * 대응 명세는 docs/api/taste.md이다.
 */
import { mockTasteAnalysis } from '../mocks/taste';
import { respond } from './mockTransport';
import type { TasteAnalysis } from '../types/taste';

/** API-040 취향 분석·키워드 조회 · GET /api/v1/users/me/taste */
export async function getMyTaste(): Promise<TasteAnalysis> {
  return respond(mockTasteAnalysis);
}
