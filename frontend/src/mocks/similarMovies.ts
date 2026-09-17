/*
 * 유사 영화 목업. API-031 유사 영화 추천
 *
 * 와이어프레임 2.4는 카드 4장에 `같은 케이퍼 장르` · `감독이 같아요` · `빠른 전개 태그` ·
 * `줄거리가 비슷해요`를 붙여 두었다. 계약은 탭 없는 단일 목록에 항목별
 * `reason.summary`를 주므로(API-031, screen-spec.md 8.1 #8) 그 문구를 각 항목의
 * `reason.summary`로 옮겼다.
 */
import { findMockMovie } from './movies';
import type { SimilarMovie } from '../types/movie';

const similarByBase: Record<number, { movieId: number; similarity: number; summary: string }[]> = {
  101: [
    { movieId: 102, similarity: 0.89, summary: '같은 케이퍼 장르' },
    { movieId: 103, similarity: 0.84, summary: '감독이 같아요' },
    { movieId: 104, similarity: 0.8, summary: '빠른 전개 태그' },
    { movieId: 203, similarity: 0.76, summary: '줄거리가 비슷해요' },
  ],
};

export function findMockSimilarMovies(baseMovieId: number): SimilarMovie[] {
  return (similarByBase[baseMovieId] ?? []).map((item) => {
    const movie = findMockMovie(item.movieId);

    return {
      movieId: movie.movieId,
      title: movie.title,
      posterUrl: movie.posterUrl,
      averageRating: movie.averageRating,
      similarity: item.similarity,
    };
  });
}
