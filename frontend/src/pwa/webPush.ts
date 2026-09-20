/*
 * 브라우저 Web Push 구독 (업무 S15P21E106-583).
 *
 * 이 기기의 `PushSubscription`과 서버 구독(API-076 · 077)을 한 쌍으로 맞춘다. 서버 호출은
 * `api/push.ts`, 받은 푸시를 띄우고 누르면 여는 일은 `public/service-worker.js`가 맡는다.
 *
 * - **켜기**는 사용자가 7.8-C에서 스위치를 누를 때만 한다. 알림 권한 창은 누른 손길 안에서만
 *   띄운다 — 앱이 먼저 묻지 않는다
 * - **로그인 상태로 앱이 뜨면** 이미 있는 구독을 서버에 다시 등록한다(`syncWebPush`). 등록은
 *   멱등이고, 같은 기기에서 다른 계정으로 로그인했으면 서버가 새 계정으로 옮긴다
 * - **로그아웃**하면 서버 구독과 기기 구독을 함께 지운다(`releaseWebPush`). 로그아웃한 기기로
 *   이전 계정의 푸시가 오면 안 된다(사용자 결정 2026-09-19)
 * - **세션이 만료**돼 로그아웃 상태가 되면 기기 구독만 지운다(`forgetWebPush`). 토큰이 없어
 *   서버 해제를 보낼 수 없다. 서버의 다음 발송이 Push Service에서 만료(404 · 410)로 돌아온다
 * - **탈퇴**하면 기기 구독만 지운다(`forgetWebPush`). 탈퇴한 계정은 서버가 해제 요청을 받지
 *   않는다(API-077은 활성 회원만). 기기 구독이 없으면 푸시가 올 곳도 없다
 *
 * 서버 구독 번호는 기기에 남긴다(`localStorage`). 해제할 때 필요하고, 없으면 등록을 다시 보내
 * 번호를 받는다(멱등이라 안전하다).
 */
import {
  getWebPushConfig,
  registerWebPushSubscription,
  unregisterWebPushSubscription,
} from '../api/push';
import { ApiError } from '../api/apiError';
import { isMockSession } from '../api/mockAccount';
import type { WebPushSubscriptionRequest } from '../types/notification';

/**
 * 이 기기의 푸시 상태.
 *
 * | 값 | 뜻 |
 * | --- | --- |
 * | `on` | 구독이 있고 서버에 등록돼 있다 |
 * | `off` | 켤 수 있지만 꺼져 있다 |
 * | `expired` | 켜 두었는데 브라우저가 구독을 버렸다. 다시 켜야 한다 |
 * | `denied` | 브라우저 알림 권한이 거부돼 있다. 브라우저 설정에서만 풀린다 |
 * | `install-required` | iOS에서 홈 화면에 추가하지 않은 채 열었다 |
 * | `unsupported` | 이 브라우저는 Web Push를 못 한다 |
 * | `unavailable` | 서버가 Web Push를 꺼 두었다 |
 */
export type WebPushStatus =
  'on' | 'off' | 'expired' | 'denied' | 'install-required' | 'unsupported' | 'unavailable';

const SUBSCRIPTION_KEY = 'feelm.webPushSubscriptionId';

function storage(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.localStorage;
  } catch {
    return null;
  }
}

function savedSubscriptionId(): number | null {
  try {
    const value = Number(storage()?.getItem(SUBSCRIPTION_KEY));

    return Number.isInteger(value) && value > 0 ? value : null;
  } catch {
    return null;
  }
}

function saveSubscriptionId(subscriptionId: number | null) {
  try {
    if (subscriptionId === null) storage()?.removeItem(SUBSCRIPTION_KEY);
    else storage()?.setItem(SUBSCRIPTION_KEY, String(subscriptionId));
  } catch {
    // 저장소를 못 쓰는 탭이다. 해제할 때 등록을 다시 보내 번호를 받는다
  }
}

/** iOS · iPadOS에서 홈 화면 앱이 아닌 사파리 탭으로 열었는지 */
function isIosBrowserTab(): boolean {
  if (typeof navigator === 'undefined') return false;

  const ios =
    /iPad|iPhone|iPod/.test(navigator.userAgent) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  const standalone =
    (navigator as Navigator & { standalone?: boolean }).standalone === true ||
    (typeof window.matchMedia === 'function' &&
      window.matchMedia('(display-mode: standalone)').matches);

  return ios && !standalone;
}

function browserSupportsPush(): boolean {
  return (
    typeof window !== 'undefined' &&
    'serviceWorker' in navigator &&
    'PushManager' in window &&
    'Notification' in window
  );
}

/**
 * 앱이 등록한 서비스 워커. 개발 서버처럼 등록하지 않은 환경이면 `null`이다.
 *
 * `serviceWorker.ready`를 기다리지 않는다 — 등록이 없으면 영원히 끝나지 않는다.
 */
async function registration(): Promise<ServiceWorkerRegistration | null> {
  if (!browserSupportsPush()) return null;

  try {
    return (await navigator.serviceWorker.getRegistration(import.meta.env.BASE_URL)) ?? null;
  } catch {
    return null;
  }
}

/** 서버가 준 base64url 공개키를 `applicationServerKey`가 받는 바이트로 바꾼다 */
export function decodeBase64Url(value: string): Uint8Array<ArrayBuffer> {
  const base64 = value.replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(new ArrayBuffer(binary.length));

  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }

  return bytes;
}

