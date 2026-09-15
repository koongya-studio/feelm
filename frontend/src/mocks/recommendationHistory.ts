/*
 * 추천 이력 목업. API-048 추천 이력 조회
 *
 * 최신 세트는 지금 2.2가 보여주는 추천(mocks/recommendations.ts의 7001)이다. 추천 이력은
 * 받은 추천을 모은 것이므로, 방금 본 추천이 이력에 없으면 두 화면이 서로 어긋난다.
 * 그 앞에 지난 세트 셋을 둔다.
 *
 * 와이어프레임 7.5는 두 달(2026년 8월 · 7월)에 걸친 다섯 줄과 출처 세 가지(취향 맞춤 ·
 * 새로운 맛 발견 · 파티 추천)를 그린다. 지난 세트는 그 구성을 따라 8월 둘 · 7월 하나로
 * 두었다. 영화는 상세가 있는 것만 쓴다 — 7.5 ⇢ 2.4가 영화 상세를 읽는다.
 *
 * `dismissed` · `watchStatus`는 기준값이다. 이번 세션에 관심없음 처리하거나 평가한 것은
 * `src/api/recommendations.ts`가 얹는다.
 */
import { findMockMovie } from './movies';
import { mockRecommendationSet } from './recommendations';
import type { RecommendationHistorySet } from '../types/recommendation';

/** API-048 `movie`는 제목 · 포스터만 준다. 영화 표시값은 mocks/movies.ts 한 곳에서 온다 */
function movieOf(movieId: number) {
  const { title, posterUrl } = findMockMovie(movieId);

  return { movieId, title, posterUrl };
}

const latest: RecommendationHistorySet = {
  recommendationSetId: mockRecommendationSet.recommendationSetId,
  generatedAt: mockRecommendationSet.generatedAt,
  items: mockRecommendationSet.items.map((item) => ({
    recommendationItemId: item.recommendationItemId,
    type: item.type,
    movie: movieOf(item.movie.movieId),
    reason: { summary: item.reason.summary },
    dismissed: false,
    watchStatus: null,
  })),
};

/*
 * 지난 세트. 이미 평가한 영화(402 · 401)는 추천받은 뒤 보고 평가한 것이라 `RATED`다.
 * 시각은 정오 기준이다(mocks/frames.ts와 같은 이유).
 */
const past: RecommendationHistorySet[] = [
  {
    recommendationSetId: 6902,
    generatedAt: '2026-08-23T12:00:00Z',
    items: [
      {
        recommendationItemId: 3201,
        type: 'TASTE',
        movie: movieOf(402),
        reason: { summary: '좋아한 케이퍼 무비와 가까워요' },
        dismissed: false,
        watchStatus: 'RATED',
      },
    ],
  },
  {
    // 파티 추천 세트. 계약은 개인 · 파티를 세트의 소유자로 구분한다(API-048 처리 규칙)
    recommendationSetId: 6901,
    generatedAt: '2026-08-09T12:00:00Z',
    items: [
      {
        recommendationItemId: 3101,
        type: 'PARTY',
        movie: movieOf(104),
        reason: { summary: '파티원 모두 범죄 드라마를 좋아해요' },
        dismissed: false,
        watchStatus: null,
      },
    ],
  },
  {
    recommendationSetId: 6801,
    generatedAt: '2026-07-28T12:00:00Z',
    items: [
      {
        recommendationItemId: 3001,
        type: 'TASTE',
        movie: movieOf(401),
        reason: { summary: '범죄 영화를 높게 평가하셨어요' },
        dismissed: false,
        watchStatus: 'RATED',
      },
      {
        recommendationItemId: 3002,
        type: 'DISCOVERY',
        movie: movieOf(403),
        reason: { summary: '범죄 영화와 이어지는 첫 느와르예요' },
        dismissed: false,
        watchStatus: 'RATED',
      },
    ],
  },
];

/** 최신순. 계약도 세트를 최신순으로 내려준다 */
export const mockRecommendationHistory: RecommendationHistorySet[] = [latest, ...past];
