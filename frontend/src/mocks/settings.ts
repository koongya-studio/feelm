/*
 * 설정 목업. 7.8-B 공개 범위 · 7.8-C 알림 설정 · 7.8-D 차단 사용자 관리
 *
 * 대응 계약은 API-017 · API-018(알림) · API-020 ~ API-022(차단)이다. 백엔드에 구현돼 있지만
 * 이 업무는 목업으로 만들기로 했다(팀 결정 2026-09-13, 업무 -307).
 *
 * 값은 와이어프레임에 그려진 상태 그대로다. 사용자가 바꾼 값은 세션 저장소가 들고,
 * `src/api/settings.ts`가 둘을 합친다.
 */
import type { NotificationSettingsView, PrivacySettingsView } from '../types/unspecified';
import type { BlockedUser } from '../types/user';

/** 와이어프레임 7.8-C — 마케팅만 꺼져 있다. API-017의 기본값(`MARKETING` false)과도 같다 */
export const mockNotificationSettings: NotificationSettingsView = {
  RATING: true,
  RECOMMENDATION: true,
  MARKETING: false,
  PARTY_INVITE: true,
  REPORT: true,
};

/** 와이어프레임 7.8-B — 취향 필름만 `파티원만`이다 */
export const mockPrivacySettings: PrivacySettingsView = {
  profile: 'PUBLIC',
  film: 'PARTY',
  popcorn: 'PUBLIC',
  review: 'PUBLIC',
};

/**
 * 와이어프레임 7.8-D의 두 사람. 다른 목업(파티원 · 한줄평 작성자)과 겹치지 않는 번호다.
 * 시각은 정오 기준이다(mocks/frames.ts와 같은 이유).
 */
export const mockBlockedUsers: BlockedUser[] = [
  { userId: 5201, nickname: '스포일러왕', blockedAt: '2026-08-30T12:00:00Z' },
  { userId: 5202, nickname: '밤샘시네필', blockedAt: '2026-08-21T12:00:00Z' },
];
