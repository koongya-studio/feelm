/*
 * 영화 검색과 급상승 접근 함수.
 * 대응 명세는 docs/api/movies-search.md · report-trend.md이다.
 *
 * 검색은 `api/movies.ts`에 두지 않고 나눴다. 그 파일은 영화 한 편(상세 · 유사 · 한줄평)을
 * 다루고, 여기는 목록을 만드는 질의를 다룬다. 목업이 필터를 흉내 내는 코드가 길어서
 * 섞으면 영화 상세 쪽을 읽기 어려워진다.
 */
import { mockFilterOptions, mockSearchMovies } from '../mocks/search';
import { mockTrending } from '../mocks/trending';
import { respond, respondError } from './mockTransport';
import type { FilterOptions, MovieSearchItem, MovieSearchQuery } from '../types/search';
import type { MovieSearchResult } from '../types/search';
import type { TrendingList } from '../types/trend';

/** 계약이 `ott`를 ottId로 받으므로 코드로 바꿔 대조한다 */
function ottCodesOf(ottIds: number[]): string[] {
  return mockFilterOptions.otts.filter((ott) => ottIds.includes(ott.ottId)).map((ott) => ott.code);
}

/** API-028 `q`는 영화명 · 배우 · 감독을 본다 */
function matchesKeyword(movie: (typeof mockSearchMovies)[number], q: string): boolean {
  const keyword = q.trim().toLowerCase();

  if (!keyword) return true;

  return (
    movie.title.toLowerCase().includes(keyword) ||
    movie.originalTitle.toLowerCase().includes(keyword) ||
    movie.people.some((person) => person.toLowerCase().includes(keyword))
  );
}

/**
 * API-028 영화 검색 · GET /api/v1/movies/search
 *
 * 처리 규칙대로 **서로 다른 필터는 AND, 같은 필터의 복수 값은 OR**로 묶는다.
 * 결과가 없으면 빈 `items`와 200이다(4.4가 이 응답을 그린다).
 */
export async function searchMovies(query: MovieSearchQuery): Promise<MovieSearchResult> {
  const { q, genre, country, yearFrom, yearTo, ott, sort = 'RELEVANCE' } = query;
  const hasFilter =
    (genre?.length ?? 0) > 0 ||
    (country?.length ?? 0) > 0 ||
    (ott?.length ?? 0) > 0 ||
    yearFrom !== undefined ||
    yearTo !== undefined;

  // 400 VALIDATION_FAILED — `q`와 필터가 모두 없음
  if (!q?.trim() && !hasFilter) {
    return respondError(400, 'VALIDATION_FAILED', '검색어나 필터가 필요합니다.');
  }

  // 400 VALIDATION_FAILED — 연도 범위 역전
  if (yearFrom !== undefined && yearTo !== undefined && yearFrom > yearTo) {
    return respondError(400, 'VALIDATION_FAILED', '개봉연도 범위가 올바르지 않습니다.');
  }

  const ottCodes = ott?.length ? ottCodesOf(ott) : null;

  const matched = mockSearchMovies.filter((movie) => {
    if (!matchesKeyword(movie, q ?? '')) return false;
    if (genre?.length && !movie.genreCodes.some((code) => genre.includes(code))) return false;
    if (country?.length && !country.includes(movie.countryCode)) return false;
    if (yearFrom !== undefined && movie.releaseYear < yearFrom) return false;
    if (yearTo !== undefined && movie.releaseYear > yearTo) return false;
    if (ottCodes && !movie.ottProviders.some((code) => ottCodes.includes(code))) return false;

    return true;
  });

  const sorted = [...matched].sort((a, b) => {
    if (sort === 'RATING') return b.averageRating - a.averageRating;
    if (sort === 'RELEASE_DATE') return b.releaseYear - a.releaseYear;

    // RELEVANCE. 목업에는 점수가 없으므로 목록에 둔 순서를 그대로 쓴다
    return 0;
  });

  // 응답에 없는 목업 전용 필드(`countryCode` · `genreCodes` · `people`)를 떼고 돌려준다
  const items: MovieSearchItem[] = sorted.map((movie) => ({
    movieId: movie.movieId,
    title: movie.title,
    originalTitle: movie.originalTitle,
    posterUrl: movie.posterUrl,
    releaseYear: movie.releaseYear,
    genres: movie.genres,
    averageRating: movie.averageRating,
    ottProviders: movie.ottProviders,
    watched: movie.watched,
  }));

  return respond({ items, nextCursor: null, hasNext: false }, 'movies');
}

/** API-029 검색 필터 옵션 조회 · GET /api/v1/movies/filter-options */
export async function getFilterOptions(): Promise<FilterOptions> {
  return respond(mockFilterOptions, 'movies');
}

/**
 * API-064 급상승 영화 · GET /api/v1/movies/trending
 *
 * **2차 MVP 계약이다.** 1차에는 데이터가 없을 수 있으므로 화면은 빈 목록을 다룰 수
 * 있어야 한다(`mockError=movies`로 실패도 확인할 수 있다).
 */
export async function getTrendingMovies(size = 10): Promise<TrendingList> {
  return respond({ ...mockTrending, items: mockTrending.items.slice(0, size) }, 'movies');
}
