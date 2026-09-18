/* API-070~074 인앱 알림 서버 계약. */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  dismissNotification,
  getLatestPushNotification,
  getNotifications,
  getUnreadNotificationCount,
  readAllNotifications,
  readNotification,
} from './notifications';
import { resetSessionForTest, startSession } from './session';
import type { NotificationItem, NotificationList } from '../types/notification';

const fetchMock = vi.fn();

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function item(overrides: Partial<NotificationItem> = {}): NotificationItem {
  return {
    notificationId: 101,
    type: 'PARTY_INVITE',
    title: '파티 모드 초대',
    body: '새로운 파티 초대가 도착했어요.',
    targetType: 'PARTY_INVITATION',
    targetId: 501,
    readAt: null,
    notifiedAt: '2026-09-17T09:00:00Z',
    ...overrides,
  };
}

function page(items: NotificationItem[], nextCursor: string | null = null): NotificationList {
  return { items, nextCursor, hasNext: nextCursor !== null };
}

function call(index = 0) {
  return fetchMock.mock.calls[index] as [string, RequestInit];
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

describe('API-070 인앱 알림 목록', () => {
  it('서버 항목을 그대로 받고 다음 커서가 있으면 끝까지 조회한다', async () => {
    const accepted = item({
      notificationId: 102,
      type: 'PARTY_INVITE_ACCEPTED',
      title: '파티 모드 초대 수락',
      targetType: 'PARTY',
      targetId: 77,
      notifiedAt: '2026-09-17T10:00:00Z',
    });
    fetchMock
      .mockResolvedValueOnce(json(200, page([accepted], 'cursor-2')))
      .mockResolvedValueOnce(json(200, page([item()])));

    await expect(getNotifications()).resolves.toEqual([accepted, item()]);
    expect(call(0)[0]).toBe('/api/v1/notifications?size=50');
    expect(call(1)[0]).toBe('/api/v1/notifications?cursor=cursor-2&size=50');
  });

  it('동시에 시작한 목록 요청은 공유한다', async () => {
    fetchMock.mockResolvedValue(json(200, page([item()])));

    const [first, second] = await Promise.all([getNotifications(), getNotifications()]);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(second).toBe(first);
  });

  it('잠금화면 예시는 감상 확인 알림을 고른다', async () => {
    const watch = item({
      notificationId: 103,
      type: 'WATCH_CONFIRM',
      title: '영화 잘 보셨나요?',
      targetType: 'WATCH_RECORD',
      targetId: 4004,
    });
    fetchMock.mockResolvedValue(json(200, page([item(), watch])));

    await expect(getLatestPushNotification()).resolves.toEqual(watch);
  });
});

describe('API-071~074 알림 상태', () => {
  it('미확인 개수를 조회한다', async () => {
    fetchMock.mockResolvedValue(json(200, { count: 2 }));

    await expect(getUnreadNotificationCount()).resolves.toBe(2);
    expect(call()[0]).toBe('/api/v1/notifications/unread-count');
  });

  it('한 건과 전체 읽음을 PATCH한다', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await readNotification(101);
    await readAllNotifications();

    expect(call(0)[0]).toBe('/api/v1/notifications/101/read');
    expect(call(0)[1].method).toBe('PATCH');
    expect(call(1)[0]).toBe('/api/v1/notifications/read-all');
    expect(call(1)[1].method).toBe('PATCH');
  });

  it('숨김은 알림 번호로 DELETE한다', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await dismissNotification(101);

    expect(call()[0]).toBe('/api/v1/notifications/101');
    expect(call()[1].method).toBe('DELETE');
  });
});
