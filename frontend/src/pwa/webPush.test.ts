import { afterEach, describe, expect, it, vi } from 'vitest';
import { clearWebPushSubscription } from './webPush';

const LEGACY_KEY = 'feelm.webPushSubscriptionId';

function registrationWith(subscription: { unsubscribe: () => Promise<boolean> } | null) {
  return {
    pushManager: { getSubscription: vi.fn(async () => subscription) },
  } as unknown as ServiceWorkerRegistration;
}

afterEach(() => {
  window.localStorage.clear();
});

describe('남은 Web Push 구독 정리', () => {
  it('이 기기에 남은 구독을 지우고 예전 구독 번호도 지운다', async () => {
    const unsubscribe = vi.fn(async () => true);

    window.localStorage.setItem(LEGACY_KEY, '42');

    await clearWebPushSubscription(registrationWith({ unsubscribe }));

    expect(unsubscribe).toHaveBeenCalledTimes(1);
    expect(window.localStorage.getItem(LEGACY_KEY)).toBeNull();
  });

  it('구독이 없거나 서비스 워커가 없으면 아무것도 하지 않는다', async () => {
    await expect(clearWebPushSubscription(registrationWith(null))).resolves.toBeUndefined();
    await expect(clearWebPushSubscription(null)).resolves.toBeUndefined();
  });

  it('구독을 지우다 실패해도 오류를 올리지 않는다', async () => {
    const unsubscribe = vi.fn(async () => {
      throw new Error('이미 버린 구독');
    });

    window.localStorage.setItem(LEGACY_KEY, '42');

    await expect(
      clearWebPushSubscription(registrationWith({ unsubscribe })),
    ).resolves.toBeUndefined();
    expect(window.localStorage.getItem(LEGACY_KEY)).toBeNull();
  });
});
