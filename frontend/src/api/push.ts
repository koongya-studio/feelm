/*
 * API-075~077 Web Push 접근 함수 (업무 S15P21E106-583).
 *
 * 브라우저 구독을 만들고 지우는 일은 `pwa/webPush.ts`가 맡는다. 여기는 서버 호출만 둔다.
 */
import { request } from './client';
import type {
  WebPushConfig,
  WebPushSubscriptionRequest,
  WebPushSubscriptionResult,
} from '../types/notification';

const PUSH_PATH = '/notifications/push';

/** API-075 Web Push 공개 설정 조회 · GET /api/v1/notifications/push/config */
export async function getWebPushConfig(): Promise<WebPushConfig> {
  return request<WebPushConfig>(`${PUSH_PATH}/config`);
}

/**
 * API-076 구독 등록 · 갱신 · PUT /api/v1/notifications/push/subscriptions
 *
 * 멱등이다. 같은 endpoint를 다시 보내면 같은 구독 번호가 온다. 다른 계정이 보내면 그 계정으로
 * 옮겨 간다.
 */
export async function registerWebPushSubscription(
  subscription: WebPushSubscriptionRequest,
): Promise<WebPushSubscriptionResult> {
  return request<WebPushSubscriptionResult>(`${PUSH_PATH}/subscriptions`, {
    method: 'PUT',
    body: subscription,
  });
}

/**
 * API-077 구독 해제 · DELETE /api/v1/notifications/push/subscriptions/{subscriptionId}
 *
 * 없거나 이미 해제한 구독은 `404`다.
 */
export async function unregisterWebPushSubscription(subscriptionId: number): Promise<void> {
  return request<void>(`${PUSH_PATH}/subscriptions/${subscriptionId}`, { method: 'DELETE' });
}
