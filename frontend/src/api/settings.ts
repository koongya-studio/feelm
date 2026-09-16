/*
 * 설정 화면(7.8 ~ 7.8-D) 데이터 접근 함수.
 * 대응 명세는 docs/api/users.md(API-013 · API-017 · API-018 · API-020 ~ API-022)이다.
 *
 * **알림 설정과 차단은 실제 API다**(업무 S15P21E106-394). 공개 범위(7.8-B)와 회원
 * 탈퇴(API-013)는 목업으로 남는다 — 공개 범위는 대응 계약이 없고, 탈퇴는 백엔드가
 * 요구하는 비밀번호가 화면에 없다(아래 `withdraw`). 두 함수는 계약의 경로 · 본문
 * 모양을 지키고 있어 결정이 서면 본문만 바꾸면 된다.
 *
 * 알림 설정은 화면과 계약의 항목 수가 다르다. 7.8-C는 토글이 다섯이고 계약(API-017)은
 * 셋이라, **계약의 셋만 서버로 보내고 나머지 둘은 세션 저장소에 남긴다**(사용자 결정
 * 2026-09-16). 한 줄 소개(api/users.ts)와 같은 처리이며, 새 세션에서는 기준 목업
 * 값으로 돌아간다.
 *
 * 로그아웃(API-005)은 이미 실제 호출이다(`api/auth.ts`). 화면은 `useAuth().signOut`을 쓴다.
 */
import {
  getPrivacySettings as getStoredPrivacySettings,
  getUnspecifiedNotificationSettings,
  savePrivacySettings,
  saveUnspecifiedNotificationSettings,
} from '../mocks/sessionStore';
import { mockNotificationSettings, mockPrivacySettings } from '../mocks/settings';
import { request } from './client';
import { respond } from './mockTransport';
import type {
  NotificationSettingsView,
  PrivacySettingsView,
  UnspecifiedNotificationSettings,
} from '../types/unspecified';
import type { BlockedUserList, BlockResult, NotificationSettings } from '../types/user';

const NOTIFICATION_SETTINGS_PATH = '/users/me/notification-settings';

/**
 * 차단 목록 한 번에 받는 수. 계약의 상한이다(API-022 `size`는 1 ~ 50).
 *
 * `[?]` 7.8-D에는 더 보기가 없다. 상한을 넘게 차단한 사람은 화면에 나오지 않으므로
 * `hasNext`면 `N명 차단 중`을 감춘다(7.5 `총 N편`과 같은 처리).
 */
const BLOCK_PAGE_SIZE = 50;

/** 계약에 없는 토글 둘. 서버에 보낼 곳이 없어 세션 저장소가 들고 있다 */
function unspecifiedNotificationSettings(): UnspecifiedNotificationSettings {
  return (
    getUnspecifiedNotificationSettings() ?? {
      PARTY_INVITE: mockNotificationSettings.PARTY_INVITE,
      REPORT: mockNotificationSettings.REPORT,
    }
  );
}

/**
 * API-017 알림 설정 조회 · GET /api/v1/users/me/notification-settings
 *
 * 응답은 계약의 세 키뿐이라 화면이 그리는 다섯 개로 채워서 돌려준다.
 */
export async function getNotificationSettings(): Promise<NotificationSettingsView> {
  const settings = await request<NotificationSettings>(NOTIFICATION_SETTINGS_PATH);

  return { ...settings, ...unspecifiedNotificationSettings() };
}

/**
 * API-018 알림 설정 변경 · PUT /api/v1/users/me/notification-settings
 *
 * 전체 교체다. 토글 하나를 바꿔도 계약의 세 값을 모두 보낸다(세 키 누락은 400).
 * 계약에 없는 둘만 바뀌어도 같은 요청이 나간다 — API-018은 멱등이고, 화면이 어느
 * 토글을 눌렀는지 이 함수는 알지 못한다.
 *
 * 세션 저장소에는 **서버가 받아들인 뒤에만** 쓴다. 실패하면 화면이 누르기 전 값으로
 * 되돌리므로 로컬에만 남으면 두 값이 어긋난다.
 */
export async function updateNotificationSettings(
  settings: NotificationSettingsView,
): Promise<NotificationSettingsView> {
  const { RATING, RECOMMENDATION, MARKETING, PARTY_INVITE, REPORT } = settings;

  const saved = await request<NotificationSettings>(NOTIFICATION_SETTINGS_PATH, {
    method: 'PUT',
    body: { RATING, RECOMMENDATION, MARKETING },
  });

  saveUnspecifiedNotificationSettings({ PARTY_INVITE, REPORT });

  return { ...saved, PARTY_INVITE, REPORT };
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

/**
 * API-022 차단 목록 조회 · GET /api/v1/users/me/blocks
 *
 * 최근에 차단한 사람이 위다(서버 정렬). 계약의 `cursor`는 쓰지 않는다 — 7.8-D에
 * 더 보기가 없어 첫 페이지만 그린다.
 */
export async function getBlockedUserList(): Promise<BlockedUserList> {
  return request<BlockedUserList>('/users/me/blocks', { query: { size: BLOCK_PAGE_SIZE } });
}

/**
 * 차단한 사용자인지.
 *
 * **목업 공개 프로필(7.2-B)이 차단한 사람을 감출 때만 쓴다.** 계약이 열리면 서버가
 * 판정하므로(API-062 `isBlockedByMe`, API-020 설명) 이 함수는 사라진다. 목록 조회가
 * 실패하면 감추지 않는다 — 프로필을 못 보는 것보다 낫고, 차단 자체는 서버에 남는다.
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
 * 오류는 계약의 코드 그대로 올라간다 — 자기 자신 400 `CANNOT_BLOCK_SELF` · 없는
 * 사용자 404 `RESOURCE_NOT_FOUND` · 이미 차단 409 `ALREADY_BLOCKED`. 화면이 가른다.
 *
 * `[!]` 7.2-B에 들어오는 `userId`는 아직 목업 번호다. 공개 프로필(API-062)과 파티
 * (S15P21E106-393)가 실제 API가 되기 전까지 이 호출은 대부분 404다.
 */
export async function blockUser(userId: number): Promise<BlockResult> {
  return request<BlockResult>(`/users/${userId}/block`, { method: 'POST' });
}

/**
 * API-021 사용자 차단 해제 · DELETE /api/v1/users/{userId}/block
 * 멱등이다. 차단 관계가 없어도 204다(API-021 처리 규칙).
 */
export async function unblockUser(userId: number): Promise<void> {
  return request<void>(`/users/${userId}/block`, { method: 'DELETE' });
}

/**
 * API-013 회원 탈퇴 · DELETE /api/v1/users/me — **목업이다.**
 *
 * `[?]` 백엔드에는 구현돼 있다(S15P21E106-198). 다만 계약과 구현이 이메일 계정에
 * `password`를 요구하는데 와이어프레임 7.8-A-1에는 비밀번호 입력이 없어, 지금 연동하면
 * 탈퇴가 항상 실패한다. 화면과 계약 중 어느 쪽을 바꿀지 정한 뒤 연동한다(394 범위 밖).
 * 목업은 본문 없이 성공한다.
 */
export async function withdraw(): Promise<void> {
  return respond(undefined, 'users');
}
