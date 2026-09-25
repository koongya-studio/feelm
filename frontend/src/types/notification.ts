/** API-070~074 인앱 알림 계약. */
export type NotificationType =
  | 'PARTY_INVITE'
  | 'PARTY_INVITE_ACCEPTED'
  | 'WATCH_CONFIRM'
  /** 반기 취향 리포트가 공개됐다(업무 -858). 대상은 `TASTE_REPORT`, `targetId`는 리포트 번호 */
  | 'REPORT_ISSUED';

export type NotificationTargetType = 'PARTY_INVITATION' | 'PARTY' | 'WATCH_RECORD' | 'TASTE_REPORT';

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
