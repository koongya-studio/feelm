/*
 * 설정 데이터 접근 함수.
 * 대응 명세는 docs/api/users.md다.
 *
 * **이 파일에 목업은 없다**(업무 -610). 알림 설정(API-017 · 018) · 차단(API-020 ~ 022) ·
 * 탈퇴(API-013)는 전부 서버다.
 *
 * 7.8-C가 그리는 `파티 초대 알림` · `리포트 발급 알림`은 계약(API-017 NotificationType)에
 * 없다. 한때 세션 저장소에만 남겨 저장된 것처럼 보였는데, 새로고침하면 사라지는 값이라
 * 설정처럼 보이면 안 된다. 그 둘은 화면에서 준비 중으로 잠근다(`NotificationSettingsPage`).
 *
 * 7.8-B 공개 범위도 같다. 계약에 있는 것은 프로필 `visibility`(API-015, `PUBLIC` · `PRIVATE`)
 * 하나라 그 값만 `users.updateMyProfile`로 저장하고, 취향 필름 · 팝콘 · 한줄평의 공개 범위는
 * 준비 중이다(`PrivacySettingsPage`).
 */
import { request } from './client';
import type { BlockedUserList, BlockResult, NotificationSettings } from '../types/user';

const NOTIFICATION_SETTINGS_PATH = '/users/me/notification-settings';

/** 차단 목록을 한 번에 받는 상한. 계약의 `size` 최댓값이다 */
const BLOCK_PAGE_SIZE = 50;

/** API-017 알림 설정 조회 · GET /api/v1/users/me/notification-settings */
export async function getNotificationSettings(): Promise<NotificationSettings> {
  return request<NotificationSettings>(NOTIFICATION_SETTINGS_PATH);
}

/**
 * API-018 알림 설정 변경 · PUT /api/v1/users/me/notification-settings
 *
 * 전체 교체다. 계약의 세 키를 모두 보낸다.
 */
export async function updateNotificationSettings(
  settings: NotificationSettings,
): Promise<NotificationSettings> {
  const { RATING, RECOMMENDATION, MARKETING } = settings;

  return request<NotificationSettings>(NOTIFICATION_SETTINGS_PATH, {
    method: 'PUT',
    body: { RATING, RECOMMENDATION, MARKETING },
  });
}

/**
 * API-022 차단 목록 조회 · GET /api/v1/users/me/blocks
 *
 * 최근에 차단한 사람이 위다. 7.8-D가 그대로 그린다.
 */
export async function getBlockedUserList(): Promise<BlockedUserList> {
  return request<BlockedUserList>('/users/me/blocks', { query: { size: BLOCK_PAGE_SIZE } });
}

/**
 * 이 사람을 차단했는지. 목록을 못 받으면 판정하지 않는다(`false`).
 *
 * 차단 여부 하나를 묻는 API는 없어 목록에서 찾는다.
 */
export async function isBlockedUser(userId: number): Promise<boolean> {
  try {
    const list = await getBlockedUserList();

    return list.items.some((user) => user.userId === userId);
  } catch {
    return false;
  }
}

/**
 * API-020 사용자 차단 · POST /api/v1/users/{userId}/block
 *
 * 이미 차단했으면 `409 ALREADY_BLOCKED`, 없는 사람이면 `404`, 자기 자신이면 `400`이다.
 * 함께 있는 파티에서는 나가게 되므로 부르기 전에 확인을 받는다(API-020 처리 규칙).
 *
 * `[!]` 7.2-B 공개 프로필은 아직 목업(API-062 미구현)이라 거기서는 부르지 않는다 —
 * 목업 번호로 실제 차단을 부르면 늘 404였다(업무 -610).
 */
export async function blockUser(userId: number): Promise<BlockResult> {
  return request<BlockResult>(`/users/${userId}/block`, { method: 'POST' });
}

/** API-021 사용자 차단 해제 · DELETE /api/v1/users/{userId}/block. 본문 없는 204다 */
export async function unblockUser(userId: number): Promise<void> {
  return request<void>(`/users/${userId}/block`, { method: 'DELETE' });
}

/**
 * API-013 회원 탈퇴 · DELETE /api/v1/users/me
 *
 * 비밀번호로 본인을 확인한다. 소셜 전용 계정은 `409 PASSWORD_NOT_SET`이라 화면이 먼저
 * 막는다(`WithdrawPage`).
 */
export async function withdraw(password: string): Promise<void> {
  return request<void>('/users/me', { method: 'DELETE', body: { password } });
}
