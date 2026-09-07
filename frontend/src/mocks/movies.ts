/*
 * 영화 기준 목업. 다른 목업이 movieId로 참조하는 원본이다
 * (frontend-structure.md 7.3 — 영화 데이터는 이 파일 한 곳에만 정의한다).
 *
 * 표시값은 와이어프레임에 적힌 문자열을 그대로 옮겼다. 제목이 `영화 제목`인 것도
 * 와이어프레임 그대로다. 실제 영화 제목을 임의로 넣으면 와이어프레임 대조 검증
 * (업무 17)이 어려워지고, 포스터 이미지 권리도 확정되지 않았다(PRODUCT.md).
 *
 * `runtimeMinutes`는 API-045 추천 요약에 없는 필드다. 2.1이 `128분`을 표시하므로
 * types/unspecified.ts의 기준에 따라 목업에만 둔다.
 */
import type { RecommendedMovieView } from '../types/unspecified';

export const mockMovies: Record<number, RecommendedMovieView> = {
  // 2.1 오늘의 추천 대표 · 2.2 취향 맞춤 추천 1번 카드
  101: {
    movieId: 101,
    title: '영화 제목',
    posterUrl: null,
    releaseYear: 2019,
    genres: ['케이퍼', '범죄'],
    averageRating: 4.2,
    // 2.1은 N · T · W 세 개를 표시한다. 2.2 카드는 공간 때문에 N · T만 보인다
    ottProviders: ['NETFLIX', 'TVING', 'WAVVE'],
    runtimeMinutes: 128,
  },
  // 2.2 취향 맞춤 추천 2번 카드
  102: {
    movieId: 102,
    title: '영화 제목',
    posterUrl: null,
    releaseYear: 2021,
    genres: ['스릴러'],
    averageRating: 4.0,
    ottProviders: ['WAVVE'],
    runtimeMinutes: 112,
  },
  // 2.2 취향 발견 추천 카드
  103: {
    movieId: 103,
    title: '영화 제목',
    posterUrl: null,
    releaseYear: 2016,
    genres: ['느와르', '스릴러'],
    averageRating: 4.1,
    ottProviders: ['TVING', 'WAVVE'],
    runtimeMinutes: 121,
  },
  // 2.3-A 관심없음 처리 후 올라오는 교체 후보
  104: {
    movieId: 104,
    title: '영화 제목',
    posterUrl: null,
    releaseYear: 2018,
    genres: ['범죄'],
    averageRating: 3.9,
    ottProviders: ['NETFLIX'],
    runtimeMinutes: 105,
  },

  // 2.1 내 OTT에서 지금 볼 수 있어요 4편
  201: {
    movieId: 201,
    title: '영화 제목 1',
    posterUrl: null,
    releaseYear: 2020,
    genres: ['드라마'],
    averageRating: 4.0,
    ottProviders: ['NETFLIX'],
    runtimeMinutes: 118,
  },
  202: {
    movieId: 202,
    title: '영화 제목 2',
    posterUrl: null,
    releaseYear: 2022,
    genres: ['액션'],
    averageRating: 3.8,
    ottProviders: ['TVING'],
    runtimeMinutes: 124,
  },
  203: {
    movieId: 203,
    title: '영화 제목 3',
    posterUrl: null,
    releaseYear: 2017,
    genres: ['스릴러'],
    averageRating: 4.3,
    ottProviders: ['WAVVE'],
    runtimeMinutes: 109,
  },
  204: {
    movieId: 204,
    title: '영화 제목 4',
    posterUrl: null,
    releaseYear: 2023,
    genres: ['범죄'],
    averageRating: 3.7,
    ottProviders: ['DISNEY_PLUS'],
    runtimeMinutes: 131,
  },
};

export function findMockMovie(movieId: number): RecommendedMovieView {
  const movie = mockMovies[movieId];

  if (!movie) {
    throw new Error(`목업 영화를 찾을 수 없습니다: ${movieId}`);
  }

  return movie;
}
