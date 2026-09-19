/*
 * OTT 구독 추천 (업무 S15P21E106-564).
 *
 * 화면에서는 보이지 않는 계약의 모양을 고정한다.
 * - 목업 시절에는 구독 여부를 API-026으로 따로 채우고 순위도 다시 정렬했다. 계약은 둘 다
 *   응답에 담아 주므로 도메인 함수가 손대면 서버와 화면이 갈린다
 * - 단건 조회가 계약에 없어 목록에서 찾는다. 없는 `ottId`는 계약 공통 규약의 404로 만든다
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getOttRecommendation, getOttRecommendations } from './ottRecommendations';
import { ApiError } from './apiError';
import { resetSessionForTest, startSession } from './session';

const fetchMock = vi.fn();

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** API-068 응답 예시 그대로다(docs/api/recommendation.md) */
const body = {
  insufficientData: false,
  aggregatedAt: '2026-09-19T00:00:00Z',
  items: [
    {
      ott: { ottId: 1, code: 'NETFLIX', name: '넷플릭스', logoUrl: null },
      matchCount: 128,
      subscribed: true,
      topMovies: [{ movieId: 807, title: '세븐', posterUrl: null, releaseYear: 1995 }],
    },
    {
      ott: { ottId: 5, code: 'TVING', name: '티빙', logoUrl: null },
      matchCount: 52,
      subscribed: false,
      topMovies: [],
    },
  ],
};

beforeEach(() => {
  resetSessionForTest();
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  startSession('token');
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('API-068 OTT 구독 추천', () => {
  it('응답을 그대로 돌려준다', async () => {
    fetchMock.mockResolvedValue(json(200, body));

    expect(await getOttRecommendations()).toEqual(body);
    expect((fetchMock.mock.calls[0] as [string, RequestInit])[0]).toBe(
      '/api/v1/recommendations/otts',
    );
  });

  // 순위는 계약이 정한다. 화면이 다시 정렬하면 동점일 때 서버와 순서가 갈린다
  it('서버가 준 순서를 바꾸지 않는다', async () => {
    fetchMock.mockResolvedValue(json(200, body));

    const { items } = await getOttRecommendations();

    expect(items.map((item) => item.ott.code)).toEqual(['NETFLIX', 'TVING']);
  });

  // 구독 여부는 응답에 있다. API-026을 따로 부르지 않는다
  it('구독 여부를 위해 다른 API를 부르지 않는다', async () => {
    fetchMock.mockResolvedValue(json(200, body));

    await getOttRecommendations();

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('503 RECOMMENDATION_UNAVAILABLE을 코드 그대로 던진다', async () => {
    fetchMock.mockResolvedValue(
      json(503, { code: 'RECOMMENDATION_UNAVAILABLE', message: '사용할 수 없습니다.' }),
    );

    await expect(getOttRecommendations()).rejects.toMatchObject({
      status: 503,
      code: 'RECOMMENDATION_UNAVAILABLE',
    });
  });
});

describe('EXT-04 단건', () => {
  it('계약에 단건 조회가 없어 목록에서 찾는다', async () => {
    fetchMock.mockResolvedValue(json(200, body));

    const item = await getOttRecommendation(5);

    expect(item.ott.code).toBe('TVING');
    expect((fetchMock.mock.calls[0] as [string, RequestInit])[0]).toBe(
      '/api/v1/recommendations/otts',
    );
  });

  it('없는 ottId는 404 RESOURCE_NOT_FOUND다', async () => {
    // 응답 본문은 한 번만 읽을 수 있어 호출마다 새로 만든다
    fetchMock.mockImplementation(() => Promise.resolve(json(200, body)));

    await expect(getOttRecommendation(999)).rejects.toBeInstanceOf(ApiError);
    await expect(getOttRecommendation(999)).rejects.toMatchObject({
      status: 404,
      code: 'RESOURCE_NOT_FOUND',
    });
  });
});
