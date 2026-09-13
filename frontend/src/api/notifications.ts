/*
 * 앱 내 알림(8.1 · 8.2) 접근 함수. **계약에 알림 목록 API가 없다.**
 *
 * ADR-0011은 1차 MVP 알림을 받은 초대(API-052)와 감상 확인 대상(API-034)의 폴링으로 정했다.
 * 와이어프레임은 오늘의 추천 · 필름 추가까지 네 종류를 그리므로, 그대로 그리기로 했다
 * (팀 결정 2026-09-13, 업무 -308). 연동할 때는 이 파일이 두 API를 불러 알림으로 바꾸거나,
 * 알림 목록 API가 생기면 그것을 부른다.
 */
import {
  getNotifications as getStoredNotifications,
  markNotificationRead,
} from '../mocks/sessionStore';
import { respond } from './mockTransport';
import type { NotificationView } from '../types/unspecified';

/** 8.1 알림 목록. 최신이 위다 */
export async function getNotifications(): Promise<NotificationView[]> {
  const items = [...getStoredNotifications()].sort((a, b) =>
    b.createdAt.localeCompare(a.createdAt),
  );

  return respond(items, 'notifications');
}

/** 8.2 잠금화면 예시가 보여줄 한 건 — 가장 최근의 감상 확인 알림 */
export async function getLatestPushNotification(): Promise<NotificationView | null> {
  const items = await getNotifications();

  return items.find((item) => item.type === 'WATCH_CONFIRM') ?? null;
}

/** 알림을 눌렀을 때. 읽음 표식을 지운다 */
export async function readNotification(notificationId: number): Promise<void> {
  markNotificationRead(notificationId);

  return respond(undefined);
}
