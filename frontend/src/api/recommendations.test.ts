/*
 * 추천 이력 조회 (업무 S15P21E106-478).
 *
 * 화면에서는 드러나지 않는 것을 고정한다.
 * - **첫 장에는 `cursor`를 싣지 않는다.** 빈 커서를 보내면 계약이 `400`으로 거절한다
 * - **다음 장은 직전 응답의 `nextCursor`만 더해 부른다**
 * - **회원 전용이라 토큰을 싣는다.** 목업 시절에는 인증 없이도 그려졌다
 * - **서버가 준 `dismissed` · `watchStatus`를 그대로 돌려준다.** 목업 시절에는 세션
 *   저장소의 관심없음 · 평가를 여기서 덮어썼다. 서버 이력에 다시 얹으면 서버가 아는
 *   상태와 화면이 어긋난다
 *
 * API-045는 업무 -610에서 실제 호출로 전환했고, API-047은 별도 프론트 연동 전이라 이
 * 파일에서 다루지 않는다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getRecommendationHistory, getRecommendations } from './recommendations';
import { ApiError } from './apiError';
import { resetSessionForTest, startSession } from './session';
import type { RecommendationHistory, RecommendationHistorySet } from '../types/recommendation';

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

function set(overrides: Partial<RecommendationHistorySet> = {}): RecommendationHistorySet {
  return {
    recommendationSetId: 7001,
    generatedAt: '2026-09-15T03:00:00Z',
    items: [
      {
        recommendationItemId: 3301,
        type: 'TASTE',
        movie: { movieId: 550, title: '파이트 클럽', posterUrl: null },
        reason: { summary: '스릴러를 높게 평가하셨어요' },
        dismissed: false,
        watchStatus: null,
      },
    ],
    ...overrides,
  };
}

function page(overrides: Partial<RecommendationHistory> = {}): RecommendationHistory {
  return { items: [set()], nextCursor: null, hasNext: false, ...overrides };
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

describe('API-045 기본 추천 조회', () => {
  it('회원 토큰으로 GET /recommendations를 부르고 세트를 그대로 돌려준다', async () => {
    const body = {
      recommendationSetId: 7001,
      generatedAt: '2026-09-18T00:00:00Z',
      items: [
        {
          recommendationItemId: 3301,
          type: 'TASTE',
          movie: {
            movieId: 550,
            title: '파이트 클럽',
            posterUrl: null,
            releaseYear: 1999,
            genres: ['드라마'],
            averageRating: 4.3,
            ottProviders: ['NETFLIX'],
          },
          flavorCode: 'FLAVOR_A',
          reason: { summary: '스릴러를 높게 평가하셨어요', factors: [] },
        },
      ],
    };
    fetchMock.mockResolvedValueOnce(json(200, body));

    const result = await getRecommendations();

    expect(requestedUrl()).toBe('/api/v1/recommendations');
    expect(requestedHeaders().Authorization).toBe('Bearer token');
    expect(result).toEqual(body);
  });

  it('빈 세트도 그대로 돌려준다 — 목업 카드로 채우지 않는다', async () => {
    fetchMock.mockResolvedValueOnce(
      json(200, { recommendationSetId: 1, generatedAt: '2026-09-18T00:00:00Z', items: [] }),
    );

    const result = await getRecommendations();

    expect(result.items).toEqual([]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('추천 서비스가 닫혀 있으면 503 코드가 그대로 올라온다', async () => {
    fetchMock.mockResolvedValueOnce(
      json(503, {
        code: 'RECOMMENDATION_UNAVAILABLE',
        message: '추천 서비스를 일시적으로 사용할 수 없습니다.',
      }),
    );

    await expect(getRecommendations()).rejects.toMatchObject({
      status: 503,
      code: 'RECOMMENDATION_UNAVAILABLE',
    });
  });

  it('동시에 시작한 조회는 한 번만 보내고 완료 뒤 다음 조회에서 최신 세트를 확인한다', async () => {
    const firstBody = {
      recommendationSetId: 7001,
      generatedAt: '2026-09-18T00:00:00Z',
      items: [],
    };
    const nextBody = {
      recommendationSetId: 7002,
      generatedAt: '2026-09-18T00:01:00Z',
      items: [],
    };
    let resolveFirst: ((response: Response) => void) | undefined;

    fetchMock
      .mockReturnValueOnce(
        new Promise<Response>((resolve) => {
          resolveFirst = resolve;
        }),
      )
      .mockResolvedValueOnce(json(200, nextBody));

    const first = getRecommendations();
    const duplicate = getRecommendations();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    resolveFirst?.(json(200, firstBody));
    await expect(Promise.all([first, duplicate])).resolves.toEqual([firstBody, firstBody]);

    await expect(getRecommendations()).resolves.toEqual(nextBody);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});

describe('API-048 추천 이력 조회', () => {
  it('첫 장은 커서 없이 회원 토큰으로 부른다', async () => {
    fetchMock.mockResolvedValue(json(200, page()));

    await getRecommendationHistory();

    const url = new URL(requestedUrl(), 'http://test');

    expect(url.pathname).toBe('/api/v1/recommendations/history');
    expect(url.searchParams.has('cursor')).toBe(false);
    expect(requestedHeaders().Authorization).toBe('Bearer token');
  });

  it('다음 장은 직전 응답의 커서만 더해 부른다', async () => {
    fetchMock.mockResolvedValue(json(200, page()));

    await getRecommendationHistory('cursor-2');

    expect(new URL(requestedUrl(), 'http://test').searchParams.get('cursor')).toBe('cursor-2');
  });

  it('size는 세트 수로 그대로 싣는다', async () => {
    fetchMock.mockResolvedValue(json(200, page()));

    await getRecommendationHistory('cursor-2', 6);

    const params = new URL(requestedUrl(), 'http://test').searchParams;

    expect(params.get('cursor')).toBe('cursor-2');
    expect(params.get('size')).toBe('6');
  });

  it('세트와 커서를 그대로 돌려준다', async () => {
    fetchMock.mockResolvedValue(json(200, page({ nextCursor: 'cursor-2', hasNext: true })));

    const result = await getRecommendationHistory();

    expect(result.items).toHaveLength(1);
    expect(result.items[0].recommendationSetId).toBe(7001);
    expect(result.items[0].items[0].movie.title).toBe('파이트 클럽');
    expect(result.nextCursor).toBe('cursor-2');
    expect(result.hasNext).toBe(true);
  });

  it('서버가 준 관심없음과 감상 상태를 덮어쓰지 않는다', async () => {
    // 세션 저장소에는 목업 추천 카드(2.3-A)에서 처리한 관심없음이 남아 있다
    fetchMock.mockResolvedValue(
      json(200, page({ items: [set({ items: [{ ...set().items[0], watchStatus: 'RATED' }] })] })),
    );

    const result = await getRecommendationHistory();

    expect(result.items[0].items[0].dismissed).toBe(false);
    expect(result.items[0].items[0].watchStatus).toBe('RATED');
  });

  it('회원 전용이라 토큰 없이 부르면 401이 그대로 올라온다', async () => {
    resetSessionForTest();
    fetchMock.mockResolvedValue(json(401, { code: 'UNAUTHORIZED', message: '인증이 필요합니다.' }));

    const error = await getRecommendationHistory().catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(401);
  });
});
