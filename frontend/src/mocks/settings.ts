/*
 * 설정 목업. 7.8-B 공개 범위 · 7.8-C 알림 설정
 *
 * 7.8-C의 알림 설정과 7.8-D의 차단 목록은 실제 API가 됐다(업무 -394). 알림은 계약에
 * 없는 토글 둘(`PARTY_INVITE` · `REPORT`)의 **시작값**만 여기 남았고, 차단 목록 목업은
 * 서버가 주는 값과 겹치므로 지웠다.
 *
 * 값은 와이어프레임에 그려진 상태 그대로다. 사용자가 바꾼 값은 세션 저장소가 들고,
 * `src/api/settings.ts`가 둘을 합친다.
 */
import type { NotificationSettingsView, PrivacySettingsView } from '../types/unspecified';

/**
 * 와이어프레임 7.8-C — 마케팅만 꺼져 있다. API-017의 기본값(`MARKETING` false)과도 같다.
 *
 * 계약의 세 키는 서버가 주므로 실제로 쓰이는 것은 `PARTY_INVITE` · `REPORT`뿐이다.
 * 다섯 개를 다 두는 것은 화면이 그리는 상태를 한곳에서 읽기 위해서다.
 */
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
