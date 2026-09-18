/*
 * 취향 필름 · 팝콘 (업무 S15P21E106-392) · 취향 분석 (업무 S15P21E106-479).
 *
 * 화면에서는 드러나지 않는 세 가지를 고정한다.
 * - 팝콘 맛 기준정보(API-044)는 **공개 API**라 토큰을 싣지 않고, `items` 래퍼를 벗긴다
 * - 프레임 상세(API-043)의 `404`는 계약 코드 그대로 올라가야 화면이 실패 상태로 간다
 * - 취향 분석(API-040)은 캐시가 없는 사용자의 `insufficientData` 응답을 그대로 돌려준다.
 *   `analyzedAt: null`이 오는 유일한 API라 여기서 못 박는다
 *
 * migration 전·실패 상태에서 `items`가 비어 오는 경우도 방어한다. 빈 배열은 오류가
 * 아니므로 던지지 않는다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  getFrameDetail,
  getMyFrames,
  getMyTaste,
  getPopcornBucket,
  getPopcornFlavors,
} from './taste';
import { resetSessionForTest, startSession } from './session';

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

function headers(index = 0): Record<string, string> {
  return call(index)[1].headers as Record<string, string>;
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

describe('API-040 취향 분석·키워드', () => {
  it('회원 토큰을 싣고 분석 결과를 그대로 돌려준다', async () => {
    const body = {
      ratingCount: 42,
      insufficientData: false,
      analysis: {
        genres: [{ code: 'THRILLER', name: '스릴러', count: 12, averageScore: 4.4, share: 0.29 }],
        countries: [],
        eras: [],
        directors: [],
      },
      keywords: [{ code: 'DARK_THRILLER_LOVER', label: '어둠 속 스릴러 애호가' }],
      analyzedAt: '2026-08-30T05:21:00Z',
    };
    fetchMock.mockResolvedValue(json(200, body));

    expect(await getMyTaste()).toEqual(body);
    expect(call()[0]).toBe('/api/v1/users/me/taste');
    expect(headers().Authorization).toBe('Bearer token');
  });

  // 평가가 없어 캐시가 없는 사용자. 실패가 아니라 200이며 화면은 빈 상태로 다룬다
  it('분석 캐시가 없으면 insufficientData 응답을 오류로 바꾸지 않는다', async () => {
    const body = {
      ratingCount: 0,
      insufficientData: true,
      analysis: { genres: [], countries: [], eras: [], directors: [] },
      keywords: [],
      analyzedAt: null,
    };
    fetchMock.mockResolvedValue(json(200, body));

    await expect(getMyTaste()).resolves.toEqual(body);
  });

  it('서버 실패는 상태 코드와 계약 코드를 그대로 올린다', async () => {
    fetchMock.mockResolvedValue(
      json(503, { code: 'SERVICE_UNAVAILABLE', message: '일시적으로 요청을 처리할 수 없습니다.' }),
    );

    await expect(getMyTaste()).rejects.toMatchObject({ status: 503, code: 'SERVICE_UNAVAILABLE' });
  });
});

describe('API-042 내 필름(프레임 목록)', () => {
  it('첫 페이지를 질의 없이 받는다', async () => {
    const body = {
      totalCount: 1,
      items: [
        {
          frameId: 77,
          movie: { movieId: 550, title: '파이트 클럽', posterUrl: null },
          score: 4.5,
          flavorCode: null,
          createdAt: '2026-09-01T00:00:00Z',
        },
      ],
      nextCursor: null,
      hasNext: false,
    };
    fetchMock.mockResolvedValue(json(200, body));

    expect(await getMyFrames()).toEqual(body);
    expect(call()[0]).toBe('/api/v1/users/me/frames');
    expect(headers().Authorization).toBe('Bearer token');
  });

  it('다음 장은 직전 응답의 커서로 부른다', async () => {
    fetchMock.mockResolvedValue(
      json(200, { totalCount: 21, items: [], nextCursor: null, hasNext: false }),
    );

    await getMyFrames('MjAyNi0wOS0xOA');

    expect(call()[0]).toBe('/api/v1/users/me/frames?cursor=MjAyNi0wOS0xOA');
  });
});

describe('API-043 프레임 상세', () => {
  it('프레임 번호를 경로에 넣는다', async () => {
    fetchMock.mockResolvedValue(json(200, { frameId: 77 }));

    await getFrameDetail(77);

    expect(call()[0]).toBe('/api/v1/frames/77');
  });

  it('없는 프레임과 남의 프레임의 404를 계약 코드 그대로 올린다', async () => {
    fetchMock.mockResolvedValue(
      json(404, { code: 'RESOURCE_NOT_FOUND', message: '프레임을 찾을 수 없습니다.' }),
    );

    await expect(getFrameDetail(77)).rejects.toMatchObject({
      status: 404,
      code: 'RESOURCE_NOT_FOUND',
    });
  });
});

describe('API-041 팝콘 버킷', () => {
  it('집계를 그대로 돌려준다', async () => {
    const body = {
      totalCount: 3,
      flavors: [
        {
          flavorCode: 'FLAVOR_A',
          displayName: '오리지널 솔트',
          color: '#F0D8B4',
          popcornCount: 3,
          averageScore: 4.4,
          discoveredAt: null,
        },
      ],
    };
    fetchMock.mockResolvedValue(json(200, body));

    expect(await getPopcornBucket()).toEqual(body);
    expect(call()[0]).toBe('/api/v1/users/me/popcorn');
  });
});

describe('API-044 팝콘 맛 기준정보', () => {
  it('`items` 래퍼를 벗기고 토큰을 싣지 않는다', async () => {
    const flavor = {
      code: 'FLAVOR_A',
      displayName: '오리지널 솔트',
      color: '#F0D8B4',
      representativeKeywords: ['다큐멘터리'],
      sortOrder: 1,
    };
    fetchMock.mockResolvedValue(json(200, { items: [flavor] }));

    expect(await getPopcornFlavors()).toEqual([flavor]);
    expect(call()[0]).toBe('/api/v1/popcorn-flavors');
    // 공개 API다. 회원 토큰을 보내지 않는다
    expect(headers().Authorization).toBeUndefined();
  });

  // migration 전·실패 상태의 빈 목록도 전송 오류로 바꾸지 않는다
  it('기준정보가 없으면 빈 목록을 돌려준다', async () => {
    fetchMock.mockResolvedValue(json(200, { items: [] }));

    expect(await getPopcornFlavors()).toEqual([]);
  });
});
