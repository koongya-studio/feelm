/** API-070~074 인앱 알림 계약. Web Push(API-075~077) 계약과는 분리한다. */
export type NotificationType = 'PARTY_INVITE' | 'PARTY_INVITE_ACCEPTED' | 'WATCH_CONFIRM';

export type NotificationTargetType = 'PARTY_INVITATION' | 'PARTY' | 'WATCH_RECORD';

export type NotificationItem = {
  notificationId: number;
  type: NotificationType;
  title: string;
  body: string;
  targetType: NotificationTargetType;
  targetId: number;
  readAt: string | null;
  notifiedAt: string;
};

export type NotificationList = {
  items: NotificationItem[];
  nextCursor: string | null;
  hasNext: boolean;
};

export type NotificationUnreadCount = {
  count: number;
};

/** API-075 Web Push 공개 설정. 서버가 꺼 두면 공개키가 없다 */
export type WebPushConfig = {
  enabled: boolean;
  publicKey: string | null;
};

/** API-076 구독 등록 · 갱신 본문. 브라우저 `PushSubscription.toJSON()`의 모양이다 */
export type WebPushSubscriptionRequest = {
  endpoint: string;
  expirationTime: number | null;
  keys: { p256dh: string; auth: string };
};

/** API-076 응답 */
export type WebPushSubscriptionResult = {
  subscriptionId: number;
};

/** Web Push payload(`Web Push 발송 규칙`). 서비스 워커가 받는 모양이다 */
export type WebPushPayload = Pick<
  NotificationItem,
  'notificationId' | 'type' | 'title' | 'body' | 'targetType' | 'targetId'
>;
