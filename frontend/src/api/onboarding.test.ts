/*
 * 온보딩 초기 취향 (업무 S15P21E106-379).
 *
 * 제출 본문의 규칙이 화면에서는 드러나지 않는다. 계약은 `preferences`와 `skipped`를
 * **둘 다** 요구하고, 건너뛴 제출은 `preferences`가 비어 있어야 한다. 한쪽을 빠뜨리면
 * `400`이라 화면은 이유 없이 실패한다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from './apiError';
import { getOnboardingCards, submitOnboardingPreferences } from './onboarding';
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

const CARD = {
  movieId: 550,
  title: '파이트 클럽',
  posterUrl: null,
  releaseYear: 1999,
  genres: ['드라마'],
  flavorCode: 'FLAVOR_A',
};

beforeEach(() => {
  resetSessionForTest();
  startSession('token');
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('API-023 초기 취향 카드 목록', () => {
  it('`items` 래퍼를 벗겨 목록으로 돌려주고 장수를 질의로 보낸다', async () => {
    fetchMock.mockResolvedValue(json(200, { items: [CARD] }));

    expect(await getOnboardingCards(10)).toEqual([CARD]);
    expect(call()[0]).toBe('/api/v1/onboarding/cards?size=10');
  });

  // 장수를 넘기지 않으면 계약 기본값(20)을 서버가 정한다
  it('장수를 넘기지 않으면 질의를 붙이지 않는다', async () => {
    fetchMock.mockResolvedValue(json(200, { items: [] }));

    await getOnboardingCards();

    expect(call()[0]).toBe('/api/v1/onboarding/cards');
  });

  it('이미 마친 사용자의 409를 계약의 코드 그대로 올린다', async () => {
    fetchMock.mockResolvedValue(
      json(409, { code: 'ONBOARDING_ALREADY_COMPLETED', message: '이미 온보딩을 마쳤습니다.' }),
    );

    await expect(getOnboardingCards(10)).rejects.toMatchObject({
      status: 409,
      code: 'ONBOARDING_ALREADY_COMPLETED',
    });
  });
});

describe('API-024 초기 취향 제출', () => {
  it('고른 값과 `skipped`를 함께 보낸다', async () => {
    fetchMock.mockResolvedValue(
      json(200, { onboardingCompleted: true, savedCount: 2, skipped: false }),
    );

    const preferences = [
      { movieId: 550, preference: 'LIKE' as const },
      { movieId: 551, preference: 'DISLIKE' as const },
    ];
    const result = await submitOnboardingPreferences(preferences, false);

    const [url, init] = call();
    expect(url).toBe('/api/v1/onboarding/preferences');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual({ preferences, skipped: false });
    expect(result.savedCount).toBe(2);
  });

  /*
   * 건너뛰기는 `preferences`가 비어 있어야 한다(API-024 오류 응답). 고르다가 건너뛰면
   * 화면에는 고른 값이 남아 있으므로 여기서 비운다.
   */
  it('건너뛰면 고른 값이 있어도 빈 배열을 보낸다', async () => {
    fetchMock.mockResolvedValue(
      json(200, { onboardingCompleted: true, savedCount: 0, skipped: true }),
    );

    await submitOnboardingPreferences([{ movieId: 550, preference: 'LIKE' }], true);

    expect(JSON.parse(call()[1].body as string)).toEqual({ preferences: [], skipped: true });
  });

  it('검증 실패는 서버 문구를 그대로 전한다', async () => {
    fetchMock.mockResolvedValue(
      json(400, { code: 'VALIDATION_FAILED', message: '존재하지 않는 영화입니다.' }),
    );

    await expect(submitOnboardingPreferences([], false)).rejects.toThrow(ApiError);
  });
});
