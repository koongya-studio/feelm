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
 * API-045는 업무 -610에서 실제 호출로 전환했고, API-047 관심없음은 업무 -672에서 연동했다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  dismissMovie,
  getMyDismissals,
  getRecommendationHistory,
  getRecommendations,
} from './recommendations';
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
  it('같은 편수라도 계정이 바뀌면 이전 계정의 진행 중인 응답을 공유하지 않는다', async () => {
    const firstBody = { recommendationSetId: 42, generatedAt: '2026-09-26T00:00:00Z', items: [] };
    const nextBody = { ...firstBody, recommendationSetId: 43 };
    let resolveOld!: (response: Response) => void;
    let resolveNew!: (response: Response) => void;
    fetchMock
      .mockReturnValueOnce(
        new Promise<Response>((resolve) => {
          resolveOld = resolve;
        }),
      )
      .mockReturnValueOnce(
        new Promise<Response>((resolve) => {
          resolveNew = resolve;
        }),
      );
    startSession('user-42');
    const first = getRecommendations(3);
    startSession('user-43');
    const next = getRecommendations(3);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(requestedHeaders(0).Authorization).toBe('Bearer user-42');
    expect(requestedHeaders(1).Authorization).toBe('Bearer user-43');
    resolveOld(json(200, firstBody));
    await expect(first).resolves.toEqual(firstBody);
    const duplicateNew = getRecommendations(3);
    expect(duplicateNew).toBe(next);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    resolveNew(json(200, nextBody));
    await expect(Promise.all([next, duplicateNew])).resolves.toEqual([nextBody, nextBody]);
  });

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

  it('빈 세트는 빈 채로 둔다 — 목업 카드로 채우지 않는다', async () => {
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

  /*
   * 화면별 노출 편수 (업무 S15P21E106-794 · 계약은 -793).
   *
   * 모바일은 계약의 기본값 3을 쓰므로 값을 싣지 않고, PC만 `size=6`을 싣는다. 편수가 다르면
   * 서로 다른 요청이므로 진행 중인 것에 얹지 않는다 — 합치면 폭이 넓어진 직후의 여섯 편
   * 요청이 앞서 나간 세 편 응답을 받는다.
   */
  it('PC는 여섯 편을 싣고, 모바일 세 편은 기본값이라 싣지 않는다', async () => {
    const body = { recommendationSetId: 7001, generatedAt: '2026-09-18T00:00:00Z', items: [] };

    // 응답 본문은 한 번만 읽을 수 있다. 부를 때마다 새 `Response`를 만든다
    fetchMock.mockImplementation(() => Promise.resolve(json(200, body)));

    await getRecommendations(6);
    expect(requestedUrl()).toBe('/api/v1/recommendations?size=6');

    await getRecommendations(3);
    expect(requestedUrl(1)).toBe('/api/v1/recommendations');
  });

  it('편수가 다른 조회는 진행 중이어도 각자 나간다', async () => {
    const three = { recommendationSetId: 7001, generatedAt: '2026-09-18T00:00:00Z', items: [] };
    const six = { recommendationSetId: 7002, generatedAt: '2026-09-18T00:00:00Z', items: [] };
    let resolveThree: ((response: Response) => void) | undefined;

    fetchMock
      .mockReturnValueOnce(
        new Promise<Response>((resolve) => {
          resolveThree = resolve;
        }),
      )
      .mockResolvedValueOnce(json(200, six));

    const mobile = getRecommendations(3);
    const desktop = getRecommendations(6);

    expect(fetchMock).toHaveBeenCalledTimes(2);
    resolveThree?.(json(200, three));

    await expect(mobile).resolves.toEqual(three);
    await expect(desktop).resolves.toEqual(six);
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

describe('API-047 관심없음 표시', () => {
  function requestedBody(index = 0): unknown {
    const init = (fetchMock.mock.calls[index] as [string, RequestInit])[1];

    return JSON.parse(String(init.body));
  }

  it('추천 카드에서는 추천 항목 번호를 실어 POST한다', async () => {
    fetchMock.mockResolvedValue(json(200, { movieId: 550 }));

    await dismissMovie(550, 3301);

    expect(requestedUrl()).toBe('/api/v1/movies/550/dismiss');
    expect((fetchMock.mock.calls[0] as [string, RequestInit])[1].method).toBe('POST');
    expect(requestedBody()).toEqual({ recommendationItemId: 3301 });
    expect(requestedHeaders().Authorization).toBe('Bearer token');
  });

  it('추천 밖에서는 빈 본문으로 보낸다', async () => {
    fetchMock.mockResolvedValue(json(200, { movieId: 550 }));

    await dismissMovie(550);

    expect(requestedBody()).toEqual({});
  });

  it('이미 관심없음이면 409 코드가 그대로 올라온다', async () => {
    fetchMock.mockResolvedValue(
      json(409, { code: 'ALREADY_DISMISSED', message: '이미 관심없음 처리한 영화입니다.' }),
    );

    await expect(dismissMovie(550, 3301)).rejects.toMatchObject({
      status: 409,
      code: 'ALREADY_DISMISSED',
    });
  });
});

describe('API-087 관심 없음 목록', () => {
  it('본인 endpoint와 인증 토큰으로 첫 장을 조회한다', async () => {
    const list = {
      items: [
        {
          dismissalId: 1,
          movie: { movieId: 10, title: '관심 없음', posterUrl: null },
          dismissedAt: '2026-09-25T00:00:00Z',
        },
      ],
      nextCursor: null,
      hasNext: false,
    };
    fetchMock.mockResolvedValue(json(200, list));
    expect(await getMyDismissals({ size: 18 })).toEqual(list);
    expect(requestedUrl()).toContain('/api/v1/users/me/dismissals?size=18');
    expect(requestedUrl()).not.toContain('cursor=');
    expect(requestedHeaders().Authorization).toBe('Bearer token');
  });
  it('다음 장에 직전 cursor와 size를 전달한다', async () => {
    fetchMock.mockResolvedValue(json(200, { items: [], nextCursor: null, hasNext: false }));
    await getMyDismissals({ cursor: 'issued-cursor', size: 18 });
    const url = new URL(requestedUrl(), 'https://example.test');
    expect(url.searchParams.get('cursor')).toBe('issued-cursor');
    expect(url.searchParams.get('size')).toBe('18');
  });
});
