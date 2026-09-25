/*
 * 알림 설정 · 차단 (업무 S15P21E106-394).
 *
 * 화면에서는 드러나지 않는 세 가지를 고정한다.
 * - 7.8-C의 토글은 다섯인데 **API-018로 나가는 것은 계약의 네 키뿐**이다. 나머지 하나가
 *   섞여 나가면 백엔드가 모르는 키를 받는다
 * - 계약에 없는 둘은 서버가 받아들인 뒤에만 세션 저장소에 남는다. 실패한 저장이 로컬에
 *   남으면 화면이 되돌린 값과 어긋난다
 * - 차단 오류 코드(400 · 404 · 409)는 계약 그대로 화면까지 올라간다. 7.2-B가 이미 차단
 *   (409)과 나머지를 가르기 때문이다
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  blockUser,
  getBlockedUserList,
  getNotificationSettings,
  isBlockedUser,
  unblockUser,
  updateNotificationSettings,
} from './settings';
import { isApiError } from './apiError';
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

/** 계약의 네 키(API-017 응답) */
const SERVER_SETTINGS = {
  RATING: true,
  RECOMMENDATION: true,
  PARTY_INVITE: true,
  MARKETING: false,
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

describe('API-017 알림 설정 조회', () => {
  it('계약의 네 키를 그대로 돌려준다', async () => {
    fetchMock.mockResolvedValue(json(200, SERVER_SETTINGS));

    // 7.8-C가 더 그리는 둘(파티 초대 · 리포트 발급)은 계약에 없어 여기서 만들지 않는다(업무 -610)
    expect(await getNotificationSettings()).toEqual(SERVER_SETTINGS);
    expect(call()[0]).toBe('/api/v1/users/me/notification-settings');
  });
});

describe('API-018 알림 설정 변경', () => {
  it('계약의 네 키를 모두 보내고 서버가 준 값을 돌려준다', async () => {
    fetchMock.mockResolvedValue(json(200, { ...SERVER_SETTINGS, MARKETING: true }));

    const saved = await updateNotificationSettings({ ...SERVER_SETTINGS, MARKETING: true });

    const [url, init] = call();
    expect(url).toBe('/api/v1/users/me/notification-settings');
    expect(init.method).toBe('PUT');
    expect(JSON.parse(init.body as string)).toEqual({
      RATING: true,
      RECOMMENDATION: true,
      PARTY_INVITE: true,
      MARKETING: true,
    });
    expect(saved).toEqual({ ...SERVER_SETTINGS, MARKETING: true });
  });

  it('서버가 거절하면 그대로 올라온다', async () => {
    fetchMock.mockResolvedValue(
      json(400, { code: 'VALIDATION_FAILED', message: '입력값을 확인해 주세요.' }),
    );

    await expect(
      updateNotificationSettings({ ...SERVER_SETTINGS, MARKETING: true }),
    ).rejects.toThrow();
  });
});

describe('API-022 차단 목록 조회', () => {
  it('계약 상한인 50명을 한 번에 받는다', async () => {
    const body = {
      items: [{ userId: 2048, nickname: '무비버디', blockedAt: '2026-09-16T00:00:00Z' }],
      nextCursor: null,
      hasNext: false,
    };
    fetchMock.mockResolvedValue(json(200, body));

    expect(await getBlockedUserList()).toEqual(body);
    expect(call()[0]).toBe('/api/v1/users/me/blocks?size=50');
  });

  it('목록을 못 받으면 차단 여부를 판정하지 않는다', async () => {
    // 목업 공개 프로필을 감추는 용도라 조회 실패로 프로필까지 막지 않는다
    fetchMock.mockResolvedValue(json(500, { code: 'INTERNAL_ERROR', message: '오류' }));

    expect(await isBlockedUser(2048)).toBe(false);
  });
});

describe('API-020 사용자 차단', () => {
  it('경로에 대상 번호를 넣어 POST한다', async () => {
    fetchMock.mockResolvedValue(
      json(201, { blockedUserId: 2048, blockedAt: '2026-09-16T00:00:00Z' }),
    );

    expect(await blockUser(2048)).toEqual({
      blockedUserId: 2048,
      blockedAt: '2026-09-16T00:00:00Z',
    });
    expect(call()[0]).toBe('/api/v1/users/2048/block');
    expect(call()[1].method).toBe('POST');
  });

  it('이미 차단한 사람은 409 코드로 올라온다', async () => {
    fetchMock.mockResolvedValue(
      json(409, { code: 'ALREADY_BLOCKED', message: '이미 차단한 사용자입니다.' }),
    );

    const error: unknown = await blockUser(2048).catch((reason: unknown) => reason);

    expect(isApiError(error) && error.code).toBe('ALREADY_BLOCKED');
  });
});

describe('API-021 사용자 차단 해제', () => {
  it('본문 없는 204를 받는다', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await expect(unblockUser(2048)).resolves.toBeUndefined();
    expect(call()[1].method).toBe('DELETE');
  });
});
