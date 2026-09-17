import type { TasteFlowView } from '../types/unspecified';

/*
 * 3.1 `요즘 취향 흐름` 목업. 계약에 대응 필드가 없다(CR-16).
 *
 * 흐름 문장과 키워드 4개의 라벨 · 근거 문장을 와이어프레임 3.1에서 그대로 옮겼다.
 * API-040의 `keywords`와 형태가 다른 이유는 types/unspecified.ts의
 * `TasteKeywordView` 주석에 적어 두었다.
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
