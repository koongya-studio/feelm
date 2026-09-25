/*
 * 설정 데이터 접근 함수.
 * 대응 명세는 docs/api/users.md다.
 *
 * **이 파일에 목업은 없다**(업무 -610). 알림 설정(API-017 · 018) · 차단(API-020 ~ 022) ·
 * 탈퇴(API-013)는 전부 서버다.
 *
 * 7.8-C가 그리는 토글 다섯은 모두 계약(API-017 NotificationType)에 있다. `파티 초대 알림`은
 * 업무 -857(`PARTY_INVITE`), `리포트 발급 알림`은 업무 -858(`REPORT_ISSUED`)에서 들어왔다.
 *
 * 7.8-B 공개 범위는 이 파일이 아니라 `users.updateMyProfile`(API-015)이 저장한다. 프로필
 * `visibility`와 항목별 `frameVisibility` · `popcornVisibility` · `reviewVisibility`가 모두 API-014 ·
 * 015에 있다(업무 -856). 값은 `PUBLIC` · `PRIVATE` 둘이다(`PrivacySettingsPage`).
 */
import { request } from './client';
import type { SocialProvider } from '../types/auth';
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
 * 전체 교체다. 계약의 다섯 키를 모두 보낸다.
 */
export async function updateNotificationSettings(
  settings: NotificationSettings,
): Promise<NotificationSettings> {
  const { RATING, RECOMMENDATION, PARTY_INVITE, REPORT_ISSUED, MARKETING } = settings;

  return request<NotificationSettings>(NOTIFICATION_SETTINGS_PATH, {
    method: 'PUT',
    body: { RATING, RECOMMENDATION, PARTY_INVITE, REPORT_ISSUED, MARKETING },
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
 * 5.8 파티원 메뉴와 7.2-B 공개 프로필의 ⋯ 메뉴가 부른다. 7.2-B는 API-062 본문의 숫자
 * `user.userId`를 넘긴다(업무 -810. 그 전에는 목업 번호라 잠가 두었다 — 업무 -610).
 */
export async function blockUser(userId: number): Promise<BlockResult> {
  return request<BlockResult>(`/users/${userId}/block`, { method: 'POST' });
}

/** API-021 사용자 차단 해제 · DELETE /api/v1/users/{userId}/block. 본문 없는 204다 */
export async function unblockUser(userId: number): Promise<void> {
  return request<void>(`/users/${userId}/block`, { method: 'DELETE' });
}

/**
 * API-013 회원 탈퇴의 본인 확인. 본문이 둘 중 어느 쪽인지로 경로가 갈린다(업무 -435).
 *
 * - 비밀번호 경로: 현재 비밀번호
 * - 소셜 재인증 경로: 연결된 제공자에서 새로 받은 인가 코드와 그때 쓴 `redirectUri`. 서버가
 *   코드를 교환해 이 계정에 연결된 제공자 계정인지 대조한다
 */
export type WithdrawProof =
  { password: string } | { provider: SocialProvider; code: string; redirectUri: string };

/**
 * API-013 회원 탈퇴 · DELETE /api/v1/users/me
 *
 * 비밀번호가 없는 계정(소셜 전용)은 비밀번호 경로로 보내면 `409 PASSWORD_NOT_SET`이다.
 * 화면이 API-014 `hasPassword`로 미리 소셜 재인증 경로를 고른다(`WithdrawPage`, 업무 -754).
 *
 * **소셜 재인증 경로는 401에 다시 보내지 않는다.** 공통 클라이언트는 401이면 재발급한 뒤 같은
 * 요청을 한 번 더 보내는데(`client.ts`), 인가 코드는 1회용이다. 연결되지 않은 계정이라
 * `401 INVALID_CREDENTIALS`를 받은 요청을 다시 보내면 같은 코드가 두 번 나가고, 두 번째는
 * `OAUTH_CODE_INVALID`가 되어 사용자는 엉뚱한 안내를 본다. 제공자에서 돌아온 직후라 세션은
 * 막 복구된 새 토큰이므로 재발급할 일도 없다.
 */
export async function withdraw(proof: WithdrawProof): Promise<void> {
  return request<void>('/users/me', {
    method: 'DELETE',
    body: proof,
    refreshOnUnauthorized: !('code' in proof),
  });
}
