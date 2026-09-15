/*
 * OTT 구독 추천 목업. API-068 (확장 단계) · EXT-01 ~ EXT-05
 *
 * 순위 · 일치도 · 장르 요약 · 추천 편수는 와이어프레임 EXT-03 그대로다. 넷플릭스(1위)의
 * 이유 · 항목별 일치도는 EXT-04 그대로이고, 나머지 OTT의 상세는 흐름이 이어지게 만든 값이다.
 *
 * - **구독 여부는 여기서 정하지 않는다.** 사용자가 6.1에서 바꾸는 값이라 API-026으로
 *   채운다(`src/api/ottRecommendations.ts`). 와이어프레임은 넷플릭스 · 티빙 구독을
 *   그렸지만, 연동 뒤에는 서버가 아는 구독만 반영된다 — 설정한 적이 없으면 모두
 *   미구독이다(업무 -379)
 * - `matchCount`는 EXT-03의 `추천 68편` 같은 수다. 계약의 순위 기준이 바로 이 값이다
 * - 좋아할 만한 영화는 상세가 있는 영화만 쓴다. EXT-04가 `영화 제목 1 ~ 3`을 그렸다
 *
 * `[?]` EXT-01의 `본 영화 100편 · 평가 96개`는 같은 사용자의 다른 화면(100 · 100)과
 * 어긋난다. 7.1과 같은 이유로 100 기준을 쓴다(screen-spec-my.md 3.1).
 */
import { findMockMovie } from './movies';
import { mockOtts } from './otts';
import type { OttAnalysisBasis, OttRecommendationItemView } from '../types/unspecified';

function ottOf(code: string) {
  const ott = mockOtts.find((item) => item.code === code);

  if (!ott) {
    throw new Error(`목업 OTT를 찾을 수 없습니다: ${code}`);
  }

  return ott;
}

function moviesOf(movieIds: number[]) {
  return movieIds.map((movieId, index) => {
    const { title, posterUrl, releaseYear } = findMockMovie(movieId);

    return { movieId, title, posterUrl, releaseYear, similarity: 0.9 - index * 0.04 };
  });
}

type Seed = Omit<OttRecommendationItemView, 'ott' | 'subscribed' | 'topMovies'> & {
  code: string;
  movieIds: number[];
};

const seeds: Seed[] = [
  {
    code: 'NETFLIX',
    matchCount: 68,
    matchRate: 92,
    genreSummary: '액션 · 스릴러',
    headline: '액션·스릴러와 케이퍼 영화가 가장 많아요',
    reasons: [
      '액션·스릴러 콘텐츠가 풍부해요',
      '높은 별점을 준 감독의 작품이 많아요',
      '좋아하는 계획형 캐릭터 영화가 많아요',
    ],
    itemRates: [
      { label: '장르', rate: 95 },
      { label: '감독', rate: 88 },
      { label: '배우', rate: 82 },
      { label: '캐릭터 취향', rate: 91 },
    ],
    movieIds: [101, 104, 401],
  },
  {
    code: 'TVING',
    matchCount: 52,
    matchRate: 84,
    genreSummary: '스릴러 · 드라마',
    headline: '스릴러와 한국 드라마 영화가 많아요',
    reasons: ['스릴러 콘텐츠가 많아요', '자주 본 한국 영화가 모여 있어요'],
    itemRates: [
      { label: '장르', rate: 86 },
      { label: '감독', rate: 80 },
      { label: '배우', rate: 78 },
      { label: '캐릭터 취향', rate: 84 },
    ],
    movieIds: [103, 412, 403],
  },
  {
    code: 'WAVVE',
    matchCount: 41,
    matchRate: 76,
    genreSummary: '액션 · 범죄',
    headline: '범죄 영화가 꾸준히 들어와요',
    reasons: ['범죄 장르 신작이 많아요'],
    itemRates: [
      { label: '장르', rate: 80 },
      { label: '감독', rate: 70 },
      { label: '배우', rate: 74 },
      { label: '캐릭터 취향', rate: 76 },
    ],
    movieIds: [102, 413],
  },
  {
    code: 'DISNEY_PLUS',
    matchCount: 33,
    matchRate: 69,
    genreSummary: '어드벤처 · SF',
    headline: 'SF와 어드벤처 영화가 많아요',
    reasons: ['SF 장르가 풍부해요'],
    itemRates: [
      { label: '장르', rate: 72 },
      { label: '감독', rate: 64 },
      { label: '배우', rate: 70 },
      { label: '캐릭터 취향', rate: 66 },
    ],
    movieIds: [413],
  },
  {
    // 확정 5종에 맞춰 쿠팡플레이 자리를 왓챠로 바꿨다(업무 -379)
    code: 'WATCHA',
    matchCount: 24,
    matchRate: 58,
    genreSummary: '드라마 · 예술영화',
    headline: '드라마 영화가 조금 있어요',
    reasons: ['드라마 영화가 일부 있어요'],
    itemRates: [
      { label: '장르', rate: 62 },
      { label: '감독', rate: 50 },
      { label: '배우', rate: 58 },
      { label: '캐릭터 취향', rate: 55 },
    ],
    movieIds: [101],
  },
];

/** 구독 여부를 뺀 추천. `src/api/`가 API-026으로 `subscribed`를 채운다 */
export const mockOttRecommendationItems: Omit<OttRecommendationItemView, 'subscribed'>[] =
  seeds.map(({ code, movieIds, ...rest }) => {
    const { ottId, name, logoUrl } = ottOf(code);

    return { ...rest, ott: { ottId, code, name, logoUrl }, topMovies: moviesOf(movieIds) };
  });

export const MOCK_OTT_AGGREGATED_AT = '2026-09-12T00:00:00Z';

/** EXT-01 분석 요약. 수치는 100 기준이다(머리말) */
export const mockOttAnalysisBasis: OttAnalysisBasis = {
  watchedCount: 100,
  ratingCount: 100,
  favoriteGenres: '액션 · 스릴러',
  frequentActor: '마고 로비',
  requiredRatingCount: 10,
};

/** EXT-05를 확인하는 목업 입력(`?mock=ott-insufficient`)일 때의 평가 수. 와이어프레임의 6 */
export const MOCK_INSUFFICIENT_RATING_COUNT = 6;
