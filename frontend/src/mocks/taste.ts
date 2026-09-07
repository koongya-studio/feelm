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
import type { TasteFlowView } from '../types/unspecified';

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

/*
 * 3.1 `요즘 취향 흐름` 목업. 계약에 대응 필드가 없다(CR-16).
 *
 * 흐름 문장과 키워드 4개의 라벨 · 근거 문장을 와이어프레임 3.1에서 그대로 옮겼다.
 * 위 `mockTasteAnalysis.keywords`는 2.1의 문장형 표기를 담고 있어 형태가 다르다.
 * 이유는 types/unspecified.ts의 `TasteKeywordView` 주석에 적어 두었다.
 */
export const mockTasteFlow: TasteFlowView = {
  flowSentence: '스릴러에서 느와르로 넓어지고 있어요',
  keywords: [
    {
      code: 'PLANNED_ANTIHERO',
      label: '계획형 안티히어로',
      rationale: '계획형 캐릭터 영화 12편에 ★4 이상',
    },
    { code: 'FAST_PACED', label: '빠른 전개', rationale: '빠른 전개 태그 영화 평균 ★4.3' },
    { code: 'TWIST_LOVER', label: '반전 선호', rationale: '반전 태그 프레임이 상위 20%' },
    { code: 'NOIR_EXPLORER', label: '느와르 탐험가', rationale: '최근 느와르 프레임 4개 추가' },
  ],
};
