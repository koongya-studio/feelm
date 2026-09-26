/*
 * 개발 서버 목업 계정의 취향 분석 (업무 S15P21E106-919).
 *
 * `mockAccount`는 백엔드 계정이 없어도 화면을 둘러보라고 만든 장치다. 그런데 업무 -836이 붙인
 * 7.1 `내 취향` 구역은 API-040을 부르므로, 목업 세션에서는 호출이 실패해 **구역째 사라진다.**
 * 그래서 그 화면을 눈으로 확인할 방법이 없었다. 목업 세션이면 이 값을 준다.
 *
 * 키워드는 API-040 사전(S15P21E106-834)의 실제 코드를 쓴다. 넷이라 대표 둘(채운 칩)과
 * 나머지 둘(테두리 칩)이 함께 보여 두 모양을 한 번에 확인할 수 있다.
 *
 * **실제 연동이 끝나면 목업과 함께 지운다.**
 */
import type { TasteAnalysis } from '../types/taste';

export const mockTasteAnalysis: TasteAnalysis = {
  ratingCount: 30,
  insufficientData: false,
  analysis: {
    genres: [
      { code: 'THRILLER', name: '스릴러', count: 14, averageScore: 4.4, share: 0.47 },
      { code: 'DRAMA', name: '드라마', count: 9, averageScore: 4.1, share: 0.3 },
    ],
  },
  keywords: [
    { code: 'DIRECTOR_BINGE', label: '봉준호 몰아보기' },
    { code: 'GENRE_DEVOTEE', label: '스릴러 편식' },
    { code: 'COUNTRY_FOCUS', label: '한국 영화 집중' },
    { code: 'ERA_LOVER', label: '2010년대 애호가' },
  ],
  analyzedAt: '2026-09-26T00:00:00Z',
};
