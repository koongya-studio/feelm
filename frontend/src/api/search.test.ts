/*
 * 영화 검색 (업무 S15P21E106-476).
 *
 * 화면에서는 드러나지 않는 것을 고정한다.
 * - **배열 질의를 같은 이름의 반복으로 싣는 것.** 계약이 그렇게 정했고 인덱스 표기는
 *   서버가 400으로 거절한다(API-028). 한 값만 실려도 화면은 "필터가 걸렸다"고 보이므로
 *   눈으로는 틀린 것을 알 수 없다
 * - **`cursor`는 같은 조건으로만 되돌려 준다.** 조건이 섞인 커서는 400이다
 * - **`결과 보기 · N편`은 한 장을 가장 작게 받아 `totalCount`만 본다.** `items`의 길이를
 *   세면 21편부터 20으로 굳는다
 * - **필터 옵션은 공개 API라 토큰을 싣지 않는다.** 실어도 서버는 200을 주므로 드러나지
 *   않는다
 * - **급상승(API-064)도 공개 API라 토큰을 싣지 않고, 실패는 `null`로 삼킨다.** 곁들이는
 *   목록이라 4.1이 실패 상태가 되면 안 된다(업무 -798)
 *
 * 필터 결합(AND · OR)과 정렬, 400 두 가지는 이제 서버가 판정한다. 목업 시절 이 파일이
 * 고정하던 규칙이라 backend 쪽 시험으로 옮겨 갔다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { countSearchResults, getFilterOptions, getTrendingMovies, searchMovies } from './search';
import { ApiError } from './apiError';
import { resetSessionForTest, startSession } from './session';
import type { MovieSearchItem, MovieSearchResult } from '../types/search';

const fetchMock = vi.fn();

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function requestedUrl(index = 0): string {
  return (fetchMock.mock.calls[index] as [string, RequestInit])[0];
}

function requestedHeaders(index = 0): Record<string, string> {
  const init = (fetchMock.mock.calls[index] as [string, RequestInit])[1];

  return (init.headers ?? {}) as Record<string, string>;
}

function item(movieId: number): MovieSearchItem {
  return {
    movieId,
    title: '영화 제목',
    originalTitle: 'Movie Title',
    posterUrl: null,
    releaseYear: 2020,
    genres: ['드라마'],
    averageRating: 4.1,
    ottProviders: ['NETFLIX'],
    watched: false,
  };
}

function page(overrides: Partial<MovieSearchResult> = {}): MovieSearchResult {
  return { totalCount: 1, items: [item(201)], nextCursor: null, hasNext: false, ...overrides };
}

beforeEach(() => {
  resetSessionForTest();
  startSession('token');
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('API-028 영화 검색', () => {
  it('검색어와 기본 페이지 크기를 싣는다', async () => {
    fetchMock.mockResolvedValue(json(200, page()));

    await searchMovies({ q: '나우 유' });

    const url = new URL(requestedUrl(), 'http://test');

    expect(url.pathname).toBe('/api/v1/movies/search');
    expect(url.searchParams.get('q')).toBe('나우 유');
    expect(url.searchParams.get('size')).toBe('20');
  });

  it('같은 필터의 복수 값은 같은 이름을 반복해 싣는다', async () => {
    fetchMock.mockResolvedValue(json(200, page()));

    await searchMovies({ genre: ['DRAMA', 'SF'], country: ['KR'], ott: [1, 2] });

    const params = new URL(requestedUrl(), 'http://test').searchParams;

    expect(params.getAll('genre')).toEqual(['DRAMA', 'SF']);
    expect(params.getAll('country')).toEqual(['KR']);
    expect(params.getAll('ott')).toEqual(['1', '2']);
    // 인덱스 표기는 서버가 400으로 거절한다
    expect(requestedUrl()).not.toContain('%5B');
  });

  it('고르지 않은 조건은 질의에 넣지 않는다', async () => {
    fetchMock.mockResolvedValue(json(200, page()));

    await searchMovies({ q: '나우 유', genre: [], ott: [] });

    const params = new URL(requestedUrl(), 'http://test').searchParams;

    expect(params.has('genre')).toBe(false);
    expect(params.has('ott')).toBe(false);
    expect(params.has('cursor')).toBe(false);
  });

  it('다음 장은 같은 조건에 커서만 더해 부른다', async () => {
    fetchMock.mockResolvedValue(json(200, page()));

    await searchMovies({ q: '나우 유', genre: ['DRAMA'] }, 'cursor-2');

    const params = new URL(requestedUrl(), 'http://test').searchParams;

    expect(params.get('cursor')).toBe('cursor-2');
    expect(params.get('q')).toBe('나우 유');
    expect(params.getAll('genre')).toEqual(['DRAMA']);
  });

  it('전체 건수와 커서를 그대로 돌려준다', async () => {
    fetchMock.mockResolvedValue(
      json(200, page({ totalCount: 24, nextCursor: 'cursor-2', hasNext: true })),
    );

    const result = await searchMovies({ q: '나우 유' });

    expect(result.totalCount).toBe(24);
    expect(result.nextCursor).toBe('cursor-2');
    expect(result.hasNext).toBe(true);
  });

  it('회원 전용이라 토큰 없이 부르면 401이 그대로 올라온다', async () => {
    resetSessionForTest();
    fetchMock.mockResolvedValue(json(401, { code: 'UNAUTHORIZED', message: '인증이 필요합니다.' }));

    const error = await searchMovies({ q: '나우 유' }).catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(401);
  });
});

describe('4.3 결과 보기 개수', () => {
  it('한 장을 가장 작게 받고 totalCount만 본다', async () => {
    fetchMock.mockResolvedValue(json(200, page({ totalCount: 24 })));

    const count = await countSearchResults({ genre: ['DRAMA'] });

    expect(count).toBe(24);
    expect(new URL(requestedUrl(), 'http://test').searchParams.get('size')).toBe('1');
  });
});

describe('API-029 검색 필터 옵션', () => {
  it('공개 API라 토큰을 싣지 않는다', async () => {
    fetchMock.mockResolvedValue(
      json(200, {
        genres: [{ code: 'DRAMA', name: '드라마' }],
        countries: [{ code: 'KR', name: '한국' }],
        yearRange: { min: 1920, max: 2026 },
        otts: [{ ottId: 1, code: 'NETFLIX', name: '넷플릭스' }],
      }),
    );

    const options = await getFilterOptions();

    expect(requestedUrl()).toBe('/api/v1/movies/filter-options');
    expect(requestedHeaders().Authorization).toBeUndefined();
    expect(options.genres).toEqual([{ code: 'DRAMA', name: '드라마' }]);
  });

  it('연도가 있는 영화가 없으면 범위가 비어 온다', async () => {
    fetchMock.mockResolvedValue(
      json(200, {
        genres: [],
        countries: [],
        yearRange: { min: null, max: null },
        otts: [],
      }),
    );

    const options = await getFilterOptions();

    expect(options.yearRange).toEqual({ min: null, max: null });
  });
});

describe('getTrendingMovies (API-064)', () => {
  const body = {
    source: 'VIEW',
    period: { from: '2026-09-22T00:00:00Z', to: '2026-09-22T00:05:00Z' },
    updatedAt: '2026-09-22T00:05:00Z',
    stale: false,
    items: [
      {
        rank: 1,
        rankDelta: 4,
        movie: { movieId: 134626, title: '오디세이', posterUrl: null },
        ratingCount: 120,
        averageScore: 4.3,
        trendScore: 0.9,
      },
    ],
  };

  it('토큰 없이 size=10으로 부르고 응답을 그대로 준다', async () => {
    fetchMock.mockResolvedValueOnce(json(200, body));

    const result = await getTrendingMovies();

    expect(requestedUrl()).toBe('/api/v1/movies/trending?size=10');
    expect(requestedHeaders().Authorization).toBeUndefined();
    expect(result).toEqual(body);
  });

  it('503 TREND_UNAVAILABLE이면 던지지 않고 null이다', async () => {
    fetchMock.mockResolvedValueOnce(
      json(503, { code: 'TREND_UNAVAILABLE', message: '급상승 결과를 조회할 수 없습니다.' }),
    );

    await expect(getTrendingMovies()).resolves.toBeNull();
  });

  it('네트워크 실패도 null이다', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));

    await expect(getTrendingMovies()).resolves.toBeNull();
  });
});
