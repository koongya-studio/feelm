/*
 * 취향 분석 목업. API-040 취향 분석·키워드 조회
 *
 * 2.1 `내 취향 요약`은 `액션 · 스릴러`와 `49%`를 표시한다. 와이어프레임이 장르
 * 두 개를 묶어 하나의 비율로 보여주므로, 상위 두 장르의 `share` 합이 0.49가 되도록
 * 두었다. 두 장르를 묶는 산출 규칙은 계약에 없으며 화면에서 계산한다.
 *
 * 키워드 라벨은 와이어프레임의 `계획형 안티히어로를 좋아해요`를 그대로 썼다.
 * API-040의 `keywords[].label`은 문자열이므로 계약 범위 안이다. 다만 계약 예시는
 * `어둠 속 스릴러 애호가`처럼 명사형이라 문장형이 맞는지는 확인이 필요하다.
 */
import type { TasteAnalysis } from '../types/taste';

export const mockTasteAnalysis: TasteAnalysis = {
  ratingCount: 100,
  insufficientData: false,
  analysis: {
    genres: [
      { code: 'ACTION', name: '액션', count: 29, averageScore: 4.3, share: 0.29 },
      { code: 'THRILLER', name: '스릴러', count: 20, averageScore: 4.4, share: 0.2 },
      { code: 'CRIME', name: '범죄', count: 14, averageScore: 4.1, share: 0.14 },
    ],
  },
  keywords: [{ code: 'PLANNED_ANTIHERO', label: '계획형 안티히어로를 좋아해요' }],
  analyzedAt: '2026-09-07T00:00:00Z',
};
