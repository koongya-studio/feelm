import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/apiError';
import {
  getWebPushConfig,
  registerWebPushSubscription,
  unregisterWebPushSubscription,
} from '../api/push';
import {
  decodeBase64Url,
  disableWebPush,
  enableWebPush,
  forgetWebPush,
  getWebPushStatus,
  releaseWebPush,
  syncWebPush,
} from './webPush';

vi.mock('../api/push', () => ({
  getWebPushConfig: vi.fn(),
  registerWebPushSubscription: vi.fn(),
  unregisterWebPushSubscription: vi.fn(),
}));

const SUBSCRIPTION_KEY = 'feelm.webPushSubscriptionId';
const PUBLIC_KEY =
  'BEl62iUYgUivxIkv69yViEuiBIa-Ib9-SkvMeAtA3LFgDzkrxZJjSgSnfckjBJuBkr3qBUYIHBQFLXYp5Nksh8U';

type FakeSubscription = PushSubscription & { unsubscribe: ReturnType<typeof vi.fn> };

function fakeSubscription(endpoint = 'https://fcm.googleapis.com/fcm/send/abc'): FakeSubscription {
  return {
    endpoint,
    unsubscribe: vi.fn().mockResolvedValue(true),
    toJSON: () => ({
      endpoint,
      expirationTime: null,
      keys: { p256dh: 'p256dh-key', auth: 'auth-key' },
    }),
  } as unknown as FakeSubscription;
}

let current: FakeSubscription | null;
let permission: NotificationPermission;
const subscribe = vi.fn();
const requestPermission = vi.fn();

function installBrowser({ registered = true }: { registered?: boolean } = {}) {
  const registration = registered
    ? {
        pushManager: {
          getSubscription: vi.fn(async () => current),
          subscribe,
        },
      }
    : undefined;

  Object.defineProperty(navigator, 'serviceWorker', {
    configurable: true,
    value: { getRegistration: vi.fn(async () => registration) },
  });
  vi.stubGlobal('PushManager', class {});
  vi.stubGlobal('Notification', {
    get permission() {
      return permission;
    },
    requestPermission,
  });
}

beforeEach(() => {
  current = null;
  permission = 'default';
  localStorage.clear();
  sessionStorage.clear();
  subscribe.mockReset().mockImplementation(async () => {
    current = fakeSubscription();

    return current;
  });
  requestPermission.mockReset().mockImplementation(async () => {
    permission = 'granted';

    return permission;
  });
  vi.mocked(getWebPushConfig)
    .mockReset()
    .mockResolvedValue({ enabled: true, publicKey: PUBLIC_KEY });
  vi.mocked(registerWebPushSubscription).mockReset().mockResolvedValue({ subscriptionId: 42 });
  vi.mocked(unregisterWebPushSubscription).mockReset().mockResolvedValue(undefined);
  installBrowser();
});

afterEach(() => {
  vi.unstubAllGlobals();
  Reflect.deleteProperty(navigator, 'serviceWorker');
});

describe('Web Push 상태', () => {
  it('브라우저가 Push를 모르면 unsupported다', async () => {
    vi.stubGlobal('PushManager', undefined);
    Reflect.deleteProperty(window, 'PushManager');

    await expect(getWebPushStatus()).resolves.toBe('unsupported');
  });

  it('서비스 워커가 등록되지 않은 환경(개발 서버)은 unsupported다', async () => {
    installBrowser({ registered: false });

    await expect(getWebPushStatus()).resolves.toBe('unsupported');
  });

  it('알림 권한이 거부돼 있으면 denied다', async () => {
    permission = 'denied';

    await expect(getWebPushStatus()).resolves.toBe('denied');
  });

  it('구독 번호가 남아 있는데 브라우저 구독이 없으면 expired다', async () => {
    localStorage.setItem(SUBSCRIPTION_KEY, '42');

    await expect(getWebPushStatus()).resolves.toBe('expired');
  });

  it('권한이 있고 구독이 있으면 on이다', async () => {
    permission = 'granted';
    current = fakeSubscription();

    await expect(getWebPushStatus()).resolves.toBe('on');
  });
});