/** 브라우저 구독을 API-076 본문으로 바꾼다. 키가 빠진 구독은 보낼 수 없다 */
function toRequest(subscription: PushSubscription): WebPushSubscriptionRequest {
  const json = subscription.toJSON();
  const p256dh = json.keys?.p256dh;
  const auth = json.keys?.auth;

  if (!json.endpoint || !p256dh || !auth) {
    throw new Error('브라우저 구독에 암호화 키가 없습니다.');
  }

  return {
    endpoint: json.endpoint,
    expirationTime: json.expirationTime ?? null,
    keys: { p256dh, auth },
  };
}

async function register(subscription: PushSubscription): Promise<number> {
  const { subscriptionId } = await registerWebPushSubscription(toRequest(subscription));

  saveSubscriptionId(subscriptionId);

  return subscriptionId;
}

function isPushUnavailable(error: unknown): boolean {
  return error instanceof ApiError && error.status === 503 && error.code === 'PUSH_UNAVAILABLE';
}

/**
 * 이 기기의 푸시 상태를 읽는다. 서버에 묻지 않는다 — 서버가 꺼 두었는지는 켤 때 안다.
 */
export async function getWebPushStatus(): Promise<WebPushStatus> {
  if (isIosBrowserTab() && !browserSupportsPush()) return 'install-required';
  if (!browserSupportsPush()) return 'unsupported';
  if (isMockSession()) return 'unavailable';
  if (Notification.permission === 'denied') return 'denied';

  const current = await registration();

  if (!current) return 'unsupported';

  const subscription = await current.pushManager.getSubscription();

  if (subscription) return Notification.permission === 'granted' ? 'on' : 'off';

  // 켜 둔 흔적(서버 구독 번호)은 있는데 브라우저 구독이 없다 — 브라우저가 만료시켰다
  return savedSubscriptionId() !== null ? 'expired' : 'off';
}

/**
 * 푸시를 켠다. **사용자가 누른 손길 안에서 불러야 한다** — 알림 권한 창을 띄운다.
 *
 * 켜지 못한 이유는 상태로 돌려준다. 네트워크 · 서버 오류는 그대로 던진다.
 */
export async function enableWebPush(): Promise<WebPushStatus> {
  const status = await getWebPushStatus();

  if (status === 'on') return 'on';
  if (status !== 'off' && status !== 'expired') return status;

  const config = await getWebPushConfig();

  if (!config.enabled || !config.publicKey) return 'unavailable';

  const permission = await Notification.requestPermission();

  if (permission === 'denied') return 'denied';
  if (permission !== 'granted') return 'off';

  const current = await registration();

  if (!current) return 'unsupported';

  const subscription =
    (await current.pushManager.getSubscription()) ??
    (await current.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: decodeBase64Url(config.publicKey),
    }));

  try {
    await register(subscription);
  } catch (error) {
    // 서버에 못 올린 구독은 남기지 않는다. 남기면 켜진 것처럼 보이는데 푸시는 오지 않는다
    await subscription.unsubscribe().catch(() => undefined);
    saveSubscriptionId(null);

    if (isPushUnavailable(error)) return 'unavailable';

    throw error;
  }

  return 'on';
}

/** 서버 구독을 해제한다. 이미 없으면(`404`) 해제된 것으로 본다 */
async function unregister(subscription: PushSubscription | null): Promise<void> {
  let subscriptionId = savedSubscriptionId();

  if (subscriptionId === null && subscription) {
    subscriptionId = await register(subscription);
  }

  if (subscriptionId === null) return;

  try {
    await unregisterWebPushSubscription(subscriptionId);
  } catch (error) {
    if (!(error instanceof ApiError && error.status === 404)) throw error;
  }

  saveSubscriptionId(null);
}

/** 푸시를 끈다. 서버 구독을 먼저 지우고 기기 구독을 지운다 */
export async function disableWebPush(): Promise<void> {
  const current = await registration();
  const subscription = current ? await current.pushManager.getSubscription() : null;

  await unregister(subscription);
  await subscription?.unsubscribe();
  saveSubscriptionId(null);
}

/**
 * 로그인 상태로 앱이 뜰 때 이 기기의 구독을 서버에 다시 올린다. 실패해도 조용히 넘어간다.
 *
 * 구독이 없으면 아무것도 하지 않는다 — 권한 창을 띄우지 않는다.
 */
export async function syncWebPush(): Promise<void> {
  try {
    if (isMockSession()) return;
    if (!browserSupportsPush() || Notification.permission !== 'granted') return;

    const subscription = await (await registration())?.pushManager.getSubscription();

    if (subscription) await register(subscription);
  } catch {
    // 다음에 앱이 뜰 때 다시 올린다
  }
}

/** 로그아웃 직전. 서버 구독과 기기 구독을 지운다. 실패해도 로그아웃은 막지 않는다 */
export async function releaseWebPush(): Promise<void> {
  try {
    if (isMockSession() || !browserSupportsPush()) return;

    await disableWebPush();
  } catch {
    await forgetWebPush();
  }
}

/** 탈퇴 뒤. 서버에는 묻지 않고 기기 구독만 지운다 */
export async function forgetWebPush(): Promise<void> {
  try {
    const subscription = await (await registration())?.pushManager.getSubscription();

    await subscription?.unsubscribe();
  } catch {
    // 브라우저가 이미 버린 구독이다
  }

  saveSubscriptionId(null);
}
