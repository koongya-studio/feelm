/*
 * API-030 영화 상세 조회 (업무 S15P21E106-475)
 *
 * 화면에서는 눈에 띄지 않는 계약의 두 가지를 고정한다.
 *
 * - **선택 인증이다.** 토큰이 있으면 싣고 없으면 비회원으로 부른다. `required`로 두면
 *   로그인 전 화면이 재발급을 시도하다 실패하고, `none`으로 두면 로그인해도 내 평가와
 *   구독 여부가 사라진다
 * - **`recommendationItemId`는 있을 때만 붙는다.** 추천 경로가 아닌 진입에 빈 값을
 *   보내면 계약이 `400 VALIDATION_FAILED`로 막는다
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getMovieDetail, getMovieReviews, getSimilarMovies } from './movies';
import { ApiError } from './apiError';
import { endSession, resetSessionForTest, startSession } from './session';

const fetchMock = vi.fn();

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function call(index = 0) {
  return fetchMock.mock.calls[index] as [string, RequestInit];
}

/** 선택 필드가 모두 비어 있는 최소 응답. 수집 데이터에는 이런 영화가 있다 */
const sparseDetail = {
  movieId: 550,
  title: '파이트 클럽',
  originalTitle: null,
  synopsis: null,
  releaseDate: null,
  runtimeMinutes: null,
  genres: [],
  countries: [],
  directors: [],
  cast: [],
  posterUrl: null,
  backdropUrl: null,
  flavorCode: null,
  averageRating: 0,
  ratingCount: 0,
  ottProviders: [],
  myRating: null,
  myWatchStatus: null,
  recommendation: null,
};

beforeEach(() => {
  resetSessionForTest();
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('API-030 영화 상세 조회', () => {
  it('영화 ID로 부르고 응답을 그대로 돌려준다', async () => {
    fetchMock.mockResolvedValue(json(200, sparseDetail));

    expect(await getMovieDetail(550)).toEqual(sparseDetail);
    expect(call()[0]).toBe('/api/v1/movies/550');
    expect(call()[1].method).toBe('GET');
  });

  it('토큰이 있으면 싣는다', async () => {
    startSession('token');
    fetchMock.mockResolvedValue(json(200, sparseDetail));

    await getMovieDetail(550);

    expect((call()[1].headers as Record<string, string>).Authorization).toBe('Bearer token');
  });

  it('토큰이 없어도 비회원으로 부른다', async () => {
    endSession('signed-out');
    fetchMock.mockResolvedValue(json(200, sparseDetail));

    await getMovieDetail(550);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect((call()[1].headers as Record<string, string>).Authorization).toBeUndefined();
  });

  it('추천 경로로 들어오면 추천 항목 ID를 붙인다', async () => {
    fetchMock.mockResolvedValue(json(200, { ...sparseDetail, recommendation: null }));

    await getMovieDetail(550, 3301);

    expect(call()[0]).toBe('/api/v1/movies/550?recommendationItemId=3301');
  });

  it('추천 항목 ID가 없으면 질의 자체를 붙이지 않는다', async () => {
    fetchMock.mockResolvedValue(json(200, sparseDetail));

    await getMovieDetail(550);

    expect(call()[0]).toBe('/api/v1/movies/550');
  });

  it('없는 영화는 404 RESOURCE_NOT_FOUND로 던진다', async () => {
    fetchMock.mockResolvedValue(
      json(404, { code: 'RESOURCE_NOT_FOUND', message: '영화를 찾을 수 없습니다.' }),
    );

    await expect(getMovieDetail(999)).rejects.toMatchObject({
      status: 404,
      code: 'RESOURCE_NOT_FOUND',
    });
    await expect(getMovieDetail(999)).rejects.toBeInstanceOf(ApiError);
  });
});

describe('API-032 공개 한줄평 목록', () => {
  const page = {
    items: [
      {
        ratingId: 91,
        user: { userId: 1024, nickname: '팝콘러버' },
        score: 5,
        comment: '밤에 보면 안 됨',
        mine: false,
        spoiler: true,
        createdAt: '2026-08-30T05:21:00Z',
      },
    ],
    nextCursor: 'next-page',
    hasNext: true,
  };

  it('회원 토큰으로 영화의 첫 페이지를 조회하고 페이지 응답을 그대로 돌려준다', async () => {
    startSession('token');
    fetchMock.mockResolvedValue(json(200, page));

    expect(await getMovieReviews(550)).toEqual(page);
    expect(call()[0]).toBe('/api/v1/movies/550/reviews');
    expect(call()[1].method).toBe('GET');
    expect((call()[1].headers as Record<string, string>).Authorization).toBe('Bearer token');
  });

  it('다음 페이지는 서버가 준 cursor와 요청한 size를 질의로 보낸다', async () => {
    startSession('token');
    fetchMock.mockResolvedValue(json(200, { items: [], nextCursor: null, hasNext: false }));

    await getMovieReviews(550, 'next-page', 50);

    expect(call()[0]).toBe('/api/v1/movies/550/reviews?cursor=next-page&size=50');
  });
});

describe('API-031 유사 영화 추천', () => {
  it('실제 API를 선택 인증으로 호출하고 응답의 items를 반환한다', async () => {
    startSession('token');
    const items = [
      {
        movieId: 807,
        title: '세븐',
        posterUrl: null,
        averageRating: 4.2,
        similarity: 0.87,
      },
    ];
    fetchMock.mockResolvedValue(json(200, { baseMovieId: 550, items }));

    await expect(getSimilarMovies(550)).resolves.toEqual(items);
    expect(call()[0]).toBe('/api/v1/movies/550/similar?size=10');
    expect((call()[1].headers as Record<string, string>).Authorization).toBe('Bearer token');
  });
});