describe('Web Push 켜기', () => {
  it('공개키로 구독을 만들어 API-076에 올리고 구독 번호를 남긴다', async () => {
    await expect(enableWebPush()).resolves.toBe('on');

    expect(requestPermission).toHaveBeenCalledTimes(1);
    expect(subscribe).toHaveBeenCalledWith({
      userVisibleOnly: true,
      applicationServerKey: decodeBase64Url(PUBLIC_KEY),
    });
    expect(registerWebPushSubscription).toHaveBeenCalledWith({
      endpoint: 'https://fcm.googleapis.com/fcm/send/abc',
      expirationTime: null,
      keys: { p256dh: 'p256dh-key', auth: 'auth-key' },
    });
    expect(localStorage.getItem(SUBSCRIPTION_KEY)).toBe('42');
  });

  it('서버가 꺼 두었으면 권한을 묻지 않고 unavailable이다', async () => {
    vi.mocked(getWebPushConfig).mockResolvedValue({ enabled: false, publicKey: null });

    await expect(enableWebPush()).resolves.toBe('unavailable');
    expect(requestPermission).not.toHaveBeenCalled();
  });

  it('권한을 거부하면 구독하지 않는다', async () => {
    requestPermission.mockImplementation(async () => {
      permission = 'denied';

      return permission;
    });

    await expect(enableWebPush()).resolves.toBe('denied');
    expect(subscribe).not.toHaveBeenCalled();
  });

  it('서버 등록이 실패하면 브라우저 구독을 되돌리고 오류를 던진다', async () => {
    vi.mocked(registerWebPushSubscription).mockRejectedValue(new TypeError('Failed to fetch'));

    await expect(enableWebPush()).rejects.toThrow('Failed to fetch');
    expect(current?.unsubscribe).toHaveBeenCalled();
    expect(localStorage.getItem(SUBSCRIPTION_KEY)).toBeNull();
  });

  it('등록이 503 PUSH_UNAVAILABLE이면 unavailable이다', async () => {
    vi.mocked(registerWebPushSubscription).mockRejectedValue(
      new ApiError(503, 'PUSH_UNAVAILABLE', 'off'),
    );

    await expect(enableWebPush()).resolves.toBe('unavailable');
  });
});

describe('Web Push 끄기 · 로그인 · 로그아웃 · 탈퇴', () => {
  beforeEach(() => {
    permission = 'granted';
    current = fakeSubscription();
  });

  it('끄면 API-077로 해제하고 기기 구독을 지운다', async () => {
    localStorage.setItem(SUBSCRIPTION_KEY, '42');
    const subscription = current;

    await disableWebPush();

    expect(unregisterWebPushSubscription).toHaveBeenCalledWith(42);
    expect(subscription?.unsubscribe).toHaveBeenCalled();
    expect(localStorage.getItem(SUBSCRIPTION_KEY)).toBeNull();
  });

  it('구독 번호가 없으면 등록을 다시 보내 번호를 받아 해제한다', async () => {
    await disableWebPush();

    expect(registerWebPushSubscription).toHaveBeenCalledTimes(1);
    expect(unregisterWebPushSubscription).toHaveBeenCalledWith(42);
  });

  it('이미 해제된 구독(404)도 끈 것으로 본다', async () => {
    localStorage.setItem(SUBSCRIPTION_KEY, '42');
    vi.mocked(unregisterWebPushSubscription).mockRejectedValue(
      new ApiError(404, 'RESOURCE_NOT_FOUND', 'gone'),
    );

    await expect(disableWebPush()).resolves.toBeUndefined();
    expect(localStorage.getItem(SUBSCRIPTION_KEY)).toBeNull();
  });

  it('로그인 상태로 앱이 뜨면 있는 구독을 다시 올린다', async () => {
    await syncWebPush();

    expect(registerWebPushSubscription).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem(SUBSCRIPTION_KEY)).toBe('42');
  });

  it('권한이 없으면 다시 올리지도 권한을 묻지도 않는다', async () => {
    permission = 'default';

    await syncWebPush();

    expect(registerWebPushSubscription).not.toHaveBeenCalled();
    expect(requestPermission).not.toHaveBeenCalled();
  });

  it('로그아웃에서 서버 해제가 실패해도 기기 구독은 지우고 넘어간다', async () => {
    localStorage.setItem(SUBSCRIPTION_KEY, '42');
    vi.mocked(unregisterWebPushSubscription).mockRejectedValue(new TypeError('Failed to fetch'));
    const subscription = current;

    await expect(releaseWebPush()).resolves.toBeUndefined();
    expect(subscription?.unsubscribe).toHaveBeenCalled();
    expect(localStorage.getItem(SUBSCRIPTION_KEY)).toBeNull();
  });

  it('탈퇴 뒤에는 서버에 묻지 않고 기기 구독만 지운다', async () => {
    localStorage.setItem(SUBSCRIPTION_KEY, '42');
    const subscription = current;

    await forgetWebPush();

    expect(unregisterWebPushSubscription).not.toHaveBeenCalled();
    expect(subscription?.unsubscribe).toHaveBeenCalled();
    expect(localStorage.getItem(SUBSCRIPTION_KEY)).toBeNull();
  });
});

describe('공개키 변환', () => {
  it('base64url을 65바이트 P-256 공개키로 바꾼다', () => {
    const bytes = decodeBase64Url(PUBLIC_KEY);

    expect(bytes).toHaveLength(65);
    expect(bytes[0]).toBe(0x04);
  });
});
