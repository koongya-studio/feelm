/*
 * `public/service-worker.js`의 Web Push 처리(업무 S15P21E106-583).
 *
 * 워커 파일을 가짜 `self` 위에서 실행해 등록된 이벤트 처리기를 직접 부른다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import SOURCE from '../../public/service-worker.js?raw';
import { notificationPath } from '../pages/notifications/destination';
import type { NotificationItem } from '../types/notification';

const SCOPE = 'https://feelm.example/app/';

type Listener = (event: Record<string, unknown>) => void;

function loadWorker() {
  const listeners: Record<string, Listener> = {};
  const shown: { title: string; options: NotificationOptions & { data: { url: string } } }[] = [];
  const registration = {
    scope: SCOPE,
    getNotifications: vi.fn(async ({ tag }: { tag: string }) =>
      shown.filter((item) => item.options.tag === tag),
    ),
    showNotification: vi.fn(
      async (title: string, options: NotificationOptions & { data: { url: string } }) => {
        // 브라우저처럼 같은 태그는 한 장으로 바뀐다
        const index = shown.findIndex((item) => item.options.tag === options.tag);

        if (index >= 0) shown.splice(index, 1);
        shown.push({ title, options });
      },
    ),
  };
  const clients = {
    claim: vi.fn(),
    matchAll: vi.fn(async () => [] as unknown[]),
    openWindow: vi.fn(async () => null),
  };
  const self = {
    registration,
    clients,
    skipWaiting: vi.fn(),
    addEventListener: (type: string, listener: Listener) => {
      listeners[type] = listener;
    },
  };

  new Function('self', SOURCE)(self);

  return { listeners, registration, clients, shown };
}

/** 이벤트를 보내고 `waitUntil`에 걸린 일이 끝날 때까지 기다린다 */
async function dispatch(listener: Listener, event: Record<string, unknown>) {
  let pending: Promise<unknown> = Promise.resolve();

  listener({ ...event, waitUntil: (work: Promise<unknown>) => (pending = work) });
  await pending;
}

function pushEvent(payload: unknown) {
  return { data: { json: () => payload } };
}

const INVITE = {
  notificationId: 501,
  type: 'PARTY_INVITE',
  title: '파티 초대',
  body: '서현 님이 금요일 영화 모임에 초대했어요',
  targetType: 'PARTY_INVITATION',
  targetId: 77,
};

let worker: ReturnType<typeof loadWorker>;

beforeEach(() => {
  worker = loadWorker();
});

describe('서비스 워커 Web Push', () => {
  it('파티 초대 푸시를 띄우고, 누르면 열 화면 주소를 싣는다', async () => {
    await dispatch(worker.listeners.push, pushEvent(INVITE));

    expect(worker.shown).toHaveLength(1);
    expect(worker.shown[0].title).toBe('파티 초대');
    expect(worker.shown[0].options).toMatchObject({
      body: INVITE.body,
      tag: 'feelm-notification-501',
      data: { url: `${SCOPE}parties/invitations/77` },
    });
  });

  it('같은 알림 번호가 다시 오면 한 장으로 겹치고, 두 번째는 소리 없이 바꾼다', async () => {
    await dispatch(worker.listeners.push, pushEvent(INVITE));
    await dispatch(worker.listeners.push, pushEvent(INVITE));

    expect(worker.shown).toHaveLength(1);
    expect(worker.registration.showNotification).toHaveBeenCalledTimes(2);
    expect(worker.registration.showNotification.mock.calls[0][1].silent).toBe(false);
    expect(worker.registration.showNotification.mock.calls[1][1].silent).toBe(true);
  });

  it('읽을 수 없는 푸시도 빠뜨리지 않고 알림 목록으로 가는 알림을 띄운다', async () => {
    // userVisibleOnly 구독이라 알림 없이 넘기면 브라우저가 구독을 끊을 수 있다
    await dispatch(worker.listeners.push, { data: { json: () => JSON.parse('{') } });
    await dispatch(worker.listeners.push, { data: null });
    await dispatch(worker.listeners.push, pushEvent({ notificationId: 5 }));

    expect(worker.registration.showNotification).toHaveBeenCalledTimes(3);
    expect(worker.shown).toHaveLength(1);
    expect(worker.shown[0]).toMatchObject({
      title: '새 알림이 있어요',
      options: { tag: 'feelm-notification', data: { url: `${SCOPE}notifications` } },
    });
  });

  it('계약에 없는 종류 · 대상도 걸러 내지 않는다. 모르는 대상은 알림 목록을 연다', async () => {
    await dispatch(
      worker.listeners.push,
      pushEvent({ ...INVITE, notificationId: 700, type: 'SOMETHING_NEW', targetType: 'UNKNOWN' }),
    );

    expect(worker.shown).toHaveLength(1);
    expect(worker.shown[0].options.data.url).toBe(`${SCOPE}notifications`);
  });

  it('여는 화면이 앱의 알림 이동 규칙(destination.ts)과 같다', async () => {
    const cases: Pick<NotificationItem, 'targetType' | 'targetId'>[] = [
      { targetType: 'PARTY_INVITATION', targetId: 7 },
      { targetType: 'PARTY', targetId: 9 },
      { targetType: 'WATCH_RECORD', targetId: 11 },
    ];

    for (const [index, target] of cases.entries()) {
      await dispatch(
        worker.listeners.push,
        pushEvent({
          ...INVITE,
          notificationId: 600 + index,
          type: 'PARTY_INVITE_ACCEPTED',
          ...target,
        }),
      );
    }

    expect(worker.shown.map((item) => item.options.data.url)).toEqual(
      cases.map(
        (target) =>
          `${SCOPE}${notificationPath({ ...target } as NotificationItem).replace(/^\//, '')}`,
      ),
    );
  });

  it('누르면 열린 앱 창을 그 화면으로 옮겨 앞으로 가져온다', async () => {
    const focus = vi.fn();
    const app = { url: `${SCOPE}film`, navigate: vi.fn(async () => ({ focus })), focus: vi.fn() };
    const close = vi.fn();

    worker.clients.matchAll.mockResolvedValue([app]);

    await dispatch(worker.listeners.notificationclick, {
      notification: { close, data: { url: `${SCOPE}parties/invitations/77` } },
    });

    expect(close).toHaveBeenCalled();
    expect(app.navigate).toHaveBeenCalledWith(`${SCOPE}parties/invitations/77`);
    expect(focus).toHaveBeenCalled();
    expect(worker.clients.openWindow).not.toHaveBeenCalled();
  });

  it('열린 앱 창이 없으면 새 창으로 연다', async () => {
    await dispatch(worker.listeners.notificationclick, {
      notification: { close: vi.fn(), data: { url: `${SCOPE}parties/9/popcorn` } },
    });

    expect(worker.clients.openWindow).toHaveBeenCalledWith(`${SCOPE}parties/9/popcorn`);
  });
});
