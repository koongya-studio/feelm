/* API-070~074 인앱 알림 접근 함수. 브라우저 푸시(API-075~077)는 별도 업무에서 연결한다. */
import { request } from './client';
import type {
  NotificationItem,
  NotificationList,
  NotificationUnreadCount,
} from '../types/notification';

const NOTIFICATIONS_PATH = '/notifications';
const PAGE_SIZE = 50;

/** 목록과 잠금화면 예시가 동시에 열려도 진행 중인 전체 목록 요청은 공유한다. */
let listingRequest: Promise<NotificationItem[]> | null = null;

async function listAllNotifications(): Promise<NotificationItem[]> {
  const items: NotificationItem[] = [];
  let cursor: string | undefined;
  let hasNext: boolean;

  do {
    const page = await request<NotificationList>(NOTIFICATIONS_PATH, {
      query: { ...(cursor === undefined ? {} : { cursor }), size: PAGE_SIZE },
    });
    items.push(...page.items);
    cursor = page.nextCursor ?? undefined;
    hasNext = page.hasNext && cursor !== undefined;
  } while (hasNext);

  return items;
}

/** API-070 인앱 알림 목록. 서버가 최신순으로 준다. */
export async function getNotifications(): Promise<NotificationItem[]> {
  listingRequest ??= listAllNotifications().finally(() => {
    listingRequest = null;
  });

  return listingRequest;
}

/** API-071 미확인 알림 개수. */
export async function getUnreadNotificationCount(): Promise<number> {
  const result = await request<NotificationUnreadCount>(`${NOTIFICATIONS_PATH}/unread-count`);

  return result.count;
}

/** 8.2 예시 화면이 보여줄 한 건. 실제 Web Push 수신은 API-075~077 연동에서 구현한다. */
export async function getLatestPushNotification(): Promise<NotificationItem | null> {
  const items = await getNotifications();

  return items.find((item) => item.type === 'WATCH_CONFIRM') ?? null;
}

/** API-072 알림 읽음 처리. */
export async function readNotification(notificationId: number): Promise<void> {
  return request<void>(`${NOTIFICATIONS_PATH}/${notificationId}/read`, { method: 'PATCH' });
}

/** API-073 전체 알림 읽음 처리. */
export async function readAllNotifications(): Promise<void> {
  return request<void>(`${NOTIFICATIONS_PATH}/read-all`, { method: 'PATCH' });
}

/** API-074 알림 숨김. 원본 사건은 삭제하지 않고 목록에서만 제외한다. */
export async function dismissNotification(notificationId: number): Promise<void> {
  return request<void>(`${NOTIFICATIONS_PATH}/${notificationId}`, { method: 'DELETE' });
}
