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
