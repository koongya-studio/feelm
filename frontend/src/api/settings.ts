/*
 * 설정 화면(7.8 ~ 7.8-D) 데이터 접근 함수.
 * 대응 명세는 docs/api/users.md(API-013 · API-017 · API-018 · API-020 ~ API-022)이다.
 *
 * **전부 목업이다**(팀 결정 2026-09-13, 업무 -307). API-013 회원 탈퇴를 뺀 나머지는
 * 백엔드에 구현돼 있지만 연동은 별도 업무로 한다. 연동할 때 함수 본문을 `request`로
 * 바꾸면 되도록 계약의 경로 · 본문 모양을 지킨다. 계약에 없는 값(알림 토글 둘 ·
 * 공개 범위 전체)은 `types/unspecified.ts`에 있다.
 *
 * 로그아웃(API-005)은 이미 실제 호출이다(`api/auth.ts`). 화면은 `useAuth().signOut`을 쓴다.
 */
import { findMockPublicProfile } from '../mocks/publicProfiles';
import {
  getBlockedUsers as getStoredBlockedUsers,
  getNotificationSettings as getStoredNotificationSettings,
  getPrivacySettings as getStoredPrivacySettings,
  saveBlockedUsers,
  saveNotificationSettings,
  savePrivacySettings,
} from '../mocks/sessionStore';
import { mockBlockedUsers, mockNotificationSettings, mockPrivacySettings } from '../mocks/settings';
import { mockMyProfile } from '../mocks/user';
import { respond, respondError } from './mockTransport';
import type { NotificationSettingsView, PrivacySettingsView } from '../types/unspecified';
import type { BlockedUser, BlockedUserList, BlockResult } from '../types/user';

/** API-017 알림 설정 조회 · GET /api/v1/users/me/notification-settings */
export async function getNotificationSettings(): Promise<NotificationSettingsView> {
  return respond(getStoredNotificationSettings() ?? mockNotificationSettings, 'users');
}

/**
 * API-018 알림 설정 변경 · PUT /api/v1/users/me/notification-settings
 *
 * 전체 교체다. 토글 하나를 바꿔도 다섯 값을 모두 보낸다(계약의 세 키 누락은 400).
 */
export async function updateNotificationSettings(
  settings: NotificationSettingsView,
): Promise<NotificationSettingsView> {
  saveNotificationSettings(settings);

  return respond(settings, 'users');
}

/**
 * 7.8-B 공개 범위 조회. **대응 API가 없다**(types/unspecified.ts `PrivacySettingsView`).
 * 가장 가까운 것은 API-014의 프로필 `visibility`다.
 */
export async function getPrivacySettings(): Promise<PrivacySettingsView> {
  return respond(getStoredPrivacySettings() ?? mockPrivacySettings, 'users');
}

/** 7.8-B 공개 범위 변경. **대응 API가 없다.** 프로필 항목은 API-015 `visibility`에 가깝다 */
export async function updatePrivacySettings(
  settings: PrivacySettingsView,
): Promise<PrivacySettingsView> {
  savePrivacySettings(settings);

  return respond(settings, 'users');
}

function currentBlockedUsers(): BlockedUser[] {
  return getStoredBlockedUsers() ?? mockBlockedUsers;
}

/** 차단한 사용자인지. 7.2-B가 차단한 사람의 프로필을 감출 때 쓴다(API-020 설명) */
export function isBlockedUser(userId: number): boolean {
  return currentBlockedUsers().some((user) => user.userId === userId);
}

/**
 * API-022 차단 목록 조회 · GET /api/v1/users/me/blocks
 *
 * 최근에 차단한 사람이 위다. 계약은 정렬을 적지 않았다. `cursor` · `size`는 구현하지 않았다.
 */
export async function getBlockedUserList(): Promise<BlockedUserList> {
  const items = [...currentBlockedUsers()].sort((a, b) => b.blockedAt.localeCompare(a.blockedAt));

  return respond({ items, nextCursor: null, hasNext: false }, 'users');
}

/**
 * API-020 사용자 차단 · POST /api/v1/users/{userId}/block
 *
 * 오류는 계약의 코드를 그대로 쓴다 — 자기 자신 400 · 없는 사용자 404 · 이미 차단 409.
 * 공동 파티 탈퇴와 초대 무효화(API-020 처리 규칙)는 목업이 흉내 내지 않는다.
 */
export async function blockUser(userId: number): Promise<BlockResult> {
  if (userId === mockMyProfile.userId) {
    return respondError(400, 'CANNOT_BLOCK_SELF', '자기 자신은 차단할 수 없습니다.');
  }

  const target = findMockPublicProfile(userId);

  if (!target) {
    return respondError(404, 'RESOURCE_NOT_FOUND', '사용자를 찾을 수 없습니다.');
  }

  if (isBlockedUser(userId)) {
    return respondError(409, 'ALREADY_BLOCKED', '이미 차단한 사용자입니다.');
  }

  const blockedAt = new Date().toISOString();

  saveBlockedUsers([...currentBlockedUsers(), { userId, nickname: target.nickname, blockedAt }]);

  return respond({ blockedUserId: userId, blockedAt }, 'users');
}

/**
 * API-021 사용자 차단 해제 · DELETE /api/v1/users/{userId}/block
 * 멱등이다. 차단 관계가 없어도 성공한다(API-021 처리 규칙).
 */
export async function unblockUser(userId: number): Promise<void> {
  saveBlockedUsers(currentBlockedUsers().filter((user) => user.userId !== userId));

  return respond(undefined, 'users');
}

/**
 * API-013 회원 탈퇴 · DELETE /api/v1/users/me — **백엔드에도 아직 없다.**
 *
 * `[?]` 계약은 이메일 계정에 `password`를 요구하는데 와이어프레임 7.8-A-1에는 비밀번호
 * 입력이 없다. 목업은 본문 없이 성공한다. 연동 전에 화면이나 계약 중 하나를 정해야 한다.
 */
export async function withdraw(): Promise<void> {
  return respond(undefined, 'users');
}
