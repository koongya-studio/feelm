/*
 * 급상승 영화 목업. API-064
 *
 * 수치는 와이어프레임 4.1이 적어 둔 값 그대로다. 6편의 제목 · 순위 변동 · 평가 수 ·
 * 별점이 화면에 쓰여 있다.
 *
 * `[!]` 와이어프레임은 목록 라벨을 `· 최근 사용자 평가 기준`이라고 적었지만, API-064는
 * **영화 상세 조회 추이**를 집계한다(ADR-0017). 계약을 기준으로 하되 화면 문구는
 * 와이어프레임을 그대로 두었다. 어느 쪽을 고칠지는 확인이 필요하다.
 *
 * `[?]` API-064 응답에는 OTT 정보가 없는데 와이어프레임은 행마다 OTT 배지를 붙인다.
 * 배지는 검색 목업(`mocks/search.ts`)에서 같은 movieId를 찾아 채운다. 실제 연동에서는
 * 계약에 필드를 추가하거나 화면에서 배지를 빼야 한다.
 */
import type { TrendingList } from '../types/trend';

export const mockTrending: TrendingList = {
  period: { from: '2026-09-03T00:00:00Z', to: '2026-09-10T00:00:00Z' },
  updatedAt: '2026-09-10T00:00:00Z',
  items: [
    {
      rank: 1,
      rankDelta: 2,
      movie: { movieId: 205, title: '웡카와 초콜릿', posterUrl: null },
      ratingCount: 214,
      averageScore: 4.3,
      trendScore: 0.92,
    },
    {
      rank: 2,
      rankDelta: 1,
      movie: { movieId: 206, title: '달의 뒷면', posterUrl: null },
      ratingCount: 180,
      averageScore: 4.1,
      trendScore: 0.88,
    },
    {
      rank: 3,
      rankDelta: 4,
      movie: { movieId: 207, title: '한밤의 추격자', posterUrl: null },
      ratingCount: 166,
      averageScore: 4.4,
      trendScore: 0.85,
    },
    {
      // 와이어프레임의 `—`. 순위가 그대로라는 뜻이다
      rank: 4,
      rankDelta: 0,
      movie: { movieId: 208, title: '여름의 끝', posterUrl: null },
      ratingCount: 150,
      averageScore: 4.0,
      trendScore: 0.81,
    },
    {
      rank: 5,
      rankDelta: 3,
      movie: { movieId: 209, title: '그림자 도시', posterUrl: null },
      ratingCount: 141,
      averageScore: 3.9,
      trendScore: 0.78,
    },
    {
      // 와이어프레임의 `▼1`. 유일하게 내려간 항목이다
      rank: 6,
      rankDelta: -1,
      movie: { movieId: 210, title: '마지막 상영회', posterUrl: null },
      ratingCount: 120,
      averageScore: 4.2,
      trendScore: 0.74,
    },
  ],
};
